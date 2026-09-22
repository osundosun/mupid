"""추천 서버를 띄운다. **로컬에서 한 줄로 돌리기 위한 진입점이다.**

    python -m ai_server.tools.serve

`uvicorn` 을 직접 부르는 것과 두 가지가 다르다.

1. **환경변수를 `.env` 에서 읽어 준다.** 산출물 경로·ClickHouse 자격을 매번 export
   하지 않아도 된다.
2. **뜨기 전에 무엇이 없는지 말한다.** 이 저장소가 반복해서 밟은 함정이 "없어도 서비스는
   정상처럼 뜨고 기능만 조용히 꺼지는" 것이다 — `labels_service.parquet` 이 없으면 모든
   사용자가 콜드스타트가 되고, `stage1_weights.npz` 가 없으면 학습 점수가 통째로 빠진다.
   둘 다 오류를 내지 않는다. 그래서 기동 전에 한 번 소리 내어 읽는다.

환경변수를 **import 보다 먼저** 세워야 한다. `app/paths.py` 가 경고하듯 산출물 경로는
모듈 최상단에서 한 번 계산되므로, 늦게 설정하면 서비스는 뜨는데 엉뚱한 곳을 본다.
그래서 이 파일은 `app/` 을 함수 안에서만 import 한다.
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

PKG_ROOT = Path(__file__).resolve().parents[1]          # ai_server/
REPO_ROOT = PKG_ROOT.parent
DEFAULT_ENV = PKG_ROOT / ".env"
DEFAULT_PORT = 8100


def load_env_file(path: Path) -> int:
    """`KEY=VALUE` 를 환경에 올린다. **이미 있는 값은 덮지 않는다.**

    셸에서 준 값이 파일보다 세다 — 한 번만 다르게 띄우고 싶을 때 파일을 고치지 않아도
    되게 하려는 것이다. 의존성을 하나 더 들이지 않으려고 직접 읽는다(`requirements.txt`
    는 실제 import 하는 것만 담는다).
    """
    if not path.exists():
        return 0
    n = 0
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if not key or key in os.environ:
            continue
        # `KEY="값"` 처럼 감싼 경우만 벗긴다. 값 안의 따옴표는 건드리지 않는다.
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        os.environ[key] = value
        n += 1
    return n


def preflight() -> tuple[bool, list[str]]:
    """뜰 수 있는가, 그리고 뜨더라도 무엇이 빠지는가.

    돌려주는 것은 (치명적인 문제가 없는가, 사람이 읽을 줄들).
    """
    from ai_server.app import (catalog_api, clickhouse, index_contract, main,
                               paths, recommender)

    lines: list[str] = []
    fatal = False

    root = paths.artifact_root()
    lines.append(f"산출물  {root}")
    if not paths.data_dir().is_dir():
        lines.append("  ✗ data/proc/mupid 가 없다 — MUPID_AI_ARTIFACT_DIR 을 확인한다")
        return False, lines

    # 판의 계약 표식을 **값을 읽기 전에** 본다. 옛 판(장르·무드를 색인 안에 들고
    # 있던 판)과 새 코드가 섞이면 오류 없이 그 축이 통째로 빈다.
    plate = index_contract.check_plate()
    if plate["ok"]:
        lines.append(f"색인 계약  v{plate['contract']} · {plate['catalog_source']}"
                     + (f"  ({plate['built_at']})" if plate.get("built_at") else ""))
    else:
        fatal = True
        for err in plate["errors"]:
            lines.append(f"  ✗ {err}")
    for warn in plate["warnings"]:
        lines.append(f"  ! {warn}")

    integrity = main.check_integrity()
    if integrity["ok"]:
        rows = integrity["checks"]["rows"]
        lines.append(f"  ✓ 색인 4종 {rows['moments']:,}행 일치 · emb_row 연속")
    else:
        fatal = True
        for err in integrity["errors"]:
            lines.append(f"  ✗ {err}")

    # 없어도 뜨지만 기능이 조용히 꺼지는 것들. 무엇을 잃는지 함께 적는다.
    status = main.artifact_status()
    cost = {
        "labels_service.parquet": "모든 사용자가 콜드스타트가 된다",
        "stage1_weights.npz": "학습 점수가 빠지고 규칙 점수로 내려간다",
        "logistic_flow_stage1.json": "점수 결합기가 없어 순위가 달라진다",
    }
    for name, why in cost.items():
        if not status["files"].get(name, {}).get("exists"):
            lines.append(f"  ! {name} 없음 — {why}")

    # 곡·순간 메타데이터. **없으면 뜨지 못한다** — 장르·무드·보컬·악기 조건이
    # 통째로 빈 채 추천이 나가는 상태를 기동에서 막아 두었기 때문이다.
    if catalog_api.configured():
        try:
            snap = catalog_api.load()
            lines.append(f"카탈로그  {len(snap.tracks):,}곡 {len(snap.sections):,}구간 "
                         f"✓ (MySQL, Spring 내부 API)")
        except Exception as e:
            fatal = True
            lines.append(f"카탈로그  ✗ {type(e).__name__}: {e}")
            lines.append("  ✗ 곡명·장르·무드·보컬·악기가 통째로 빈다 — 뜨지 않는다")
    else:
        fatal = True
        lines.append("카탈로그  ✗ 미설정 "
                     "(MUPID_API_BASE_URL · MUPID_API_REFRESH_TOKEN)")
        lines.append("  ✗ 색인에는 메타데이터가 없다. 이 값 없이는 추천할 수 없다")

    if clickhouse.configured():
        try:
            clickhouse.query("SELECT 1")
            lines.append(f"행동 저장소  {clickhouse._http_base()}  ✓ 연결")
        except Exception as e:
            lines.append(f"행동 저장소  ✗ {type(e).__name__}: {e}")
            lines.append("  ! 개인화 없이 콜드스타트 추천만 나간다")
    else:
        lines.append("행동 저장소  미설정 (MUPID_CLICKHOUSE_URL)")
        lines.append("  ! 개인화 없이 콜드스타트 추천만 나간다")

    if not os.environ.get("MUPID_AI_NLP_URL", "").strip():
        lines.append("자연어 해석기  미설정 — query 원문 검색은 501 "
                     "(Spring 은 query_plan 을 보내므로 정상)")
    if os.environ.get("MUPID_AI_INTERNAL_TOKEN", "").strip():
        lines.append("내부 토큰  설정됨 — Spring 쪽 "
                     "MUPID_RECOMMENDER_INTERNAL_TOKEN 이 같은 값이어야 한다")

    pool = sorted(recommender.POOL_SOURCES)
    lines.append(f"후보 풀  {', '.join(pool)}")
    return not fatal, lines


def main() -> int:
    ap = argparse.ArgumentParser(
        prog="python -m ai_server.tools.serve",
        description="추천 서버를 띄운다. 뜨기 전에 산출물·연결 상태를 알려 준다.")
    ap.add_argument("--artifacts", default=None,
                    help="모델·색인 루트. MUPID_AI_ARTIFACT_DIR 보다 세다")
    ap.add_argument("--host", default="127.0.0.1",
                    help="기본 127.0.0.1. 컨테이너에서는 0.0.0.0 을 준다")
    ap.add_argument("--port", type=int, default=None,
                    help=f"기본 MUPID_AI_PORT 또는 {DEFAULT_PORT}")
    ap.add_argument("--env-file", default=None,
                    help=f"기본 {DEFAULT_ENV}. 없으면 건너뛴다")
    ap.add_argument("--check", action="store_true",
                    help="띄우지 않고 준비 상태만 본다")
    ap.add_argument("--reload", action="store_true",
                    help="코드가 바뀌면 다시 띄운다 (개발용)")
    a = ap.parse_args()

    # ── 환경을 먼저 세운다. app/ import 는 그 뒤여야 한다.
    env_file = Path(a.env_file) if a.env_file else DEFAULT_ENV
    loaded = load_env_file(env_file)
    if a.artifacts:
        os.environ["MUPID_AI_ARTIFACT_DIR"] = str(Path(a.artifacts).resolve())
    port = a.port or int(os.environ.get("MUPID_AI_PORT") or DEFAULT_PORT)

    from ai_server.app.log import log

    if loaded:
        log(f"{env_file} 에서 {loaded}개 읽음")
    elif a.env_file:
        log(f"{env_file} 가 없다 — 환경변수만 쓴다")

    if not os.environ.get("MUPID_AI_ARTIFACT_DIR"):
        log("MUPID_AI_ARTIFACT_DIR 이 비어 있다 — ai_server/artifacts 를 본다")

    ok, lines = preflight()
    for line in lines:
        log(line)
    if not ok:
        log("색인이 성하지 않다. 띄우지 않는다 — 이 상태로 뜨면 엉뚱한 곡을 추천한다.")
        return 1
    if a.check:
        log("준비 상태만 보았다 (--check).")
        return 0

    log(f"http://{a.host}:{port}  ·  /health · /internal/ready")

    import uvicorn

    uvicorn.run("ai_server.app.main:app", host=a.host, port=port,
                reload=a.reload,
                # 워커는 하나다. 카탈로그를 프로세스마다 통째로 올리므로 늘리면
                # 메모리가 배로 든다(임베딩만 402MB).
                workers=1,
                reload_dirs=[str(PKG_ROOT)] if a.reload else None)
    return 0


if __name__ == "__main__":
    # 저장소 루트에서 부르지 않아도 `ai_server` 를 찾을 수 있게 한다.
    if str(REPO_ROOT) not in sys.path:
        sys.path.insert(0, str(REPO_ROOT))
    raise SystemExit(main())
