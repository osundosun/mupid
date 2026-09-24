"""Pinecone 순간 벡터 전체 갱신·대사. **물리 인덱스 하나다.**

    python -m ai_server.tools.pinecone_sync --reload    멈춤 → 전체 갱신 → 검증 → 재개
    python -m ai_server.tools.pinecone_sync --audit     카탈로그 ↔ 인덱스 대사
    python -m ai_server.tools.pinecone_sync --verify    연결·차원·개수만 본다
    python -m ai_server.tools.pinecone_sync --status    지금 멈춰 있는가
    python -m ai_server.tools.pinecone_sync --delete ID [ID ...]
    python -m ai_server.tools.pinecone_sync --resume    정합성을 다시 통과해야 재개

## 무중단 전환을 만들지 않는다

활성 namespace 는 **고정**이다(`MUPID_PINECONE_NAMESPACE`). 분석 배치가 벡터나 추천
풀을 바꾸면 그 자리에서 전체를 갈아 끼운다 — 새 namespace 를 잡고 옮겨 타는 구조를
두지 않는다. 대신 갈아 끼우는 동안 **추천을 멈춘다.**

    ① control 을 UPDATING 으로   → 이 순간부터 서빙이 503
    ② 데이터 namespace 전체 삭제
    ③ 삭제 반영을 유한 재시도로 확인
    ④ 새 μ·snapshot 을 control 에 기록 (상태는 UPDATING 그대로)
    ⑤ 새 u·r 전량 적재
    ⑥ 카탈로그·차원·개수·ID·snapshot 대사
    ⑦ AI 서버가 새 세대를 읽었는지 확인
    ⑧ 그 뒤에만 control 을 READY 로

**어느 단계라도 실패하면 UPDATING 을 유지한다.** 반쯤 적재된 벡터로 추천하지
않기 위한 것이고, 검증을 우회해 재개하는 길은 두지 않는다.

적재본이 같으면 아무것도 하지 않는다 — 바뀐 것이 없는데 지우고 다시 넣으면 그동안의
추천 중단이 순전한 손해다.

언제 도는가는 이 파일이 정하지 않는다 — **원본 임베딩을 실제로 만드는 분석 배치**
(`music_preprocessing/run_batch.sh` 의 `analysis` 성공 직후)가 부른다. AI 서버 배송
색인에는 `moments_emb.npy` 가 없으므로 그쪽에서는 돌 수 없다.

## 무엇을 올리나

    데이터 namespace   id = external_id · vector = (Z-mu)/‖Z-mu‖ · metadata = {r}
    control namespace  id = __center__  · vector = mu · metadata = 세대

**물리 인덱스는 하나다.** 원본 Z 는 따로 저장하지 않고 `Z = mu + r·u` 로 되돌린다.
곡명·장르·무드·보컬·악기·시간의 정본은 MySQL 이고 추천 서버는 Spring 내부 API 로
읽는다 — Pinecone metadata 에 DB 소유값을 넣지 않는다.

## 적재 대상

색인의 추천 풀(`MUPID_POOL`) ∩ Spring 카탈로그. 삭제곡·카탈로그 밖 순간·풀 밖
소스는 올리지 않고, 인덱스에만 남아 있는 것(잔존)은 **갱신 때 함께 지운다.**

종료 코드
    0 정상 · 2 설정·입력 오류 · 3 인덱스 없음·차원 불일치 · 4 대사 불일치
"""
from __future__ import annotations

import argparse
import json
import sys

import numpy as np

from ..app import catalog_api, pinecone_store as ps


def _say(message: str) -> None:
    print(f"[pinecone] {message}", flush=True)


def _fail(message: str, code: int) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(code)


def _catalog():
    """Spring 카탈로그. **여기가 기준이다.**"""
    snap = catalog_api.snapshot()
    if snap is None:
        try:
            snap = catalog_api.load()
        except Exception as e:
            _fail(f"카탈로그를 읽지 못했다 — {type(e).__name__}: {e}", 2)
    return snap


def _pool():
    from ..app import recommender as rc

    if rc.CAT is None:
        # 스냅샷을 **직접 넘긴다.** 서버와 달리 이 도구는 기동에서 카탈로그를 올려
        # 두지 않는다 — 넘기지 않으면 "카탈로그를 읽지 못했다" 로 멈추는데, 실제로는
        # 부르지도 않은 것이다(2026-09-23 최초 적재).
        rc.CAT = rc.Catalog(_catalog())
    return rc.CAT


