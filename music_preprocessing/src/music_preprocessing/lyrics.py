"""가사 등록 — 메타데이터에 실려 온 정렬 결과를 Mupid API 에 올린다.

가사는 곡 메타데이터(`metadata/T000034.json`) 안의 `lyrics_sync` 에 있다.
`catalog` 가 읽는 파일과 같은 파일이다.

```json
"lyrics_sync": [
  { "text": "Sun goes down across the sky", "start_sec": null,  "end_sec": null },
  { "text": "Left my big speaker on the stage", "start_sec": 30.0, "end_sec": 32.1 }
]
```

연주곡은 이 값이 `null` 이다. 곡 1060개 중 **가사가 있는 505곡**만 올린다.

서버의 `lyrics` 테이블은 `text` 와 `time_ms` 둘뿐이라 여기서 쓰는 것도
`text` 와 `start_sec` 뿐이다. `end_sec` 은 담을 자리가 없다.

## 비어 있는 타이밍

**가사 23,675줄 중 3,419줄(14%)에 `start_sec` 이 없다.** 정렬이 그 줄을 음원에
못 붙인 것이다. `lyrics.time_ms` 는 NOT NULL 이라 그냥은 넣을 수 없다.

- **타이밍을 메운다 (기본).** 앞뒤로 시각을 아는 줄 사이를 고르게 나눈다.
  가사 줄이 빠지지 않는다. 대신 그 줄의 시각은 **추정값**이다.
- **버린다 (`--skip-untimed`).** 시각이 확실한 줄만 올린다. 대신 한 절에서
  가운데 줄이 사라져 가사가 이가 빠진 채로 보인다.

기본을 "메운다" 로 둔 이유: 가사는 **읽는 것**이 먼저고 따라가는 것은
그다음이다. 줄이 통째로 사라지면 노래를 따라 부를 수 없지만, 한두 줄의 시각이
조금 어긋나는 것은 눈으로 보정된다.

결측은 대부분 **사이**에 있다(구간 1,500개 중 사이 1,271 · 앞 125 · 뒤 104,
중앙값 1줄). 사이는 앞뒤 시각을 알아 고르게 나눌 수 있고, 앞뒤 끝은 그 곡의
평균 줄 간격으로 늘려 잡는다.

## 못 올리는 곡

| 경우 | 곡 수 | 동작 |
| --- | --- | --- |
| `lyrics_present` 인데 `lyrics_sync` 가 비어 있음 | 11 | 올릴 것이 없다. 기록만 남기고 넘어간다 |
| 시각을 아는 줄이 하나도 없음 (`T000407`) | 1 | 기준점이 없어 메울 수 없다. **건너뛴다** |

둘 다 실패로 세지 않는다. 사람이 원본을 고치기 전에는 다시 시도해도 같다.

## 곡마다 한 번

`PUT /api/v1/music/{musicId}/lyric` 이 그 곡의 가사를 **통째로 갈아 끼운다.**
줄마다 `POST` 를 부르던 때는 505곡에 23,628번이었지만 이제 505번이다.

지우는 일도 서버가 한다. 배치가 기존 가사를 조회해 한 줄씩 지울 필요가 없다.
곡 하나가 서버에서 한 트랜잭션이라, 중간에 끊겨도 반쯤 올라간 가사가 남지
않는다.
"""

from __future__ import annotations

import hashlib
import json
import logging
from pathlib import Path

from .api_client import MupidClient
from .catalog import CatalogError, ProgressReporter
from .registrar import Registry

logger = logging.getLogger(__name__)

# 앞뒤 끝의 시각을 늘려 잡을 때, 곡에 기준이 하나뿐이라 간격을 못 구하면 쓰는 값.
_DEFAULT_LINE_INTERVAL_SEC = 3.0

# 서버의 `lyrics.text` 는 VARCHAR(500) 이다. 지금 데이터의 최대 길이는 63자라
# 걸릴 일이 없지만, 넘으면 INSERT 가 통째로 실패하므로 올리기 전에 잡는다.
TEXT_MAX_LENGTH = 500


