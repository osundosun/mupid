"""색인이 **어떤 계약으로 구워졌는지** 확인한다.

색인에서 곡명·장르·무드·보컬·악기·순간 메타데이터를 걷어내고 그 값을 Spring 내부
카탈로그 API 에서 읽게 되면서, 판과 코드 사이에 **버전이 생겼다.**

    contract 1   장르·무드·보컬이 `flow_meta.parquet` 안에 있다 (옛 판)
    contract 2   그 값들은 MySQL 이 정본이고 색인에는 벡터와 비-DB 축만 있다

이 둘이 섞이면 **오류가 나지 않는다.** 새 코드는 `flow_meta.parquet` 을 읽지 않으므로
옛 판을 올리면 장르·무드·보컬·악기 축이 통째로 빈 채 추천이 돌고, 응답은
"조건에 맞는 곡이 없다" 로 보인다 — 이 저장소가 색인 불일치로 두 번 겪은 실패와 같은
형태다. 그래서 **표식을 보고 거절한다.**

되돌릴 때도 같다. 옛 판으로 롤백하면 이 검사가 `/internal/ready` 를 막는다. 막히는
것이 맞다 — 그 상태로 뜨면 장르 없는 추천이 조용히 나간다. 판을 되돌릴 때는 코드도
함께 되돌린다.

## 카탈로그와의 대조

표식만으로는 "이 판이 **이 카탈로그**와 짝인가" 를 알 수 없다. 그래서 풀의
`moment_id`(= `external_id`) 가 카탈로그에 얼마나 있는지를 함께 본다.

  · 0% — 판과 카탈로그가 아예 다른 세계다. 기동을 막는다.
  · 100% 미만 — 지워진 곡이 있으면 정상이다. 수를 남기고 계속한다.
  · `MUPID_AI_CATALOG_MIN_COVERAGE` 아래 — 판이 잘못 올라갔다고 본다(기본 0.5).
    합법적인 삭제가 한 번에 절반을 넘는 일은 없다.
"""
from __future__ import annotations

import json
import os
from typing import Optional

from . import paths

CONTRACT = 2
SOURCE = "spring-internal-v1"
DEFAULT_MIN_COVERAGE = 0.5

# 새 판에 있어서는 **안 되는** 파일. 걷어낸 사본이 그대로 실려 오면, 다음 사람이
# "있으니까 읽어도 되겠지" 로 되돌린다. 있으면 알린다(기동은 막지 않는다 — 파일이
# 있다는 것과 그것을 읽는다는 것은 다르고, 읽지 않는다는 보장은 코드가 한다).
RETIRED_FILES = ("flow_meta.parquet", "content.parquet")


def min_coverage() -> float:
    raw = os.environ.get("MUPID_AI_CATALOG_MIN_COVERAGE", "").strip()
    try:
        return min(1.0, max(0.0, float(raw))) if raw else DEFAULT_MIN_COVERAGE
    except ValueError:
        return DEFAULT_MIN_COVERAGE


def read() -> dict:
    """`index_meta.json`. 없거나 깨졌으면 빈 dict — 판정은 `check_plate` 가 한다."""
    path = paths.index_meta_path()
    if not path.exists():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}
    return data if isinstance(data, dict) else {}


def check_plate() -> dict:
    """판의 표식만 본다. 카탈로그는 아직 보지 않는다."""
    meta = read()
    errors: list[str] = []
    warnings: list[str] = []

    contract = meta.get("index_contract")
    if not meta:
        errors.append(
            f"{paths.index_meta_path().name} 이 없다 — 장르·무드·보컬을 색인 안에 "
            f"들고 있던 옛 판으로 보인다. 그 판으로는 해당 축이 통째로 빈 채 추천이 "
            f"나간다. `python -m ai_server.tools.slice_index` 로 다시 구운 판을 올린다")
    elif not isinstance(contract, int) or contract < CONTRACT:
        errors.append(
            f"색인 계약이 {contract!r} 인데 이 코드는 {CONTRACT} 를 요구한다 — "
            f"판과 코드를 함께 올린다")
    elif contract > CONTRACT:
        errors.append(
            f"색인 계약이 {contract} 로 코드({CONTRACT})보다 앞선다 — "
            f"AI 이미지가 낡았다")

    source = meta.get("catalog_source")
    if meta and source != SOURCE:
        errors.append(f"catalog_source 가 {source!r} 다 — 기대값은 {SOURCE!r}")

    data = paths.data_dir()
    leftovers = [n for n in RETIRED_FILES if (data / n).exists()]
    if leftovers:
        warnings.append(
            f"걷어낸 파일이 판에 남아 있다(읽지 않는다): {', '.join(leftovers)}")

    if not paths.track_features_path().exists():
        errors.append(
            f"{paths.track_features_path().name} 이 없다 — BPM·언어·스타일 축이 "
            f"통째로 빈다")

    return {"ok": not errors, "contract": contract, "catalog_source": source,
            "errors": errors, "warnings": warnings,
            "built_at": meta.get("built_at"), "rows": meta.get("rows")}


def check_catalog(pool_moment_ids, snapshot) -> dict:
    """풀의 구간이 카탈로그에 얼마나 있는지.

    `snapshot` 이 None 이면 아직 못 읽은 것이다 — 준비되지 않았다고만 말하고
    비율을 지어내지 않는다.
    """
    total = len(pool_moment_ids)
    if snapshot is None:
        return {"ok": False, "reason": "카탈로그를 아직 읽지 못했다",
                "moments_total": total, "moments_matched": 0, "coverage": 0.0,
                "min_coverage": min_coverage(), "sample_unmatched": []}

    sections = snapshot.sections
    unmatched = [str(m) for m in pool_moment_ids if str(m) not in sections]
    matched = total - len(unmatched)
    coverage = (matched / total) if total else 0.0
    floor = min_coverage()

    reason = None
    if total == 0:
        reason = "추천 풀이 비어 있다"
    elif matched == 0:
        reason = ("색인의 구간이 카탈로그에 하나도 없다 — 판과 카탈로그가 다른 "
                  "세계다(external_id 규약이나 배포 대상을 확인한다)")
    elif coverage < floor:
        reason = (f"색인 구간의 {coverage:.1%} 만 카탈로그에 있다 "
                  f"(기준 {floor:.0%}) — 판이 잘못 올라갔을 가능성이 높다")

    return {"ok": reason is None, "reason": reason,
            "moments_total": total, "moments_matched": matched,
            "moments_unmatched": len(unmatched),
            "coverage": round(coverage, 4), "min_coverage": floor,
            "sample_unmatched": unmatched[:5]}


def build_meta(rows: int, tracks: int, built_at: str,
               extra: Optional[dict] = None) -> dict:
    """`slice_index` 가 새 판에 쓰는 표식."""
    meta = {
        "index_contract": CONTRACT,
        "catalog_source": SOURCE,
        "built_at": built_at,
        "rows": int(rows),
        "tracks": int(tracks),
        "note": ("곡명·장르·무드·보컬·악기·순간 메타데이터는 이 판에 없다. "
                 "MySQL 이 정본이고 AI 는 Spring 내부 카탈로그 API 로 읽는다."),
    }
    if extra:
        meta.update(extra)
    return meta
