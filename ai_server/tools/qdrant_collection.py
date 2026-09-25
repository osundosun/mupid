"""Qdrant 순간 벡터 컬렉션을 정의 파일대로 만든다 — **멱등** (S15P21A204-456, 설계 §6.1).

정의는 `ai_server/app/qdrant_collection.json` 한 곳이다. 이 도구는 그것을 Qdrant 에 옮길 뿐이고,
벡터는 넣지 않는다(쓰는 것은 Spring 벡터 등록 API, 457).

    없으면        컬렉션을 만든다 (named vector 전부 + 모델 벡터 stage1_v{N})
    있으면        빠진 모델 벡터만 덧붙이고(PUT …/vectors/stage1_v{N}), payload 색인을 건다
    다르면        (차원·거리가 정의와 다르면) **아무것도 고치지 않고** 실패한다 — 컬렉션을 지우고
                  다시 만드는 것은 사람의 결정이다 (벡터를 전부 다시 받아야 한다)

모델 버전은 `--model-version v3` 로 준다(여럿 가능). 주지 않으면 저장소의 `models.lock`(이미지
안이면 구운 모델 묶음)에서 읽는다 — 지금처럼 null 이면 모델 벡터 없이 만든다.

운영에서는 **배포 뒤 사람이 한 번** 돌린다. Qdrant 는 클러스터 밖에 열려 있지 않고
NetworkPolicy 가 ai 파드의 연결은 받으므로, ai 파드 안에서 돌리고 쓰기 키는 표준 입력으로 준다
(명령줄·셸 기록에 남지 않게):

    kubectl -n mupid get secret mupid-secrets -o jsonpath='{.data.MUPID_QDRANT_API_KEY}' \\
      | base64 -d \\
      | kubectl -n mupid exec -i deploy/ai -- python -m ai_server.tools.qdrant_collection --api-key-stdin

정의만 보려면(Spring 쪽 대조용) `--print`.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from ai_server.app import qdrant_schema, qdrant_store

ROOT = Path(__file__).resolve().parents[2]


def lock_versions(path: Path = ROOT / "models.lock") -> list[str]:
    """`models.lock` 의 `stage1.version`. null 이면 빈 목록.

    이미지 안에는 `models.lock` 이 없다 — 그때는 이미지에 구운 모델 묶음의 버전을 쓴다(서버의
    준비 판정이 요구하는 바로 그 버전이다).
    """
    if not path.exists():
        from ai_server.app import model_bundle            # noqa: PLC0415
        b = model_bundle.image_bundle()
        return [str(b["version"])] if b["present"] and not b["error"] and b["version"] else []
    for line in path.read_text(encoding="utf-8").splitlines():
        s = line.split("#", 1)[0].rstrip()
        if s.startswith("  version:"):
            v = s.split(":", 1)[1].strip()
            return [v] if v and v != "null" else []
    return []


def _same(want: dict, have: dict) -> list[str]:
    """정의와 컬렉션이 다른 설정. 차원·거리만 본다 — 나머지(on_disk·양자화)는 바꿀 수 있다."""
    out = []
    for k in ("size", "distance"):
        if str(want.get(k)) != str(have.get(k)):
            out.append(f"{k} {have.get(k)} ≠ 정의 {want.get(k)}")
    return out


def ensure(call, name: str, versions: list[str], log=print) -> dict:
    """컬렉션을 정의대로 맞춘다. `call(method, path, body)` 는 쓰기 키로 부르는 함수다."""
    report: dict = {"collection": name, "created": False, "added_vectors": [], "indexes": []}
    try:
        info = call("GET", f"/collections/{name}", None)
    except qdrant_store.QdrantError as e:
        if not str(e).startswith("HTTP 404"):
            raise
        info = None
    if info is None:
        call("PUT", f"/collections/{name}", qdrant_schema.collection_body(versions))
        report["created"] = True
        log(f"만들었다: {name} (모델 벡터 {', '.join(versions) or '없음'})")
    else:
        have = ((info.get("config") or {}).get("params") or {}).get("vectors") or {}
        want = qdrant_schema.vectors(versions)
        bad = {k: _same(v, have[k]) for k, v in want.items() if k in have and _same(v, have[k])}
        if bad:
            raise SystemExit(f"{name} 의 벡터 설정이 정의와 다르다 — 고치지 않는다: {bad}")
        for k, v in want.items():
            if k in have:
                continue
            call("PUT", f"/collections/{name}/vectors/{k}?wait=true", {"dense": v})
            report["added_vectors"].append(k)
            log(f"덧붙였다: {name}.{k}")
    for field, kind in qdrant_schema.payload_indexes():
        # 같은 색인을 다시 걸어도 Qdrant 는 성공으로 답한다 — 그래서 매번 건다.
        call("PUT", f"/collections/{name}/index?wait=true",
             {"field_name": field, "field_schema": kind})
        report["indexes"].append(field)
    log(f"payload 색인: {', '.join(report['indexes'])}")
    return report


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--url", default=os.environ.get("MUPID_QDRANT_URL") or qdrant_store.DEFAULT_URL)
    ap.add_argument("--collection", default=None, help="기본은 정의 파일의 이름")
    ap.add_argument("--model-version", action="append", default=None,
                    help="stage1 모델 버전 (v3 …). 주지 않으면 models.lock")
    ap.add_argument("--api-key-stdin", action="store_true",
                    help="쓰기 키를 표준 입력 첫 줄에서 읽는다 (없으면 MUPID_QDRANT_API_KEY)")
    ap.add_argument("--print", action="store_true", help="만들 본문만 찍고 끝낸다")
    a = ap.parse_args(argv)

    name = a.collection or qdrant_schema.collection_name()
    versions = a.model_version if a.model_version is not None else lock_versions()
    if a.print:
        print(json.dumps({"collection": name, "body": qdrant_schema.collection_body(versions),
                          "payload_indexes": qdrant_schema.payload_indexes()},
                         ensure_ascii=False, indent=1))
        return 0
    key = sys.stdin.readline().strip() if a.api_key_stdin else os.environ.get("MUPID_QDRANT_API_KEY", "")
    if not key:
        # 읽기 전용 키로는 만들 수 없다(403). 키 없이 부르면 잠긴 Qdrant 가 401 을 준다.
        print("쓰기 키가 없다 — --api-key-stdin 또는 MUPID_QDRANT_API_KEY", file=sys.stderr)
        return 2

    def call(method, path, body):
        return qdrant_store.request(method, path, body, timeout_s=60, api_key=key, base=a.url)

    try:
        report = ensure(call, name, versions)
    except qdrant_store.QdrantError as e:
        print(f"실패: {e}", file=sys.stderr)
        return 1
    print(json.dumps(report, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