class LyricSheet:
    """곡 하나의 가사. 시각이 매겨진 줄 목록과, 어떻게 매겼는지의 내역."""

    __slots__ = ("track_id", "lines", "timed", "interpolated", "dropped", "sha256")

    def __init__(
        self,
        track_id: str,
        lines: list[tuple[int, str]],
        timed: int,
        interpolated: int,
        dropped: int,
        sha256: str,
    ) -> None:
        self.track_id = track_id
        #: `(time_ms, text)` 를 시각 오름차순으로
        self.lines = lines
        #: 파일에 시각이 있던 줄
        self.timed = timed
        #: 시각을 메워 넣은 줄
        self.interpolated = interpolated
        #: 시각이 없어 버린 줄 (`--skip-untimed` 일 때만 0 이 아니다)
        self.dropped = dropped
        self.sha256 = sha256

    def __len__(self) -> int:
        return len(self.lines)


# ─────────────────────────────────────────────────────────────
# 읽기
# ─────────────────────────────────────────────────────────────


def load_lyrics(
    metadata_dir: Path, skip_untimed: bool = False
) -> dict[str, LyricSheet]:
    """메타데이터 디렉터리에서 가사를 뽑는다. track_id → 가사.

    `lyrics_sync` 가 비어 있는 곡(연주곡)은 아예 넣지 않는다. 등록할 것이
    없는데 목록에 두면 "건너뜀 555곡" 같은 숫자가 매번 화면을 채운다.
    """
    metadata_dir = Path(metadata_dir)
    files = sorted(metadata_dir.glob("*.json"))
    if not files:
        raise CatalogError(f"메타데이터 JSON 이 없습니다: {metadata_dir}")

    sheets: dict[str, LyricSheet] = {}
    empty = 0
    for path in files:
        with open(path, encoding="utf-8") as f:
            raw = json.load(f)
        rows = raw.get("lyrics_sync") or []
        if not rows and not raw.get("flow_generated", {}).get("lyrics_present"):
            continue  # 연주곡. 가사 자체가 없는 곡이다
        if not rows:
            empty += 1  # 가사가 있다는데 정렬 결과가 비어 있다
        track_id = str(raw.get("track_id") or path.stem).strip()
        sheets[track_id] = _build_sheet(track_id, rows, path, skip_untimed)

    logger.info(
        "가사 %d곡을 읽었습니다: %s (정렬 결과가 빈 곡 %d개 포함)",
        len(sheets),
        metadata_dir,
        empty,
    )
    return sheets


def _build_sheet(
    track_id: str, rows: list[dict], path: Path, skip_untimed: bool
) -> LyricSheet:
    """`lyrics_sync` 한 곡분을 시각이 매겨진 줄 목록으로 바꾼다."""
    rows = [row for row in rows if (row.get("text") or "").strip()]
    for row in rows:
        text = row["text"].strip()
        if len(text) > TEXT_MAX_LENGTH:
            raise CatalogError(
                f"{path.name}: 가사 한 줄이 {TEXT_MAX_LENGTH}자를 넘습니다 "
                f"({len(text)}자): {text[:40]}…"
            )

    # 지문은 파일이 아니라 `lyrics_sync` 만 가지고 낸다. 같은 파일에 들어 있는
    # 곡명·아티스트가 바뀌었다고 가사 23,000줄을 다시 올릴 이유가 없다.
    digest = _fingerprint(rows)

    if skip_untimed:
        kept = [r for r in rows if r.get("start_sec") is not None]
        lines = _make_non_decreasing(
            [(_to_ms(r["start_sec"]), r["text"].strip()) for r in kept]
        )
        return LyricSheet(track_id, lines, len(kept), 0, len(rows) - len(kept), digest)

    times = _fill_times([r.get("start_sec") for r in rows])
    if times is None:
        # 기준점이 하나도 없다. 줄은 있는데 어디에 놓을지 모른다.
        return LyricSheet(track_id, [], 0, 0, len(rows), digest)

    timed = sum(1 for r in rows if r.get("start_sec") is not None)
    lines = _make_non_decreasing(
        [(_to_ms(t), r["text"].strip()) for t, r in zip(times, rows)]
    )
    return LyricSheet(track_id, lines, timed, len(rows) - timed, 0, digest)


