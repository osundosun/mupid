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
from .audit import EXEMPT_KEY
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
        "deleted_songs": scan_deleted_songs(registry),
        "errors": [],
    }
    if not music_ids:
        logger.info("등록 기록이 비어 있어 훑을 곡이 없습니다.")
        _report_deleted_songs(report)
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
            # 410 Gone = 서버에서 삭제됐다. 404 = 등록 기록이 낡아 그런 곡이 없다.
            # 둘 다 스트림을 물어볼 수 없다는 점은 같지만 원인이 다르다 — 410 이면
            # 사람이 곡을 되살릴지 정해야 하고, 404 면 `registered.json` 이 틀렸다.
            # **410 을 안 잡으면 삭제된 곡이 그냥 오류로 보고된다** (2026-09-18,
            # 그전엔 서버가 404 를 줘서 아래 경고가 났다).
            if e.status in (404, 410):
                logger.warning(
                    "곡이 서버에 없습니다: music_id=%s (%s) — 딸린 스트림이 남아 있어도 "
                    "여기서는 보이지 않습니다",
                    music_id,
                    "삭제됨" if e.status == 410 else "등록 기록에 없는 번호",
                )
                if e.status == 410:
                    # 이 자리에서는 스트림 번호를 모른다(`stream_ids` 는 청킹
                    # 기록에서 찾은 것만 채운다). 곡 단위로만 남긴다.
                    report["deleted_songs"].append(
                        {"music_id": music_id, "stream_ids": [],
                         "reason": "서버에서 삭제됨"})
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

    서버에서 **사라진 곡은 뺀다.** 어차피 404 라 스트림을 볼 수 없고
    (`getStreamByMusicId` 의 `isExists` 검사), 그 곡의 남은 스트림은
    `scan_deleted_songs` 가 `registered.json` 기록으로 따로 보고한다. 여기 두면
    곡마다 헛된 404 호출이 나가고 같은 사실을 두 번 알리게 된다.
    """
    expected: dict[int, dict] = {}

    # 서버에서 사라진 것으로 표시된 곡. 배치 한 바퀴에서 `catalog` 이 남긴 표식이다.
    gone = {
        entry.get("music_id")
        for entry in registry.catalog("tracks").values()
        if entry.get(EXEMPT_KEY)
    }

    for entry in registry.catalog("tracks").values():
        music_id = entry.get("music_id")
        if music_id and music_id not in gone:
            expected[music_id] = {"stream_id": None, "chunk_count": None}

    for entry in registry.data["tracks"].values():
        music_id = entry.get("music_id")
        if not music_id or music_id in gone:
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


def scan_deleted_songs(registry: Registry) -> list[dict]:
    """서버에서 사라진 곡에 배치가 붙여 둔 스트림을 모은다.

    표식(`server_missing`)은 `catalog` 의 서버 대조가 남긴 것이다. 여기서 서버를
    다시 훑지 않는 이유: 배치 한 바퀴에서 `catalog` 이 이미 같은 목록을 읽었고
    그 결과가 파일에 남아 있다. 없는 곡을 찾는 책임은 한 곳(`audit`)에 둔다.
    """
    from .audit import orphan_streams_of_missing, AuditResult

    missing = [
        {"track_id": track_id, "music_id": entry.get("music_id")}
        for track_id, entry in registry.catalog("tracks").items()
        if entry.get("server_missing")
    ]
    if not missing:
        return []
    result = AuditResult()
    result.missing = missing
    return orphan_streams_of_missing(registry, result)


def _report_deleted_songs(report: dict) -> None:
    """서버에서 사라진 곡에 딸린 스트림을 알린다. **지우지는 않는다.**

    이 스트림들은 조회로 볼 수 없다 — 곡이 없으면 `GET /api/v1/music/{id}/stream`
    이 410 이다(`StreamServiceImpl.getStreamByMusicId` 의 `requireAlive` 검사). 그래서
    청킹 기록에 남은 `stream_id` 로만 알 수 있고, 여기서 보고하는 것이 유일한
    단서다. 그냥 두면 청크가 스토리지를 계속 먹는데 어디에도 안 나타난다.

    남은 곡 원본 파일을 지울지 스트림을 지울지는 사람이 정한다 — 배치가
    "내 기록에 없다" 를 "버려도 된다" 로 승격시키지 않는다(`모듈 문서` 참고).
    """
    deleted = report.get("deleted_songs") or []
    if not deleted:
        return
    for row in deleted:
        logger.warning(
            "서버에서 사라진 곡에 스트림이 남아 있습니다: %s (music_id=%s, stream_id=%s). "
            "조회로는 확인할 수 없습니다(곡이 없으면 410). 지우려면 "
            "DELETE /api/v1/stream/{streamId}",
            row.get("track_id") or "-",
            row["music_id"],
            # 두 곳이 이 보고에 모인다. `scan_deleted_songs` 는 청킹 기록에서
            # `stream_ids` 를 찾아 넣고, 조회 중 410 을 만난 자리는 그 자리에서
            # 곡만 안다(`stream_ids` 가 없다). 없는 것을 KeyError 로 터뜨리면
            # 보고하다 배치가 죽는다 — 보고는 배치를 망치지 않는다.
            ", ".join(str(s) for s in row.get("stream_ids") or []) or "기록 없음",
        )
    logger.warning(
        "서버에서 사라진 곡 %d개에 딸린 스트림입니다. **지우지 않았습니다** — "
        "등록 기록에서 그 곡만 걷어내려면 `music-preprocessing repair-track <track_id>`",
        len(deleted),
    )


def _report(report: dict) -> None:
    """마지막에 한 줄로 정리한다. 경고만 하고 종료 코드는 건드리지 않는다."""
    found = sum(
        len(report[key]) for key in ("unknown", "duplicate", "count_mismatch", "short")
    )
    _report_deleted_songs(report)
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
