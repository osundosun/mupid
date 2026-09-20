"""서버에 없는 곡을 찾아 등록 기록에 예외로 남긴다.

`registered.json` 은 배치가 곡을 등록하며 남긴 기록이다. 그 곡이 서버에서
**소프트 삭제**되면 조회 경로가 전부 그 곡을 감춘다.

| 부르는 것 | 삭제된 곡에 대한 응답 |
| --- | --- |
| `GET /api/v1/music` | 목록에 없음 (`m.isDeleted = false` 필터) |
| `GET /api/v1/music/{id}` | 404 (없음) / **410 Gone** (삭제됨) |
| `PATCH /api/v1/music/{id}` | 404 / **410** |
| `GET /api/v1/music/{id}/analysis` | 404 / **410** |
| `PUT /api/v1/music/{id}/classification` | 404 / **410** (2026-09-18 수정 — 그전엔 삭제된 곡에도 통과했다) |

그래서 목록만 보면 배치는 다음 두 가지를 **구분할 수 없다.**

1. 곡이 소프트 삭제됐다 — 곡 ID 몇 개가 목록에서 빠진다. 흔한 일이고 배치가
   멈출 이유가 없다. **이 곡은 포기해야 한다**(원본을 지울지 되살릴지는 사람이 정한다).
2. `registered.json` 이 낡았다(DB 를 새로 띄웠다) — **1060곡 전부** 빠진다.
   서버의 곡 번호가 바뀐 것이라 **기록을 다시 맞춰야 한다.**
   이때 배치가 조용히 전곡을 건너뛰고 종료 0 으로 끝나면 아무도 모른다.

## 그래서 목록에 없던 곡만 단건 조회한다 (2026-09-18)

곡별 단건 조회(`GET /api/v1/music/{id}`)가 그 둘을 가른다 — **410 Gone = 삭제**,
**404 = 그런 번호가 없음(기록이 낡음)**. 표식의 `verdict` 에 판정이 남아 사람이
다음에 뭘 할지(`repair-track` 인지 원본 정리인지) 알 수 있다.

**곡마다 부르지 않는다.** 목록에 있던 곡은 이미 살아 있는 것이 확인됐으니 부르지
않고, 목록에서 빠진 곡만 부른다 — 1060회가 아니라 삭제·노후화가 확인된 몇 회다.
표식이 이미 붙은 곡도 다시 묻지 않는다(지난 실행의 판정이 파일에 남아 있다).

단건 조회는 200 을 줄 수도 있다 — 목록과 어긋났을 뿐 살아 있는 곡이다. 그 곡에는
**표식을 붙이지 않는다.** 붙이면 배치가 멀쩡한 곡을 영영 건너뛴다.

## 비율로 가르지 않는다

처음에는 누락 비율이 일정 이상이면 (2) 로 판정하려 했다. **틀렸다** — 곡은 몇
개든 삭제될 수 있다. 1060곡 중 500곡이 지워져도 그건 정상적인 운영 결과일 수
있으므로, 비율은 삭제와 기록 노후화를 가르는 신호가 되지 못한다.

대신 **곡별 예외 항목**으로 관리한다. 누락이 확인된 곡은 그 자체로 예외가 되고,
개수에 상한을 두지 않는다. 예외가 아닌 곡이 갑자기 사라지면 그때 다시 누락으로
잡히므로, 전곡이 한꺼번에 빠지는 상황도 그대로 드러난다.

## 표식을 어디에 남기는가

새 파일을 만들지 않는다. 곡 번호를 이미 기억하는 `registered.json` 의
`catalog.tracks[track_id]` 항목에 `server_missing` 을 붙인다.

```json
"T000957": {
  "music_id": 957, "title": "...", "...": "...",
  "server_missing": {
    "detected_at": "2026-09-18T18:20:00+09:00",
    "music_id": 957,
    "verdict": "deleted",
    "reason": "서버에서 삭제됨 (410 Gone)"
  }
}
```

- 표식이 있는 곡은 `PATCH`·재등록·가사·분류를 **건너뛴다.** 410 을 맞고 배치를
  실패시키는 대신, 조용히 지나가되 로그와 보고서에 남긴다.
- **곡이 서버에 다시 나타나면 표식을 지운다.** 손으로 되살린 곡을 영영 방치하지
  않기 위해서다. 다음 실행부터 평소처럼 동기화된다.
- 서버에서 곡을 지운 것과 주피터의 원본 파일을 지우는 것은 **다른 일**이다.
  배치는 원본 오디오·메타데이터를 절대 건드리지 않는다. 그 판단은 사람이 한다.

## 무엇을 보고하는가

`누락(missing)` 과 `곡명 불일치(title_mismatch)` 를 나눈다.

- 누락 — 곡 번호가 서버 목록에 없다. 삭제로 본다. **건너뛴다.**
- 곡명 불일치 — 곡 번호는 있는데 곡명이 다르다. `registered.json` 이 다른 곡을
  가리키는 상태다. 그대로 두면 `PUT classification` 이 **엉뚱한 곡의 분류를
  지운다**(그 엔드포인트는 삭제 여부도 보지 않는다). 그래서 이쪽은 한 건도 쓰기
  전에 멈춘다 — 건너뛰는 것으로는 막을 수 없는 위험이다.
"""

