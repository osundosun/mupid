"""서버에 남은 쓰이지 않는 스트림·청크를 찾아 경고한다. **지우지 않는다.**

배치가 청크를 올리다 죽거나, 레지스트리를 다시 만들었거나, 누가 손으로 스트림을
만들었으면 아무도 재생하지 않는 청크가 서버에 남는다. 곡 하나가 수십 개 청크라
쌓이면 스토리지를 꽤 먹는다.

**지우지는 않는다.** 배치가 판단할 수 있는 것은 "내 기록에 없다" 까지이고, 그것이
곧 "버려도 된다" 는 아니다. 사람이 보고 지운다 —
`DELETE /api/v1/stream/{streamId}` 가 그 스트림의 청크까지 함께 치운다.

## 무엇을 볼 수 있는가

배치는 HTTP 로만 서버를 본다. 전체 스트림을 훑는 엔드포인트가 없어서, **배치가
아는 곡**(`registered.json`)을 하나씩 물어보는 수밖에 없다.

| 찾는 것 | 어떻게 |
| --- | --- |
| 배치가 모르는 스트림 | 레지스트리가 적어 둔 `stream_id` 가 아닌 것 |
| 겹치는 스트림 | 한 곡에 같은 코덱·비트레이트가 둘 이상. 화면은 하나만 고른다 |
| 청크 수가 안 맞는 스트림 | 서버가 선언한 `chunk_count` ≠ 배치가 만든 청크 수 |
| 모자란 스트림 | 실제 청크 행 수 < 선언한 `chunk_count` (`--verify-chunks`) |

## 못 보는 것

**`PENDING` · `IN_PROGRESS` · `FAILED` 스트림은 보이지 않는다.**
`GET /api/v1/music/{musicId}/stream` 이 `DONE` 만 걸러 주기 때문이다
(`StreamServiceImpl.getStreamByMusicId`).

하필 가장 흔한 찌꺼기가 이쪽이다 — 청크를 올리다 프로세스가 죽으면 스트림이
`PENDING` 인 채로 남는다. 배치가 정상적으로 실패하면 스스로 치우지만
(`Registrar._drop_stream`), 강제 종료·정전에는 손쓸 수 없다.

이걸 보려면 서버에 상태 필터가 필요하다. 지금은 **찾지 못한다는 사실만 로그에
남긴다** — 조용히 넘어가면 "경고가 없으니 깨끗하다" 고 잘못 읽게 된다.
"""

from __future__ import annotations

import logging
from collections import defaultdict

from .api_client import MupidApiError, MupidClient
from .catalog import ProgressReporter
from .registrar import Registry

logger = logging.getLogger(__name__)


def scan_streams(
    client: MupidClient,
    registry: Registry,
    verify_chunks: bool = False,
    progress: ProgressReporter | None = None,
) -> dict:
    """배치가 아는 곡들의 스트림을 훑어 쓰이지 않는 것을 찾는다.

    Args:
        client: API 클라이언트
        registry: `registered.json`
        verify_chunks: 켜면 성한 스트림까지 청크 행 수를 대조한다.
            곡 수만큼 호출이 더 나간다
        progress: 진행률 보고자

    Returns:
        찾은 것들의 요약. **아무것도 지우지 않는다.**
    """
    progress = progress or ProgressReporter()
    expected = _expected_streams(registry)
    music_ids = sorted(expected)

    report = {
        "music_checked": 0,
        "streams_seen": 0,
        "unknown": [],
        "duplicate": [],
        "count_mismatch": [],
        "short": [],
        "errors": [],
    }
    if not music_ids:
        logger.info("등록 기록이 비어 있어 훑을 곡이 없습니다.")
        return report

    logger.info("스트림 점검 시작: 곡 %d개", len(music_ids))
    progress.phase("스트림 점검", len(music_ids))

    for music_id in music_ids:
        try:
            streams = client.get_streams(music_id)
            report["music_checked"] += 1
            report["streams_seen"] += len(streams)
            _inspect(client, music_id, streams, expected[music_id], verify_chunks, report)
        except MupidApiError as e:
            if e.status == 404:
                # 곡이 서버에서 지워졌다. 남은 스트림이 있는지도 물어볼 수 없다.
                logger.warning(
                    "곡이 서버에 없습니다: music_id=%s — 딸린 스트림이 남아 있어도 "
                    "여기서는 보이지 않습니다",
                    music_id,
                )
                continue
            report["errors"].append({"key": f"music_id={music_id}", "error": str(e)})
        except Exception as e:  # noqa: BLE001 — 점검이 배치를 망치면 안 된다
            report["errors"].append({"key": f"music_id={music_id}", "error": str(e)})
        finally:
            progress.advance("스트림 점검")

    _report(report)
    return report


# ─────────────────────────────────────────────────────────────
# 판정
# ─────────────────────────────────────────────────────────────


def _expected_streams(registry: Registry) -> dict[int, dict]:
    """배치가 아는 곡 → 그 곡에 있어야 할 스트림.

    청킹 기록(`tracks`)에 `stream_id` 가 있고, 카탈로그 기록
    (`catalog.tracks`)에는 곡만 있고 스트림은 없다. 후자는 "스트림이 하나도
    없어야 정상" 인 곡이라 같이 넣는다 — 거기 스트림이 있으면 배치가 만든 것이
    아니다.
    """
    expected: dict[int, dict] = {}

    for entry in registry.catalog("tracks").values():
        music_id = entry.get("music_id")
        if music_id:
            expected[music_id] = {"stream_id": None, "chunk_count": None}

    for entry in registry.data["tracks"].values():
        music_id = entry.get("music_id")
        if not music_id:
            continue
        expected[music_id] = {
            "stream_id": entry.get("stream_id"),
            "chunk_count": entry.get("chunk_count"),
        }

    return expected


