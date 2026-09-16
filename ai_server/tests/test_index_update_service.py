"""색인 교체가 **서비스에 어떻게 보이는가** — 기동·행 수·순위 불변.

`test_index_update.py` 가 파일을 보는 쪽이라면, 여기는 그 판으로 실제 앱을 띄워 본다.
서버는 기동 때 `Catalog` 으로 색인을 통째로 올리므로, 교체 효과는 **새 프로세스**에서만
드러난다. 그래서 하위 프로세스로 띄운다.

가장 중요한 것은 **`rank_order` 불변**이다. 신곡이 끼어들어 목록이 바뀌는 것은 정상이지만,
**기존 곡끼리의 상대 순서**는 그대로여야 한다. 그렇지 않다면 append 가 기존 행을
건드렸다는 뜻이고, 그 상태로도 서비스는 정상 기동한다.
"""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "music_preprocessing" / "src"))

from ai_server.app import paths                                      # noqa: E402

ix = pytest.importorskip("music_preprocessing.analysis.indexer",
                         reason="analysis 선택 의존성이 없다")
pd = pytest.importorskip("pandas")
np = pytest.importorskip("numpy")

from test_index_update import NEEDED, SEED_PREFIX, make_seed          # noqa: E402

D = paths.data_dir()
MISSING = [n for n in NEEDED if not (D / n).exists()]
pytestmark = pytest.mark.skipif(bool(MISSING), reason=f"산출물이 없다: {MISSING}")

USER = "internal:4200000913"          # 예약 대역. 사람 계정과 겹치지 않는다


def _probe(artifact_root: Path) -> dict:
    """새 프로세스로 앱을 띄워 행 수와 추천 순서를 받는다."""
    script = """
import json, os
from fastapi.testclient import TestClient
from ai_server.app.main import app
from ai_server.app import recommender

out = {}
client = TestClient(app, raise_server_exceptions=False)
with client:
    h = client.get("/health")
    out["health_status"] = h.status_code
    body = h.json()
    # /health 는 check_integrity() 의 checks 를 한 단계 펴서 싣는다.
    out["integrity"] = body.get("integrity", {}).get("rows")
    out["catalog"] = body.get("catalog")
    try:
        # explore=False. 탐색 슬롯은 **분 단위 시드**로 마지막 한 칸을 바꿔치기하므로
        # (recommender: default_rng(int(time.time()) // 60)) 두 번의 조회가 분 경계를
        # 넘으면 순서가 달라진다. 여기서 보려는 것은 append·롤백의 안정성이다.
        r = recommender.recommend("%s", 20, set(), False, None, None, None)
        out["order"] = [str(x.get("track_id")) for x in r.get("results", [])]
        out["query_source"] = r.get("diagnostics", {}).get("query_source")
    except Exception as e:
        out["order_error"] = f"{type(e).__name__}: {e}"
print("PROBE " + json.dumps(out, ensure_ascii=False))
""" % USER
    env = dict(**{k: v for k, v in __import__("os").environ.items()})
    env["MUPID_AI_ARTIFACT_DIR"] = str(artifact_root)
    env["PYTHONIOENCODING"] = "utf-8"
    p = subprocess.run([sys.executable, "-c", script], capture_output=True, text=True,
                       cwd=str(REPO_ROOT), env=env, encoding="utf-8")
    for line in (p.stdout or "").splitlines():
        if line.startswith("PROBE "):
            return json.loads(line[6:])
    pytest.skip(f"앱을 띄우지 못했다: {(p.stderr or '')[-400:]}")


@pytest.fixture(scope="module")
def sandbox(tmp_path_factory):
    """산출물 사본. 진짜 디렉터리는 건드리지 않는다."""
    root = tmp_path_factory.mktemp("svc")
    data = root / "data" / "proc" / "mupid"
    data.mkdir(parents=True)
    for p in D.iterdir():
        if p.is_file():
            shutil.copy2(p, data / p.name)
    return root, data


def test_health_reports_new_row_count_and_order_is_stable(sandbox):
    root, data = sandbox

    before = _probe(root)
    if before.get("order_error"):
        pytest.skip(f"교체 전 추천을 받지 못했다: {before['order_error']}")
    rows_before = (before.get("integrity") or {}).get("moments")
    print(f"\n교체 전  /health {before['health_status']}  moments {rows_before}")
    print(f"         상위 {len(before['order'])}곡  query_source={before.get('query_source')}")

    rep = ix.stage(make_seed(data, 2), data)
    assert rep.ok, f"무결성 실패: {rep.integrity.get('errors')}"
    ix.swap(data, rep.new_dir, keep=3)

    after = _probe(root)
    rows_after = (after.get("integrity") or {}).get("moments")
    print(f"교체 후  /health {after['health_status']}  moments {rows_after}")

    assert rows_after == rows_before + rep.added_sections, (
        f"/health 행 수가 {rows_before} → {rows_after} "
        f"(기대 +{rep.added_sections})")

    # ── rank_order 불변: 신곡을 뺀 뒤 기존 곡끼리의 상대 순서가 같아야 한다.
    seed_free = [t for t in after["order"] if not t.startswith(SEED_PREFIX)]
    common = [t for t in before["order"] if t in set(seed_free)]
    got = [t for t in seed_free if t in set(common)]
    print(f"기존 곡 상대 순서  비교 {len(common)}곡")
    assert got == common, (
        f"기존 곡끼리의 순서가 바뀌었다\n  전 {common}\n  후 {got}")


def test_rollback_restores_previous_response(sandbox):
    """되돌리면 교체 전과 같은 응답이 나오는가."""
    root, data = sandbox
    before = _probe(root)
    if before.get("order_error"):
        pytest.skip("추천을 받지 못했다")

    rep = ix.stage(make_seed(data, 1), data)
    assert rep.ok
    res = ix.swap(data, rep.new_dir, keep=3)
    ix.rollback(data, Path(res["backup"]))

    after = _probe(root)
    assert after["order"] == before["order"], "롤백 뒤 추천이 달라졌다"
    assert (after.get("integrity") or {}).get("moments") == \
           (before.get("integrity") or {}).get("moments")
    print(f"\n롤백 확인 — 상위 {len(after['order'])}곡 동일")