from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone

from .api_client import GONE_CODE, GONE_STATUS, MupidApiError
from .registrar import Registry

logger = logging.getLogger(__name__)

# 등록 기록에 붙이는 예외 표식의 키.
EXEMPT_KEY = "server_missing"

# 한 번에 읽는 곡 수. 서버가 100 을 넘기면 100 으로 깎는다(`MusicServiceImpl`).
PAGE_SIZE = 100

# 한국 시간. 보고용 시각을 사람이 읽는 형태로 남긴다.
_KST = timezone(timedelta(hours=9))


class ServerAuditError(RuntimeError):
    """서버 곡 목록을 읽지 못했다. 판단을 보류한다."""


# ─────────────────────────────────────────────────────────────
# 서버 곡 목록
# ─────────────────────────────────────────────────────────────


def index_server_music(client, page_size: int = PAGE_SIZE) -> dict[int, dict]:
    """서버의 살아 있는 곡을 `music_id → 행` 으로 모은다.

    `GET /api/v1/music` 은 삭제된 곡을 걸러서 준다(`findPage` 의
    `m.isDeleted = false`). 그래서 **여기에 없다는 것이 곧 "보이지 않는다"** 이고,
    **이 목록만으로는 삭제인지 기록 노후화인지 알 수 없다.**

    서버는 단건 조회에서 그 둘을 가른다 — 삭제된 곡은 **410 Gone**, 그런 번호가
    아예 없으면 404 다(2026-09-18). 다만 **배치는 그 조회를 아직 부르지 않는다**
    (`MupidClient` 에 단건 곡 조회가 없다). 곡마다 부르면 1060회, 목록은 11회라
    지금은 목록으로 훑고 구분하지 못한 채 전부 표식으로 남긴다 —
    `repair_one` 도 같은 목록만 본다. 곡별 구분이 필요해지면 그때 단건 조회를
    더한다(410·404 판정은 `orphans.scan_streams` 에 이미 있다).

    훑는 일 자체는 `MupidClient.music_rows` 가 한다. 응답 키는 snake_case 다
    (`total_elements`·`total_pages`) — 서버의
    `spring.jackson.property-naming-strategy: SNAKE_CASE` 가 여기에도 걸린다.
    """
    try:
        rows = client.music_rows(page_size)
    except Exception as e:  # noqa: BLE001 — 호출 쪽이 보류로 처리한다
        raise ServerAuditError(f"서버 곡 목록을 읽지 못했습니다: {e}") from e

    index: dict[int, dict] = {}
    for row in rows:
        music_id = row.get("music_id")
        if not isinstance(music_id, int) or music_id <= 0:
            raise ServerAuditError(f"곡 번호가 이상합니다: {music_id!r}")
        if music_id in index:
            raise ServerAuditError(f"곡 목록에 같은 번호가 두 번: {music_id}")
        index[music_id] = row
    return index


def _title_of(row: dict) -> str:
    return str(row.get("title") or "").strip()


# ─────────────────────────────────────────────────────────────
# 대조
# ─────────────────────────────────────────────────────────────