def _targets():
    """(id 목록, 벡터, 임베딩 버전). **풀 ∩ 카탈로그만.**"""
    cat = _pool()
    snap = _catalog()
    ids = [m for m in cat.pool.moment_id.astype(str) if m in snap.sections]
    skipped = len(cat.pool) - len(ids)
    if not ids:
        _fail("적재 대상이 0건이다 — 색인 풀과 카탈로그가 겹치지 않는다", 2)
    rowmap = cat.mom.set_index(cat.mom.moment_id.astype(str)).emb_row
    Z = np.asarray(cat.Z[rowmap.loc[ids].to_numpy()], dtype=np.float32)
    version = (cat.info.get("embedding_version", "mert-v1-95m")
               if isinstance(cat.info, dict) else "mert-v1-95m")
    return ids, Z, version, skipped


def _snapshot(ids, Z, version) -> str:
    from ..app import recommender as rc

    return ps.snapshot_hash(ids, Z, version, rc.POOL_SOURCES)


def _require_index(dim: int) -> dict:
    try:
        return ps.check_dimension(dim)
    except ps.DimensionMismatch as e:
        _fail(str(e), 3)
    except ps.PineconeError as e:
        _fail(f"인덱스를 확인하지 못했다 — {e}\n"
              f"  콘솔에서 dimension={dim}, metric=dotproduct, type=dense 로 "
              f"먼저 만든다 (ai_server/docs/PINECONE.md)", 3)
    return {}


# ------------------------------------------------------------------ 전체 갱신
def cmd_reload(args) -> int:
    """멈춤 → 전체 갱신 → 검증 → 재개. **실패하면 재개하지 않는다.**"""
    ids, Z, version, skipped = _targets()
    dim = int(Z.shape[1])
    snapshot = _snapshot(ids, Z, version)
    _say(f"대상 {len(ids)}건 (풀·카탈로그 밖 {skipped}건 제외) · {dim}차 "
         f"· 적재본 {snapshot[:12]}…")

    _require_index(dim)
    if args.dry_run:
        _say(f"[모의] namespace {ps.namespace()!r} 에 {len(ids)}건을 갈아 끼운다")
        return 0

    # 같은 적재본이면 아무것도 하지 않는다. 바뀐 것이 없는데 지우고 다시 넣으면
    # 그동안의 추천 중단이 순전한 손해다.
    has_control = True
    try:
        current = ps.center(reload=True)
        if current.state == ps.READY and current.snapshot == snapshot:
            _say("적재본이 같다 — 갱신하지 않는다 (추천도 멈추지 않는다)")
            return 0
    except ps.PineconeError:
        has_control = False                   # control 이 없으면 처음 적재다

    # `--initial-load` 는 **control 이 없을 때만** 통한다. 있으면 최초가 아니고,
    # 그 뒤로도 통하면 "서버 확인을 건너뛰는 상시 우회로" 가 된다 — 급할 때 그 길로
    # 열게 되고, 서버가 옛 μ 를 든 채 READY 가 된다.
    if args.initial_load and has_control:
        _fail("--initial-load 는 control 레코드가 없을 때만 쓴다. 이미 있으므로 "
              "최초 적재가 아니다 — --reload 로 돌린다", 2)
    if args.initial_load:
        _say("[최초 적재] 서버 반영 확인(⑦)만 건너뛴다. 삭제·적재·대사는 그대로 한다")

    # ① 멈춘다. 이 뒤로 서빙은 503 을 돌려준다.
    if has_control:
        ps.set_state(ps.UPDATING)
    else:
        # control 이 아직 없다. 자리를 만들면서 UPDATING 으로 연다.
        ps.save_center(Z.mean(0), version, snapshot, len(ids), state=ps.UPDATING)
    _say("control 을 UPDATING 으로 — 추천이 멈췄다")

    # ② 전체 삭제 → ③ 비워졌는지 확인. 삭제가 비동기인 판이 있어 눈으로 본다.
    ps.delete_all()
    if not _wait_empty(args.empty_tries, args.empty_wait):
        _fail(f"데이터 namespace 가 {args.empty_tries}번 확인에도 비지 않았다 — "
              f"UPDATING 을 유지한 채 멈춘다", 4)
    _say("데이터 namespace 가 비었다")

    # ④ 새 μ·snapshot. 상태는 아직 UPDATING 이다.
    ps.save_center(Z.mean(0), version, snapshot, len(ids), state=ps.UPDATING)

    # ⑤ 전량 적재.
    ps.upsert(ids, Z, center_=ps.center(reload=True))
    _say(f"적재 완료 — {len(ids)}건")

    # ⑥ 대사.
    rep = _audit()
    if not rep["ok"]:
        _say("검증 실패 — UPDATING 을 유지한다")
        _report(rep)
        _fail("전체 갱신 후 대사가 맞지 않는다. 고친 뒤 --reload 를 다시 돌린다", 4)

    # ⑦ AI 서버가 새 세대를 읽었는가. 확인하지 못하면 READY 로 넘어가지 않는다.
    #
    # 최초 적재에서만 이 단계를 건너뛴다. control 이 아직 없던 판에서는 서버가
    # 읽을 세대 자체가 없어 영영 확인되지 않는다 — 배치와 서버가 서로 기다린다.
    # ⑥ 대사는 위에서 이미 통과했다. 건너뛰는 것은 여기 한 단계뿐이다.
    if args.initial_load:
        _say("[최초 적재] 서버 반영 확인을 건너뛴다 — 기동 후 /health 로 직접 본다")
    elif not _wait_server(snapshot, args.server_tries, args.server_wait):
        _fail("AI 서버가 새 세대를 읽었는지 확인하지 못했다 — UPDATING 을 유지한다. "
              "MUPID_AI_SERVER_URL 을 확인한다", 4)

    # ⑧ 재개.
    ps.set_state(ps.READY)
    _say(f"검증 통과 — READY (namespace {ps.namespace()!r}, {len(ids)}건)")
    return 0