def _fingerprint(rows: list[dict]) -> str:
    """가사 내용만의 SHA-256. 바뀌지 않았으면 다시 올리지 않는다."""
    payload = json.dumps(
        [(r.get("text"), r.get("start_sec")) for r in rows],
        ensure_ascii=False,
        sort_keys=True,
    )
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _to_ms(seconds: float) -> int:
    """초 → 밀리초. `time_ms` 는 부호 없는 정수라 음수를 넘길 수 없다."""
    return max(0, round(float(seconds) * 1000))


def _make_non_decreasing(lines: list[tuple[int, str]]) -> list[tuple[int, str]]:
    """시각이 뒤로 가는 일이 없게 만든다.

    원본은 단조 증가라 손볼 일이 거의 없지만, 앞쪽을 0 으로 깎는 과정에서
    어긋날 수 있다. 가사 조회가 `time_ms` 순으로 정렬하므로, 여기서 어긋나면
    화면에 뒤섞여 나온다.
    """
    result: list[tuple[int, str]] = []
    last = 0
    for time_ms, text in lines:
        last = max(last, time_ms)
        result.append((last, text))
    return result


def _fill_times(raw_times: list[float | None]) -> list[float] | None:
    """시각이 빈 줄을 앞뒤 기준점 사이에 고르게 나눠 채운다.

    기준점이 하나도 없으면 None — 그 곡은 놓을 자리를 알 수 없다.
    """
    anchors = [(i, t) for i, t in enumerate(raw_times) if t is not None]
    if not anchors:
        return None

    interval = _line_interval(anchors)
    filled: list[float] = [0.0] * len(raw_times)

    first_index, first_time = anchors[0]
    last_index, last_time = anchors[-1]

    # 첫 기준점 앞: 평균 간격만큼 거슬러 올라간다. 0 아래로는 내려가지 않는다.
    for i in range(first_index):
        filled[i] = max(0.0, first_time - (first_index - i) * interval)

    # 기준점 사이: 두 시각을 줄 수만큼 고르게 나눈다.
    for (start_index, start_time), (end_index, end_time) in zip(anchors, anchors[1:]):
        filled[start_index] = start_time
        gap = end_index - start_index
        for step in range(1, gap):
            filled[start_index + step] = (
                start_time + (end_time - start_time) * step / gap
            )
    filled[last_index] = last_time

    # 마지막 기준점 뒤: 평균 간격만큼 이어 붙인다.
    for i in range(last_index + 1, len(raw_times)):
        filled[i] = last_time + (i - last_index) * interval

    return filled


def _line_interval(anchors: list[tuple[int, float]]) -> float:
    """그 곡의 평균 줄 간격(초). 기준점이 하나뿐이면 기본값을 쓴다."""
    if len(anchors) < 2:
        return _DEFAULT_LINE_INTERVAL_SEC
    (first_index, first_time), (last_index, last_time) = anchors[0], anchors[-1]
    span = last_time - first_time
    steps = last_index - first_index
    if span <= 0 or steps <= 0:
        return _DEFAULT_LINE_INTERVAL_SEC
    return span / steps


# ─────────────────────────────────────────────────────────────
# 등록
# ─────────────────────────────────────────────────────────────