class AuditResult:
    """등록 기록과 서버를 대조한 결과. **아무것도 쓰지 않는다.**

    Attributes:
        checked: 대조한 곡 수 (`registered.json` 의 `catalog.tracks`)
        server_total: 서버가 보여 준 살아 있는 곡 수
        missing: 곡 번호가 서버 목록에 없는 곡 (`track_id` 기준 오름차순)
        title_mismatch: 곡 번호는 있는데 곡명이 다른 곡
        exempt: 이번에 예외로 남길 곡 — 누락이면서 아직 표식이 없던 것
        recovered: 표식이 있었는데 서버에 다시 나타난 곡
        exempt_total: 이번 실행 후 표식이 남아 있을 곡 수
        verdicts: 곡별로 왜 없는지 (`track_id → "deleted" | "stale_record"`).
            단건 조회를 켰을 때만 채워진다
        alive_but_unlisted: 목록에 없었는데 단건 조회는 살아 있다고 답한 곡
        classified: 단건 조회로 판정한 곡 수 (요청 수)
    """

    def __init__(self) -> None:
        self.checked = 0
        self.server_total = 0
        self.missing: list[dict] = []
        self.title_mismatch: list[dict] = []
        self.exempt: list[dict] = []
        self.recovered: list[str] = []
        self.exempt_total = 0
        self.verdicts: dict[str, str] = {}
        self.alive_but_unlisted: list[dict] = []
        self.classified = 0

    def verdict_of(self, track_id: str) -> str | None:
        """왜 없는지. 단건 조회를 안 켰으면 None 이다."""
        return self.verdicts.get(track_id)

    @property
    def ok(self) -> bool:
        """쓸 수 있는 상태인가. 곡명 불일치는 한 건이라도 있으면 아니다."""
        return not self.title_mismatch

    def as_report(self) -> dict:
        """보고서·요약에 넣을 형태.

        누락 곡은 **전부** 싣는다. 첫 곡에서 멈추면 나머지를 알 수 없다는 것이
        이 티켓의 요구사항이다.
        """
        return {
            "checked": self.checked,
            "server_total": self.server_total,
            "missing": [dict(item) for item in self.missing],
            "title_mismatch": [dict(item) for item in self.title_mismatch],
            "newly_exempt": [item["track_id"] for item in self.exempt],
            "recovered": list(self.recovered),
            # 왜 없는지 — 단건 조회를 켰을 때만 찬다 (`deleted` / `stale_record`).
            "verdicts": dict(self.verdicts),
            "alive_but_unlisted": [dict(item) for item in self.alive_but_unlisted],
            "classified": self.classified,
            "exempt_total": self.exempt_total,
        }

    def missing_track_ids(self) -> list[str]:
        return [item["track_id"] for item in self.missing]


def classify_server_missing(
    client,
    tracks: dict,
    missing: list[dict],
) -> tuple[dict[str, str], list[dict]]:
    """목록에 없던 곡을 **왜 없는지** 곡별로 가른다.

    곡 목록(`GET /api/v1/music`)은 삭제된 곡을 걸러서 주므로 "없다" 까지만 알려
    준다. 그런데 **삭제된 것과 등록 기록이 낡은 것은 사람이 할 일이 다르다** —
    앞은 그 곡을 포기할지 정해야 하고(`repair-track` 으로 기록 정리),
    뒤는 `registered.json` 이 틀린 것이라 서버의 그 곡 번호를 다시 맞춰야 한다.

    서버는 단건 조회에서 그 둘을 가른다(2026-09-18):

    | 응답 | 뜻 |
    | --- | --- |
    | **410 Gone** (`MUPID_MUSIC_GONE`) | 서버에서 소프트 삭제됐다 |
    | **404** (`MUPID_MUSIC_NOT_FOUND`) | 그런 곡 번호가 아예 없다 — 기록이 낡았다 |
    | 200 | 살아 있다 (목록과 어긋났다. 표식을 붙이지 않는다) |

    **목록에 없던 곡만** 부른다. 곡마다 부르면 1060회지만 이러면 삭제·노후화가
    확인된 몇 곡뿐이다.

    Args:
        client: 서버 클라이언트
        tracks: `registered.json` 의 `catalog.tracks`
        missing: `audit_registry` 가 목록 대조로 찾아낸 곡들

    Returns:
        `(track_id → verdict, 다시 살아 있는 것으로 확인된 곡들)`.
        verdict 는 `"deleted"` 또는 `"stale_record"`.
    """
    verdicts: dict[str, str] = {}
    alive: list[dict] = []

    for item in missing:
        track_id = item["track_id"]
        music_id = item.get("music_id")
        if not isinstance(music_id, int) or music_id <= 0:
            # 곡 번호가 없으면 물어볼 수 없다. 삭제로 단정하지 않고 노후화로 둔다.
            verdicts[track_id] = "stale_record"
            continue

        # 살아 있는지 여부를 따로 든다. `row` 는 성공 경로에서만 채워지므로
        # 예외로 끝난 경우를 `row is None` 으로 판단하면 안 된다.
        alive_row = None
        try:
            alive_row = client.get_music(music_id)
        except MupidApiError as e:
            if e.code == GONE_CODE or e.status == GONE_STATUS:
                verdicts[track_id] = "deleted"
            elif e.status == 404:
                # 그런 번호가 없다. 목록에 없는 것과 같은 답이라 노후화로 본다.
                verdicts[track_id] = "stale_record"
            else:
                # 인증 실패·서버 장애. 판단하지 않는다 — 삭제로 단정하면 사람이
                # 멀쩡한 곡을 걷어낸다.
                raise ServerAuditError(
                    f"곡 단건 조회에 실패해 삭제 여부를 가릴 수 없습니다 "
                    f"({track_id}, music_id={music_id}): {e}"
                ) from e

        if alive_row is not None:
            # 살아 있다. 이 곡은 `missing` 에서 빼야 한다 — 목록과 어긋났을 뿐이다.
            alive.append(
                {"track_id": track_id, "music_id": music_id, "title": item.get("title")}
            )
        elif track_id not in verdicts:
            # `MupidClient.get_music` 은 **삭제된 곡에 예외를 던지지 않고 `None` 을
            # 준다**(410 을 그 안에서 흡수한다). 그래서 여기서 그 뜻을 담아야 한다 —
            # 예외만 보면 삭제된 곡이 판정 없이 지나간다.
            verdicts[track_id] = "deleted"

    return verdicts, alive


