"""이미 등록된 곡에 분석 값(보컬·BPM·언어·스타일)과 구간 에너지를 한 번 채운다.

새 곡은 분석 배치의 ⑥ 등록이 함께 싣는다(`analysis.api.HttpRegistrar`). 이 명령은
그 경로가 생기기 전에 등록된 곡을 위한 것이다 (S15P21A204-423).

    곡 값      PUT /api/v1/music/{id}/analysis     정식 쓰기 경로. 곡마다 한 번
    구간 에너지  UPDATE 문 파일                       사람이 MySQL 에 한 번 적용

**구간 에너지는 API 로 넣지 않는다.** `PUT /music/{id}/section` 은 구간을 지우고 다시
만드는 교체라 `music_section_analysis_id` 가 새로 발급된다 — ClickHouse 구간 표와
그 번호를 참조하는 행이 어긋난다. 그래서 기존 구간은 `external_id` 로 `UPDATE` 한다.

**값은 색인에서 읽는다.** 추천이 지금 쓰는 것과 같은 값이어야 옮긴 뒤에도 추천이
바뀌지 않는다 — BPM 은 `content.parquet`, 언어·스타일·요청 BPM·보컬 요청은
`flow_meta.parquet`, 에너지는 `moments.parquet` 의 `rms` 다.

**flow 곡만 옮긴다.** `registered.json` 에 번호가 있는 곡이 곧 서비스에 올라간 곡이다.
Jamendo 곡은 서비스하지 않는다 — 번호도 없다.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from .analysis.api import analysis_payload


@dataclass
class Plan:
    """무엇을 보낼지. 보내기 전에 센다."""

    bodies: dict[int, dict] = field(default_factory=dict)     # music_id → 본문
    skipped_no_vocal: list[str] = field(default_factory=list)
    unregistered: int = 0
    energy: list[tuple[str, float]] = field(default_factory=list)


def plan(data_dir: Path, music_id_of) -> Plan:
    """색인 → 보낼 것. `music_id_of(track_id)` 가 번호를 준다(없으면 `None`)."""
    import pandas as pd

    data_dir = Path(data_dir)
    for name in ("flow_meta.parquet", "content.parquet", "moments.parquet"):
        if not (data_dir / name).exists():
            raise SystemExit(f"색인에 {name} 이 없다: {data_dir} — 전체 색인을 가리킨다")
    meta = pd.read_parquet(data_dir / "flow_meta.parquet").drop_duplicates("track_id")
    content = pd.read_parquet(data_dir / "content.parquet").drop_duplicates("track_id")
    by_content = content.set_index(content.track_id.astype(str)).to_dict("index")

    out = Plan()
    known: set[str] = set()
    for row in meta.to_dict("records"):
        tid = str(row["track_id"])
        mid = music_id_of(tid)
        if mid is None:
            out.unregistered += 1
            continue
        body = analysis_payload(row, by_content.get(tid, {}))
        if body is None:
            out.skipped_no_vocal.append(tid)
            continue
        out.bodies[int(mid)] = body
        known.add(tid)

    mom = pd.read_parquet(data_dir / "moments.parquet", columns=["moment_id", "track_id", "rms"])
    mom = mom[mom.track_id.astype(str).isin(known)]
    for mid, rms in zip(mom.moment_id.astype(str), mom.rms):
        try:
            value = float(rms)
        except (TypeError, ValueError):
            continue
        if value == value and 0.0 <= value <= 1.0:
            out.energy.append((mid, round(value, 6)))
    return out


def energy_sql(rows: list[tuple[str, float]]) -> str:
    """구간 에너지 UPDATE 문. **`external_id` 로만 찾는다** — 번호는 바뀔 수 있다.

    값은 숫자로 포맷하고 ID 는 따옴표를 이스케이프한다. 입력이 색인 파일이라 외부
    입력은 아니지만, 곡 제목이 ID 에 들어가는 출처도 있다.
    """
    lines = ["-- 구간 에너지 백필 (S15P21A204-423) — analysis_values 가 만들었다.",
             "-- external_id 로 찾는다. 없는 구간은 0행 갱신으로 조용히 지나간다.",
             "START TRANSACTION;"]
    for uid, value in rows:
        safe = uid.replace("\\", "\\\\").replace("'", "''")
        lines.append(f"UPDATE `music_section_analysis` SET `energy` = {value:.6f} "
                     f"WHERE `external_id` = '{safe}';")
    lines += ["COMMIT;",
              "-- 확인: SELECT COUNT(*) FROM music_section_analysis WHERE energy IS NOT NULL;"]
    return "\n".join(lines) + "\n"


def send(client, bodies: dict[int, dict]) -> dict:
    """곡마다 `PUT /analysis`. 하나가 실패해도 나머지는 계속 보내고 센다."""
    sent, failed = 0, []
    for mid, body in sorted(bodies.items()):
        try:
            client.replace_analysis(mid, body)
            sent += 1
        except Exception as e:                   # 곡 하나 때문에 전체를 멈추지 않는다
            failed.append({"music_id": mid, "error": f"{type(e).__name__}: {e}"[:300]})
    return {"sent": sent, "failed": failed}