class LyricRegistrar:
    """가사를 곡에 붙인다.

    `catalog` 가 먼저 돌아 있어야 한다. 곡 번호는 `registered.json` 의
    `catalog.tracks` 에서 가져온다 — 메타데이터에는 곡 번호가 없다.

    곡마다 `PUT /api/v1/music/{musicId}/lyric` 을 한 번 부른다. 그 호출이
    기존 가사를 지우고 새 줄을 넣는 일을 한 트랜잭션으로 처리하므로, 다시
    돌려도 두 벌이 겹치지 않는다.
    """

    def __init__(
        self,
        client: MupidClient | None,
        registry: Registry,
        dry_run: bool = False,
        progress: ProgressReporter | None = None,
    ) -> None:
        self.client = client
        self.registry = registry
        self.dry_run = dry_run
        self.progress = progress or ProgressReporter()
        if not dry_run and client is None:
            raise CatalogError("dry-run 이 아니면 API 클라이언트가 있어야 합니다.")

    def run(self, sheets: dict[str, LyricSheet], force: bool = False) -> dict:
        """가사를 등록한다.

        Args:
            sheets: `load_lyrics` 가 읽은 것
            force: 내용이 그대로여도 지우고 다시 올린다
        """
        summary = {
            "songs_total": len(sheets),
            "songs_registered": 0,
            "songs_unchanged": 0,
            "songs_empty": 0,
            "songs_untimed": 0,
            "songs_unregistered": 0,
            "lines_written": 0,
            "lines_deleted": 0,
            "lines_timed": 0,
            "lines_interpolated": 0,
            "lines_dropped": 0,
            "errors": [],
        }

        self.progress.phase("가사", len(sheets))
        for track_id in sorted(sheets):
            sheet = sheets[track_id]
            try:
                self._register(sheet, force, summary)
            except Exception as e:  # noqa: BLE001 — 한 곡이 깨져도 나머지를 올린다
                logger.error("가사 등록 실패: %s - %s", track_id, e)
                summary["errors"].append({"key": track_id, "error": str(e)})
            self.progress.advance("가사")

        self._save()
        return summary

    def _register(self, sheet: LyricSheet, force: bool, summary: dict) -> None:
        track = self.registry.catalog_entry("tracks", sheet.track_id)
        if not track or not track.get("music_id"):
            summary["songs_unregistered"] += 1
            return
        music_id = track["music_id"]

        previous = self.registry.catalog_entry("lyrics", sheet.track_id)
        if previous and previous.get("sha256") == sheet.sha256 and not force:
            summary["songs_unchanged"] += 1
            return

        if not sheet.lines:
            # 가사 줄이 없거나(11곡) 기준 시각이 없는 곡(1곡). 올릴 것이 없다.
            # 기록은 남긴다 — 다음 실행이 같은 파일을 또 들여다보지 않게.
            key = "songs_untimed" if sheet.dropped else "songs_empty"
            summary[key] += 1
            logger.warning(
                "가사를 올리지 못했습니다: %s (%s)",
                sheet.track_id,
                "시각을 아는 줄이 없음" if sheet.dropped else "가사 줄이 없음",
            )
            self._remember(sheet, music_id, 0)
            return

        # 교체다. 지난번이 중간에 끊겨 반쯤 올라가 있어도 서버가 먼저 비운다.
        result = self._replace(music_id, sheet.lines)
        summary["lines_deleted"] += result.get("deleted", 0)
        summary["lines_written"] += len(sheet.lines)
        summary["lines_timed"] += sheet.timed
        summary["lines_interpolated"] += sheet.interpolated
        summary["lines_dropped"] += sheet.dropped
        summary["songs_registered"] += 1

        self._remember(sheet, music_id, len(sheet.lines))
        logger.info(
            "가사 등록: %s → music_id=%s (%d줄, 시각 추정 %d줄)",
            sheet.track_id,
            music_id,
            len(sheet.lines),
            sheet.interpolated,
        )

    def _remember(self, sheet: LyricSheet, music_id: int, line_count: int) -> None:
        self.registry.put_catalog_entry(
            "lyrics",
            sheet.track_id,
            {
                "music_id": music_id,
                "line_count": line_count,
                "timed": sheet.timed,
                "interpolated": sheet.interpolated,
                "dropped": sheet.dropped,
                "sha256": sheet.sha256,
            },
        )
        self._save()

    def _replace(self, music_id: int, lines: list[tuple[int, str]]) -> dict:
        """곡 하나의 가사를 통째로 갈아 끼운다."""
        if self.dry_run:
            return {}
        return self.client.replace_lyrics(music_id, lines)

    def _save(self) -> None:
        """dry-run 은 기록을 남기지 않는다 — 다음 실제 실행이 전부 건너뛰게 된다."""
        if self.dry_run:
            return
        self.registry.save()