def audit_registry(
    registry: Registry,
    server_index: dict[int, dict],
    exempt_now: bool = True,
    client=None,
) -> AuditResult:
    """등록 기록의 곡을 서버 목록과 맞춰 본다.

    Args:
        registry: `registered.json`
        server_index: `index_server_music` 이 만든 것
        exempt_now: 누락 곡에 표식을 남길지. `False` 면 보고만 한다
            (`--dry-run` 이 쓰기 없이 같은 수치를 보여 주기 위한 것)

    Returns:
        `AuditResult`. **레지스트리를 고치지 않는다** — 반영은 `apply_exemptions`.
    """
    result = AuditResult()
    result.server_total = len(server_index)

    # 곡명으로도 한 번 찾아 둔다. 곡 번호가 바뀌었는지 보려는 것이다.
    by_title: dict[str, list[int]] = {}
    for music_id, row in server_index.items():
        by_title.setdefault(_title_of(row), []).append(music_id)

    tracks = registry.catalog("tracks")
    result.checked = len(tracks)

    for track_id in sorted(tracks):
        entry = tracks[track_id]
        music_id = entry.get("music_id")
        title = str(entry.get("title") or "").strip()
        finding = {
            "track_id": track_id,
            "music_id": music_id,
            "title": title,
        }

        if isinstance(music_id, int) and music_id in server_index:
            if _title_of(server_index[music_id]) == title:
                # 정상. 예전에 붙은 표식이 있으면 곡이 되살아난 것이다.
                if entry.get(EXEMPT_KEY):
                    result.recovered.append(track_id)
                continue
            finding["server_title"] = _title_of(server_index[music_id])
            result.title_mismatch.append(finding)
            continue

        # 곡 번호로 못 찾았다. 곡명이 같은 다른 번호가 있으면 매핑이 낡은 것이다.
        moved = [m for m in by_title.get(title, []) if m != music_id]
        if title and moved:
            finding["server_music_ids"] = sorted(moved)
            result.title_mismatch.append(finding)
            continue

        result.missing.append(finding)
        if not entry.get(EXEMPT_KEY) and exempt_now:
            result.exempt.append(finding)

    # 왜 없는지 곡별로 가른다. **목록에 없던 곡만** 부른다 — 표식이 이미 붙은 곡은
    # 지난 실행에서 판정이 끝났고, 그 판정이 파일에 남아 있다(`_restamp` 참고).
    if client is not None and result.missing:
        fresh = [i for i in result.missing if not tracks[i["track_id"]].get(EXEMPT_KEY)]
        if fresh:
            result.verdicts, result.alive_but_unlisted = classify_server_missing(
                client, tracks, fresh
            )
            result.classified = len(fresh)

            if result.alive_but_unlisted:
                # 목록과 어긋났을 뿐 살아 있다. 누락으로 두면 표식이 붙어 배치가
                # 멀쩡한 곡을 영영 건너뛴다.
                alive_ids = {i["track_id"] for i in result.alive_but_unlisted}
                result.missing = [
                    i for i in result.missing if i["track_id"] not in alive_ids
                ]
                result.exempt = [
                    i for i in result.exempt if i["track_id"] not in alive_ids
                ]

    # 이번 실행 뒤에도 표식이 남아 있을 곡 = (기존 표식 − 되살아난 곡) + 새로 붙일 곡
    recovered = set(result.recovered)
    still_exempt = {
        track_id for track_id, entry in tracks.items() if entry.get(EXEMPT_KEY)
    } - recovered
    result.exempt_total = len(still_exempt) + len({i["track_id"] for i in result.exempt})
    return result