def _wait_empty(tries: int, wait: float) -> bool:
    """삭제가 실제로 반영됐는지 본다. 판에 따라 비동기라 바로 비지 않는다."""
    import time                                # noqa: PLC0415

    for attempt in range(max(1, tries)):
        if not ps.list_ids():
            return True
        if attempt + 1 < tries:
            _say(f"  삭제 반영을 기다린다 ({attempt + 1}/{tries})")
            time.sleep(wait)
    return not ps.list_ids()


def _wait_server(snapshot: str, tries: int, wait: float) -> bool:
    """AI 서버가 새 μ 를 메모리에 올렸는지 `/health` 로 확인한다.

    묻는 행위 자체가 서버의 control 재조회를 일으키므로 반영이 앞당겨진다.
    주소가 없으면 **통과시키지 않는다** — 확인하지 않은 것을 확인했다고 하지 않는다.
    """
    import json                                # noqa: PLC0415
    import os                                  # noqa: PLC0415
    import time                                # noqa: PLC0415
    import urllib.request                      # noqa: PLC0415

    base = os.environ.get("MUPID_AI_SERVER_URL", "").strip().rstrip("/")
    if not base:
        _say("  MUPID_AI_SERVER_URL 이 없다 — 서버 반영을 확인할 수 없다")
        return False
    for attempt in range(max(1, tries)):
        try:
            with urllib.request.urlopen(f"{base}/health", timeout=10) as r:
                got = json.loads(r.read().decode() or "{}")
            if (got.get("vector_generation") or {}).get("snapshot") == snapshot:
                _say(f"  AI 서버가 새 세대를 읽었다 ({attempt + 1}번째 확인)")
                return True
        except Exception as e:                 # 아직 안 떴거나 못 닿는다
            _say(f"  서버 확인 실패 ({type(e).__name__}) — 다시 시도한다")
        if attempt + 1 < tries:
            time.sleep(wait)
    return False


def cmd_delete(args) -> int:
    todo = [str(i) for i in args.delete]
    if args.dry_run:
        _say(f"[모의] {len(todo)}건 삭제 예정")
        return 0
    ps.delete(todo)
    _say(f"삭제 {len(todo)}건")
    return 0


def cmd_resume(args) -> int:
    """손으로 재개한다. **정합성을 다시 통과해야만 열린다.**

    검증을 건너뛰는 길은 두지 않는다 — 그런 길이 있으면 결국 그 길로 열게 되고,
    그때 반쯤 적재된 벡터로 추천이 나간다.
    """
    center = ps.center(reload=True)
    if center.state == ps.READY:
        _say("이미 READY 다")
        return 0
    rep = _audit()
    if not rep["ok"]:
        _report(rep)
        _fail("대사가 맞지 않는다 — 고친 뒤 --reload 를 돌린다", 4)
    if not _wait_server(rep["snapshot"], args.server_tries, args.server_wait):
        _fail("AI 서버가 새 세대를 읽었는지 확인하지 못했다 — UPDATING 을 유지한다", 4)
    ps.set_state(ps.READY)
    _say("정합성을 통과해 READY 로 바꿨다")
    return 0


def cmd_status(args) -> int:
    try:
        state = {**ps.center(reload=True).stats(), "namespace": ps.namespace()}
    except ps.PineconeError as e:
        state = {"error": str(e), "namespace": ps.namespace()}
    print(json.dumps(state, ensure_ascii=False, indent=2))
    return 0


