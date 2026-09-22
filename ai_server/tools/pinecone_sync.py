"""Pinecone 순간 벡터 전체 갱신·대사. **두 인덱스를 함께 다룬다.**

    python -m ai_server.tools.pinecone_sync --reload    멈춤 → 전체 갱신 → 검증 → 재개
    python -m ai_server.tools.pinecone_sync --audit     카탈로그 ↔ 인덱스 대사
    python -m ai_server.tools.pinecone_sync --verify    연결·차원·개수만 본다
    python -m ai_server.tools.pinecone_sync --status    지금 멈춰 있는가
    python -m ai_server.tools.pinecone_sync --delete ID [ID ...]
    python -m ai_server.tools.pinecone_sync --resume    사람이 확인한 뒤 손으로 재개

## 무중단 전환을 만들지 않는다

활성 namespace 는 **고정**이다(`MUPID_PINECONE_NAMESPACE`). 분석 배치가 벡터나 추천
풀을 바꾸면 그 자리에서 전체를 갈아 끼운다 — 새 namespace 를 잡고 옮겨 타는 구조를
두지 않는다. 대신 갈아 끼우는 동안 **추천을 멈춘다.**

    멈춤 표시 → 잔존 삭제 → candidate·raw 전량 upsert → 검증 → 표시 제거

**검증이 전부 통과할 때만 재개한다.** 어느 한 단계라도 실패하면 표시가 남고, 서빙
파드는 `/internal/ready` 가 실패해 준비 완료가 되지 않는다. 반쯤 적재된 벡터로
추천하지 않기 위한 것이다.

언제 도는가는 이 파일이 정하지 않는다 — 기존 `ai-labeler` CronJob 의 일정을 그대로
따른다(`infra_k8s/base/ai-labeler.yaml`). 새 CronJob 을 만들지 않는다.

## 무엇을 올리나

    candidate  normalize(Z - mu)   후보 검색 전용
    raw        Z (저장본 그대로)    DNA·온보딩 평균 전용

둘 다 id 는 `external_id` 이고 **metadata 는 없다.** 곡명·장르·무드·보컬·악기·시간의
정본은 MySQL 이고 추천 서버는 Spring 내부 API 로 읽는다.

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

from ..app import catalog_api, paths, pinecone_store as ps


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
        rc.CAT = rc.Catalog()
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


def _require_indexes(dim: int) -> dict:
    out = {}
    for role in ps.ROLES:
        try:
            out[role] = ps.check_dimension(dim, role)
        except ps.DimensionMismatch as e:
            _fail(str(e), 3)
        except ps.PineconeError as e:
            _fail(f"{role} 인덱스를 확인하지 못했다 — {e}\n"
                  f"  콘솔에서 dimension={dim}, metric=dotproduct, type=dense 로 "
                  f"먼저 만든다 (ai_server/docs/PINECONE.md)", 3)
    return out


# ------------------------------------------------------------------ 전체 갱신
def cmd_reload(args) -> int:
    """멈춤 → 전체 갱신 → 검증 → 재개. **실패하면 재개하지 않는다.**"""
    ids, Z, version, skipped = _targets()
    dim = int(Z.shape[1])
    snapshot = _snapshot(ids, Z, version)
    _say(f"대상 {len(ids)}건 (풀·카탈로그 밖 {skipped}건 제외) · {dim}차 "
         f"· 적재본 {snapshot[:12]}…")

    _require_indexes(dim)
    if args.dry_run:
        _say(f"[모의] namespace {ps.namespace()!r} 에 {len(ids)}건을 갈아 끼운다")
        return 0

    # ① 멈춘다. 이 뒤로 서빙 파드는 준비 완료가 되지 않는다.
    ps.pause_serving("pinecone-reload", {"targets": len(ids), "snapshot": snapshot})
    _say("추천을 멈췄다 — 갱신이 끝나고 검증을 통과해야 재개한다")

    # ② 잔존을 먼저 지운다. 남겨 두면 대사가 끝내 맞지 않는다.
    want = set(ids)
    stale = sorted({i for role in ps.ROLES for i in ps.list_ids(role)} - want)
    if stale:
        ps.delete(stale)
        _say(f"잔존 {len(stale)}건 삭제")

    # ③ 평균을 새 적재본 기준으로 굳히고 두 인덱스를 채운다.
    mu = Z.mean(0)
    path = paths.data_dir() / ps.CENTER_FILE
    ps.save_center(path, mu, version, ps.namespace(), len(ids), snapshot)
    ps.forget_center()
    center = ps.center(path)
    ps.upsert(ps.RAW, ids, Z)
    ps.upsert(ps.CANDIDATE, ids, center.apply(Z))
    _say(f"적재 완료 — raw·candidate 각 {len(ids)}건")

    # ④ 검증. 하나라도 어긋나면 멈춘 채로 끝낸다.
    rep = _audit()
    if not rep["ok"]:
        _say("검증 실패 — 추천을 멈춘 채로 둔다")
        _report(rep)
        _fail("전체 갱신 후 대사가 맞지 않는다. 고친 뒤 --reload 를 다시 돌린다", 4)

    # ⑤ 재개.
    ps.resume_serving()
    _say(f"검증 통과 — 추천을 재개했다 (namespace {ps.namespace()!r}, {len(ids)}건)")
    return 0


def cmd_delete(args) -> int:
    todo = [str(i) for i in args.delete]
    if args.dry_run:
        _say(f"[모의] {len(todo)}건 삭제 예정 (두 인덱스 모두)")
        return 0
    ps.delete(todo)
    _say(f"삭제 {len(todo)}건 — raw·candidate 양쪽에서 지웠다")
    return 0


def cmd_resume(args) -> int:
    """사람이 확인한 뒤 손으로 재개한다. **검증을 건너뛰는 길이다.**"""
    state = ps.pause_state()
    if state is None:
        _say("멈춰 있지 않다")
        return 0
    rep = _audit()
    if not rep["ok"] and not args.force:
        _report(rep)
        _fail("대사가 맞지 않는다. 그래도 재개하려면 --force 를 준다", 4)
    ps.resume_serving()
    _say(f"재개했다 (멈춘 까닭: {state.get('reason')})")
    return 0


def cmd_status(args) -> int:
    state = ps.pause_state()
    print(json.dumps({"paused": state is not None, "state": state,
                      "namespace": ps.namespace()}, ensure_ascii=False, indent=2))
    return 0


# ------------------------------------------------------------------ 대사
def _audit() -> dict:
    ids, Z, version, skipped = _targets()
    snapshot = _snapshot(ids, Z, version)
    want = set(ids)
    got = {role: set(ps.list_ids(role)) for role in ps.ROLES}

    out = {"namespace": ps.namespace(), "expected": len(want),
           "skipped_outside_pool_or_catalog": skipped,
           "snapshot": snapshot, "roles": {}}
    for role, have in got.items():
        out["roles"][role] = {
            "present": len(have),
            "missing": sorted(want - have)[:20], "missing_count": len(want - have),
            "stale": sorted(have - want)[:20], "stale_count": len(have - want)}
    out["roles_disagree"] = sorted(got[ps.CANDIDATE] ^ got[ps.RAW])[:20]
    out["roles_disagree_count"] = len(got[ps.CANDIDATE] ^ got[ps.RAW])

    # 평균도 같은 적재본인가. 인덱스 둘이 맞아도 평균이 옛 것이면 질의만 어긋난다.
    try:
        center = ps.center(reload=True)
        center.require_snapshot(snapshot)
        out["center"] = {**center.stats(), "ok": True, "error": None}
    except ps.PineconeError as e:
        out["center"] = {"ok": False, "error": str(e)}

    out["ok"] = (all(r["missing_count"] == 0 and r["stale_count"] == 0
                     for r in out["roles"].values())
                 and not out["roles_disagree_count"] and out["center"]["ok"])
    return out


def _report(rep: dict) -> None:
    _say(f"namespace {rep['namespace']!r} · 기대 {rep['expected']}건 "
         f"· 적재본 {rep['snapshot'][:12]}…")
    for role, r in rep["roles"].items():
        _say(f"  {role:9s} 있음 {r['present']}  누락 {r['missing_count']}  "
             f"잔존 {r['stale_count']}")
        if r["missing"]:
            _say(f"    누락 예: {', '.join(r['missing'][:5])}")
        if r["stale"]:
            _say(f"    잔존 예: {', '.join(r['stale'][:5])}")
    if rep["roles_disagree_count"]:
        _say(f"  두 인덱스가 어긋난다 {rep['roles_disagree_count']}건")
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
    cat = _pool()
    got = _require_indexes(int(cat.Z.shape[1]))
    for role, r in got.items():
        _say(f"{role:9s} {r['index']}  {r['dimension']}차  "
             f"namespace {r['namespace']!r} {r['vectors_in_namespace']}건")
    state = ps.pause_state()
    _say("멈춰 있다" if state else "멈춰 있지 않다")
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
    ap.add_argument("--dry-run", action="store_true",
                    help="무엇을 할지만 말하고 Pinecone 을 바꾸지 않는다")
    ap.add_argument("--force", action="store_true",
                    help="--resume 에서 대사 불일치를 무시한다")
    a = ap.parse_args(argv)

    if a.status:                                  # 설정 없이도 답할 수 있다
        return cmd_status(a)
    if not ps.configured():
        _fail("MUPID_PINECONE_API_KEY · MUPID_PINECONE_CANDIDATE_INDEX · "
              "MUPID_PINECONE_RAW_INDEX 가 필요하다", 2)

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