# ─────────────────────────────────────────────────────────────
# 예외 표식
# ─────────────────────────────────────────────────────────────


def is_exempt(registry: Registry, track_id: str) -> bool:
    """이 곡이 서버에서 사라진 것으로 표시돼 있는가."""
    entry = registry.catalog_entry("tracks", track_id)
    return bool(entry and entry.get(EXEMPT_KEY))


def exempt_track_ids(registry: Registry) -> set[str]:
    """표식이 붙은 곡 전부. 다른 단계가 410 을 맞기 전에 걸러 내는 데 쓴다."""
    return {
        track_id
        for track_id, entry in registry.catalog("tracks").items()
        if entry.get(EXEMPT_KEY)
    }


def apply_exemptions(result: AuditResult, registry: Registry) -> tuple[bool, bool]:
    """대조 결과를 등록 기록에 반영한다.

    Returns:
        (표식을 새로 붙였는가, 표식을 지웠는가). 둘 다 아니면 파일을 쓰지 않는다.
    """
    tracks = registry.catalog("tracks")
    stamped, cleared = False, False
    now = datetime.now(_KST).replace(microsecond=0).isoformat()

    for item in result.exempt:
        entry = tracks.get(item["track_id"])
        if not entry:
            continue
        # 왜 없는지에 따라 문구와 다음 사람이 할 일이 다르다. 단건 조회를 안 켰으면
        # 판정이 없으므로 예전 문구를 그대로 쓴다.
        verdict = result.verdict_of(item["track_id"])
        reason, advice = {
            "deleted": (
                "서버에서 삭제됨 (410 Gone)",
                "되살릴지 원본을 지울지는 사람이 정합니다: "
                "SELECT music_id, is_deleted FROM music WHERE music_id=%s",
            ),
            "stale_record": (
                "서버에 그런 곡 번호가 없음 (404) — registered.json 이 낡았습니다",
                "곡 번호를 다시 맞추세요: music-preprocessing repair-track %s",
            ),
        }.get(verdict, (
            "서버 곡 목록에 없음",
            "되살릴지 원본을 지울지는 사람이 정합니다: "
            "SELECT music_id, is_deleted FROM music WHERE music_id=%s",
        ))

        entry[EXEMPT_KEY] = {
            "detected_at": now,
            "music_id": item["music_id"],
            "reason": reason,
        }
        if verdict:
            entry[EXEMPT_KEY]["verdict"] = verdict
        stamped = True
        logger.warning(
            "서버에 없는 곡을 예외로 남깁니다: %s (music_id=%s) — %s. "
            "이 곡은 건너뜁니다. %s",
            item["track_id"],
            item["music_id"],
            reason,
            # 삭제는 곡 번호로(SQL), 노후화는 track_id 로(CLI) 짚는다.
            advice % (item["music_id"] if verdict != "stale_record"
                      else item["track_id"]),
        )

    for track_id in result.recovered:
        entry = tracks.get(track_id)
        if entry and entry.pop(EXEMPT_KEY, None) is not None:
            cleared = True
            logger.info(
                "서버에 다시 나타난 곡의 예외 표식을 지웁니다: %s — 다음 실행부터 정상 동기화합니다",
                track_id,
            )

    return stamped, cleared