# ------------------------------------------------------------------ 대사
def _audit() -> dict:
    ids, Z, version, skipped = _targets()
    snapshot = _snapshot(ids, Z, version)
    want = set(ids)
    have = set(ps.list_ids())

    out = {"namespace": ps.namespace(), "expected": len(want),
           "skipped_outside_pool_or_catalog": skipped, "snapshot": snapshot,
           "present": len(have),
           "missing": sorted(want - have)[:20], "missing_count": len(want - have),
           "stale": sorted(have - want)[:20], "stale_count": len(have - want)}

    # control 도 같은 적재본인가. 데이터가 맞아도 평균이 옛 것이면 질의만 어긋난다.
    try:
        center = ps.center(reload=True)
        center.require_snapshot(snapshot)
        out["center"] = {**center.stats(), "ok": True, "error": None}
    except ps.PineconeError as e:
        out["center"] = {"ok": False, "error": str(e)}

    out["ok"] = (out["missing_count"] == 0 and out["stale_count"] == 0
                 and out["center"]["ok"])
    return out


def _report(rep: dict) -> None:
    _say(f"namespace {rep['namespace']!r} · 기대 {rep['expected']}건 "
         f"· 적재본 {rep['snapshot'][:12]}…")
    _say(f"  있음 {rep['present']}  누락 {rep['missing_count']}  "
         f"잔존 {rep['stale_count']}")
    if rep["missing"]:
        _say(f"    누락 예: {', '.join(rep['missing'][:5])}")
    if rep["stale"]:
        _say(f"    잔존 예: {', '.join(rep['stale'][:5])}")
    if rep["center"]["ok"]:
        _say(f"  평균 {rep['center']['rows']}건 · 적재본 일치")
    else:
        _say(f"  평균 불일치 — {rep['center']['error']}")


def cmd_audit(args) -> int:
    rep = _audit()
    _report(rep)
    if not rep["ok"]:
        _fail("대사 불일치 — --reload 로 전체를 다시 올린다", 4)
    _say("대사 일치")
    return 0


def cmd_verify(args) -> int:
    ids, Z, _version, _skipped = _targets()
    r = _require_index(int(Z.shape[1]))
    _say(f"{r['index']}  {r['dimension']}차  namespace {r['namespace']!r} "
         f"{r['vectors_in_namespace']}건 · control {r['vectors_in_control']}건")
    _ = ids
    try:
        _say(f"control 상태 {ps.center(reload=True).state}")
    except ps.PineconeError as e:
        _say(f"control 을 읽지 못했다 — {e}")
    return 0


# ------------------------------------------------------------------ 진입점
def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        prog="python -m ai_server.tools.pinecone_sync",
        description="Pinecone 순간 벡터 전체 갱신·대사 (인덱스는 만들지 않는다)")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--reload", action="store_true",
                   help="멈춤 → 전체 갱신 → 검증 → 재개")
    g.add_argument("--audit", action="store_true")
    g.add_argument("--verify", action="store_true")
    g.add_argument("--status", action="store_true")
    g.add_argument("--resume", action="store_true")
    g.add_argument("--delete", nargs="+", metavar="ID")
    ap.add_argument("--initial-load", action="store_true",
                    help="최초 1회 적재. 서버 반영 확인만 건너뛴다. "
                         "control 레코드가 이미 있으면 거절한다")
    ap.add_argument("--dry-run", action="store_true",
                    help="무엇을 할지만 말하고 Pinecone 을 바꾸지 않는다")
    ap.add_argument("--empty-tries", type=int, default=10,
                    help="전체 삭제가 반영됐는지 확인할 횟수")
    ap.add_argument("--empty-wait", type=float, default=3.0,
                    help="삭제 확인 사이의 대기(초)")
    ap.add_argument("--server-tries", type=int, default=20,
                    help="AI 서버가 새 세대를 읽었는지 확인할 횟수")
    ap.add_argument("--server-wait", type=float, default=5.0,
                    help="서버 확인 사이의 대기(초)")
    a = ap.parse_args(argv)

    if a.initial_load and not a.reload:
        _fail("--initial-load 는 --reload 와 함께 쓴다", 2)

    if a.status:                                  # 설정 없이도 답할 수 있다
        return cmd_status(a)
    if not ps.configured():
        _fail("MUPID_PINECONE_API_KEY · MUPID_PINECONE_INDEX 가 필요하다", 2)

    try:
        if a.reload:
            return cmd_reload(a)
        if a.audit:
            return cmd_audit(a)
        if a.verify:
            return cmd_verify(a)
        if a.resume:
            return cmd_resume(a)
        return cmd_delete(a)
    except ps.DimensionMismatch as e:
        _fail(str(e), 3)
    except ps.PineconeError as e:
        _fail(f"Pinecone 실패 — {e}", 2)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
