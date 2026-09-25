"""온보딩 씨앗 표를 `onboarding_seeds.npz` 로 내보낸다 (S15P21A204-452, 설계 §6.3).

씨앗은 "조건에 맞는 곡들의 구간 종류별 평균 방향" 이다. 조건 조합은 셀 수 없이 많지만
평균은 합으로 쪼개지므로, **곡 × 구간 종류마다 중심화 벡터의 합과 개수**만 있으면 어떤
조건의 씨앗이든 더해서 만든다. 서빙은 지금 이 합을 요청 사본마다 처음 필요할 때 세어
캐시한다(`recommender.seed_sums`). 이 도구는 같은 함수로 색인 풀 전체의 표를 만들어
모델 묶음에 싣는 자리다 — 묶음을 읽어 서빙에 쓰는 것은 3단계(모델 묶음 분리)에서 한다.

    MUPID_AI_ARTIFACT_DIR=/경로/artifacts \\
      python -m ai_server.tools.export_onboarding_seeds --catalog-json body.json --out seeds.npz

`--catalog-json` 은 Spring 이 추천 요청에 싣는 `catalog` 본문(구간 종류·시각의 정본은
MySQL)이다. 주지 않으면 ClickHouse `v_track_sections_current` 에서 읽는다(배치와 같은 길).

npz 안:
    track_id, section_label   (M,)   곡 × 구간 종류
    count                     (M,)   추천에 쓰는 구간 수 (MIN_MOMENT_SEC 이상)
    rep_moment_id             (M,)   그 묶음의 첫 구간 (풀 차례)
    first_row                 (M,)   그 구간의 풀 행 번호 (씨앗 차례를 정한다)
    sum_u                     (M, D) 중심화 방향 `(z-μ)/‖z-μ‖` 의 합
    mu                        (D,)   중심화 평균 — 서빙의 μ 와 같아야 쓸 수 있다
    snapshot, embedding_version, min_moment_sec
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np


def build_table(rc) -> dict:
    """지금 요청 사본(`rc.current()`)의 풀 전체로 씨앗 표를 만든다. 시험도 이것을 부른다."""
    idx = rc.pool_index()
    sums = rc.seed_sums(idx.track_order)
    rows = [(t, lab, v) for t in idx.track_order for lab, v in sums[t].items()]
    rows.sort(key=lambda x: x[2][2])
    dim = rc._vector_dim()
    store = rc.vector_store()
    c = rc.current()
    return {
        "track_id": np.asarray([t for t, _, _ in rows], dtype=str),
        "section_label": np.asarray([lab for _, lab, _ in rows], dtype=str),
        "count": np.asarray([v[1] for _, _, v in rows], dtype=np.int64),
        "rep_moment_id": np.asarray([v[3] for _, _, v in rows], dtype=str),
        "first_row": np.asarray([v[2] for _, _, v in rows], dtype=np.int64),
        "sum_u": (np.asarray([v[0] for _, _, v in rows], dtype=np.float32)
                  if rows else np.zeros((0, dim), np.float32)),
        "mu": np.asarray(store.mu, dtype=np.float32),
        "snapshot": np.asarray(store.snapshot()),
        "embedding_version": np.asarray(
            c.info.get("embedding_version", "mert-v1-95m")
            if isinstance(c.info, dict) else "mert-v1-95m"),
        "min_moment_sec": np.asarray(float(rc.MIN_MOMENT_SEC)),
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--catalog-json", default=None,
                    help="Spring catalog 본문 JSON. 없으면 ClickHouse 구간 표")
    ap.add_argument("--out", default="onboarding_seeds.npz")
    a = ap.parse_args()

    from ai_server.app import catalog_body
    from ai_server.app import recommender as rc

    if a.catalog_json:
        snap = catalog_body.from_body(
            json.loads(Path(a.catalog_json).read_text(encoding="utf-8")))
    else:
        from ai_server.app import track_sections
        snap = track_sections.load()
    view = rc.Catalog().with_catalog(snap)
    if not len(view.pool):
        print("후보 풀이 비었다 — 카탈로그와 색인의 external_id 가 맞는지 본다", file=sys.stderr)
        return 2
    with rc.using(view):
        table = build_table(rc)
    np.savez(a.out, **table)
    print(f"{a.out}: 곡×구간 종류 {len(table['count'])}묶음 · 구간 {int(table['count'].sum())}개"
          f" · 차원 {table['mu'].shape[0]} · snapshot {str(table['snapshot'])[:12]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