def report_findings(result: AuditResult) -> None:
    """사람이 읽을 형태로 남긴다. **누락 곡은 빠짐없이** 찍는다."""
    if result.title_mismatch:
        for item in result.title_mismatch:
            logger.error(
                "곡명 불일치: %s (기록 music_id=%s, 기록 제목=%r%s)",
                item["track_id"],
                item["music_id"],
                item["title"],
                (
                    f", 서버 제목={item['server_title']!r}"
                    if "server_title" in item
                    else f", 서버 곡 번호={item.get('server_music_ids')}"
                ),
            )
        logger.error(
            "곡명이 어긋난 곡이 %d개입니다. registered.json 이 다른 곡을 가리키고 "
            "있으면 분류 등록(PUT)이 엉뚱한 곡의 값을 지웁니다 — 한 건도 쓰지 않고 멈춥니다.",
            len(result.title_mismatch),
        )

    if result.missing:
        logger.warning(
            "서버 곡 목록에 없는 곡 %d개 (확인: 총 %d곡 대조, 서버 %d곡):",
            len(result.missing),
            result.checked,
            result.server_total,
        )
        for item in result.missing:
            verdict = result.verdict_of(item["track_id"])
            logger.warning(
                "  · %s (music_id=%s, %r)%s",
                item["track_id"],
                item["music_id"],
                item["title"],
                # 단건 조회를 켰으면 왜 없는지까지 보여 준다.
                {
                    "deleted": " — 서버에서 삭제됨",
                    "stale_record": " — 서버에 그런 번호 없음(기록이 낡음)",
                }.get(verdict, ""),
            )

    if result.alive_but_unlisted:
        # 목록에 없는데 단건 조회는 살아 있다고 답한 곡. 표식을 붙이지 않았으니
        # 다음 실행이 다시 본다. 지금은 알리기만 한다.
        logger.warning(
            "곡 목록에는 없는데 단건 조회는 살아 있는 곡 %d개 — 목록이 낡았을 수 "
            "있습니다. 표식을 붙이지 않았습니다: %s",
            len(result.alive_but_unlisted),
            ", ".join(i["track_id"] for i in result.alive_but_unlisted),
        )

    if result.recovered:
        logger.info("서버에 다시 나타난 곡 %d개: %s", len(result.recovered), ", ".join(result.recovered))

    if not (result.missing or result.title_mismatch):
        logger.info(
            "서버 대조: %d곡 전부 서버에 있습니다 (서버 %d곡)",
            result.checked,
            result.server_total,
        )


# ─────────────────────────────────────────────────────────────
# 삭제된 곡에 딸린 스트림
# ─────────────────────────────────────────────────────────────


def orphan_streams_of_missing(registry: Registry, result: AuditResult) -> list[dict]:
    """서버에서 사라진 곡에 배치가 붙여 둔 스트림.

    곡이 삭제되면 `GET /api/v1/music/{id}/stream` 이 410 이라 **조회로는 볼 수
    없다.** 그래서 `registered.json` 에 그때 기록한 `stream_id` 를 그대로 보고한다.
    (청킹 기록 `tracks` 가 오디오 경로별로 그 값을 들고 있다.)

    지우지는 않는다. 그 곡이 정말 버려도 되는지 배치는 단정할 수 없다.
    """
    missing = {item["music_id"] for item in result.missing}
    if not missing:
        return []

    # 곡 번호 → track_id. 어느 오디오 파일이 그 곡이었는지 같이 보여 주려는 것.
    track_of_music = {
        entry.get("music_id"): track_id
        for track_id, entry in registry.catalog("tracks").items()
        if entry.get("music_id") in missing
    }

    found: dict[int, dict] = {}
    for audio_path, entry in registry.data.get("tracks", {}).items():
        music_id = entry.get("music_id")
        if music_id not in missing:
            continue
        row = found.setdefault(
            music_id,
            {
                "track_id": track_of_music.get(music_id),
                "music_id": music_id,
                "stream_ids": [],
                "source": [],
            },
        )
        if entry.get("stream_id"):
            row["stream_ids"].append(entry["stream_id"])
        row["source"].append(str(audio_path))

    return [found[music_id] for music_id in sorted(found)]


# ─────────────────────────────────────────────────────────────
# 곡 하나만 고치기 (`music-preprocessing repair-track`)
# ─────────────────────────────────────────────────────────────