def _inspect(
    client: MupidClient,
    music_id: int,
    streams: list[dict],
    expected: dict,
    verify_chunks: bool,
    report: dict,
) -> None:
    """곡 하나의 스트림 목록을 판정한다."""
    known_id = expected.get("stream_id")

    # ── 같은 코덱·비트레이트가 둘 이상이면 하나만 재생된다 ──
    by_format: dict[tuple, list[dict]] = defaultdict(list)
    for stream in streams:
        by_format[(stream.get("codec"), stream.get("bitrate_kbps"))].append(stream)

    for (codec, bitrate), group in by_format.items():
        if len(group) < 2:
            continue
        extras = [s["stream_id"] for s in group if s["stream_id"] != known_id]
        finding = {
            "music_id": music_id,
            "codec": codec,
            "bitrate_kbps": bitrate,
            "stream_ids": [s["stream_id"] for s in group],
            "unused_stream_ids": extras,
        }
        report["duplicate"].append(finding)
        logger.warning(
            "겹치는 스트림: music_id=%s %s %skbps 가 %d개 (stream_id=%s). "
            "화면은 하나만 고르므로 나머지 청크는 재생되지 않습니다",
            music_id,
            codec,
            bitrate,
            len(group),
            ", ".join(str(x) for x in finding["stream_ids"]),
        )

    for stream in streams:
        stream_id = stream["stream_id"]
        declared = stream.get("chunk_count")

        if stream_id != known_id:
            report["unknown"].append(
                {
                    "music_id": music_id,
                    "stream_id": stream_id,
                    "codec": stream.get("codec"),
                    "bitrate_kbps": stream.get("bitrate_kbps"),
                    "chunk_count": declared,
                    "expected_stream_id": known_id,
                }
            )
            logger.warning(
                "배치가 모르는 스트림: music_id=%s stream_id=%s (%s %skbps, 청크 %s개). "
                "기록에 있는 것은 %s 입니다. 지우려면 DELETE /api/v1/stream/%s",
                music_id,
                stream_id,
                stream.get("codec"),
                stream.get("bitrate_kbps"),
                declared,
                known_id if known_id else "없음",
                stream_id,
            )

        elif expected.get("chunk_count") not in (None, declared):
            report["count_mismatch"].append(
                {
                    "music_id": music_id,
                    "stream_id": stream_id,
                    "declared": declared,
                    "expected": expected["chunk_count"],
                }
            )
            logger.warning(
                "청크 수가 기록과 다릅니다: music_id=%s stream_id=%s "
                "(서버 %s개 / 배치 기록 %s개)",
                music_id,
                stream_id,
                declared,
                expected["chunk_count"],
            )

        # 수상한 스트림은 실제 청크 행까지 세어 본다. 성한 것까지 세려면
        # `--verify-chunks` 를 켠다 (곡 수만큼 호출이 더 나간다).
        suspicious = stream_id != known_id
        if (verify_chunks or suspicious) and declared is not None:
            _verify_chunks(client, music_id, stream_id, declared, report)


def _verify_chunks(
    client: MupidClient, music_id: int, stream_id: int, declared: int, report: dict
) -> None:
    """선언한 청크 수만큼 실제로 있는지 센다."""
    try:
        actual = len(client.get_chunks(stream_id))
    except MupidApiError as e:
        report["errors"].append({"key": f"stream_id={stream_id}", "error": str(e)})
        return

    if actual >= declared:
        return
    report["short"].append(
        {
            "music_id": music_id,
            "stream_id": stream_id,
            "declared": declared,
            "actual": actual,
        }
    )
    logger.warning(
        "청크가 모자란 스트림: music_id=%s stream_id=%s (선언 %d개 / 실제 %d개). "
        "재생이 도중에 끊깁니다",
        music_id,
        stream_id,
        declared,
        actual,
    )


def _report(report: dict) -> None:
    """마지막에 한 줄로 정리한다. 경고만 하고 종료 코드는 건드리지 않는다."""
    found = sum(
        len(report[key]) for key in ("unknown", "duplicate", "count_mismatch", "short")
    )
    if found:
        logger.warning(
            "스트림 점검: 곡 %d개 · 스트림 %d개를 보고 %d건을 찾았습니다 "
            "(모르는 스트림 %d · 겹침 %d · 청크 수 불일치 %d · 모자람 %d). "
            "**지우지 않았습니다** — 확인 후 DELETE /api/v1/stream/{streamId} 로 치우세요",
            report["music_checked"],
            report["streams_seen"],
            found,
            len(report["unknown"]),
            len(report["duplicate"]),
            len(report["count_mismatch"]),
            len(report["short"]),
        )
    else:
        logger.info(
            "스트림 점검: 곡 %d개 · 스트림 %d개 — 쓰이지 않는 것을 찾지 못했습니다",
            report["music_checked"],
            report["streams_seen"],
        )

    # 못 보는 것이 있다는 사실을 매번 남긴다. 조용하면 "깨끗하다" 로 잘못 읽는다.
    logger.warning(
        "점검 범위: 완료(DONE)된 스트림만 봅니다. 청크를 올리다 죽어 PENDING 으로 "
        "남은 스트림은 API 로 보이지 않아 여기 잡히지 않습니다"
    )