def repair_one(
    client,
    registry: Registry,
    track_id: str,
    apply: bool = False,
) -> dict:
    """등록 기록에서 곡 하나만 걷어내거나 다시 맞춘다.

    `registered.json` **전체를 지우지 않는다.** 곡 하나가 서버에서 사라졌다고
    1060곡의 등록 결과를 버리면 전부 다시 만들어야 한다.

    Args:
        client: 서버 클라이언트
        registry: `registered.json`
        track_id: 고칠 곡 (`T000957` 같은 형태)
        apply: False 면 무엇을 할지만 계산한다 (파일을 쓰지 않는다)

    Returns:
        `on_server` · `music_id` · `title` · `stream_ids` · `chunk_entries` ·
        `exempt` · `changed` 를 담은 요약. `changed` 는 실제로 파일을 고쳤는가.

    **원본 오디오·메타데이터 파일은 건드리지 않는다.** 서버 곡을 되살릴지
    주피터의 원본을 지울지는 사람이 정한다.
    """
    entry = registry.catalog_entry("tracks", track_id)
    if entry is None:
        raise ValueError(
            f"등록 기록에 없는 곡입니다: {track_id} — `catalog` 을 먼저 돌렸는지 확인하세요."
        )

    music_id = entry.get("music_id")

    # 청킹 기록에서 이 곡을 가리키는 항목들. 스트림 번호가 거기 남아 있다.
    chunk_keys = [
        key
        for key, chunk in registry.data.get("tracks", {}).items()
        if chunk.get("music_id") == music_id
    ]
    stream_ids = sorted(
        {
            chunk["stream_id"]
            for key in chunk_keys
            for chunk in (registry.data["tracks"][key],)
            if chunk.get("stream_id")
        }
    )

    # 서버에 아직 있는지 **단건 조회로** 본다. 목록(`GET /api/v1/music`)은 삭제분을
    # 걸러서 주므로 "없다" 까지만 알려 주고, 삭제됨과 등록 기록 노후화가 구분되지
    # 않는다. 단건 조회는 410(삭제) 과 404(그런 번호 없음) 로 갈라 준다.
    # 곡 하나를 고치는 자리라 요청 한 번이면 된다.
    row, verdict = None, None
    if music_id:
        try:
            row = client.get_music(music_id)  # 삭제된 곡이면 None
        except MupidApiError as e:
            if e.code == GONE_CODE or e.status == GONE_STATUS:
                verdict = "deleted"
            elif e.status == 404:
                verdict = "stale_record"
            else:
                # 인증 실패·서버 장애. 삭제로 단정하면 멀쩡한 곡을 걷어낸다.
                raise ServerAuditError(
                    f"곡 상태를 가릴 수 없습니다 ({track_id}, music_id={music_id}): {e}"
                ) from e
    on_server = row is not None
    # 삭제된 곡은 `get_music` 이 None 을 준다 — 그 뜻을 판정에 담는다.
    if not on_server and verdict is None:
        verdict = "deleted"

    summary = {
        "track_id": track_id,
        "on_server": on_server,
        "verdict": verdict,
        "music_id": music_id,
        "title": entry.get("title"),
        "stream_ids": stream_ids,
        "chunk_entries": chunk_keys,
        "exempt": bool(entry.get(EXEMPT_KEY)),
        "changed": False,
    }
    if not apply:
        return summary

    if on_server:
        # 곡은 살아 있다. 낡은 값만 다시 맞춘다 (`PATCH` 는 준 필드만 바꾼다).
        client.update_music(music_id, title=entry.get("title"))
        entry[EXEMPT_KEY] and entry.pop(EXEMPT_KEY)
        summary["changed"] = True
    else:
        # 서버에 없다. 등록 기록에서 이 곡만 걷어낸다. 표식도 함께 치운다 —
        # 등록 기록 자체가 없으면 표식이 가리킬 것이 없다.
        registry.catalog("tracks").pop(track_id, None)
        for key in chunk_keys:
            registry.data["tracks"].pop(key, None)
        registry.catalog("lyrics").pop(track_id, None)
        summary["changed"] = True

    registry.save()
    logger.info(
        "곡 복구: %s (music_id=%s, 서버에 %s%s) — 청킹 기록 %d건 정리, 스트림 %s",
        track_id,
        music_id,
        "있음" if on_server else "없음",
        {"deleted": " — 삭제됨", "stale_record": " — 그런 번호 없음(기록이 낡음)"}
            .get(verdict, ""),
        len(chunk_keys),
        stream_ids or "없음",
    )
    return summary
