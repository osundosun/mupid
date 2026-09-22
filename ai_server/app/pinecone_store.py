"""Pinecone 순간 벡터. **검색과 fetch 만** 한다.

## 물리 인덱스가 둘이다

추천기는 같은 순간 벡터를 **두 가지 공간**에서 쓴다. 한 인덱스에 섞어 담을 수 없어
물리 인덱스를 나눈다 — 역할을 namespace 로 가르지 않는 이유는, namespace 가 이미
"환경 + 승인된 임베딩 버전" 을 표현하고 있어서 거기에 역할까지 얹으면 전환할 때
무엇을 바꾸는지가 흐려지기 때문이다.

    candidate  normalize(Z - mu)   후보 검색 전용. 질의도 같은 변환을 거친다
    raw        Z (저장본 그대로)    DNA 군집·온보딩 조건별 평균 전용

    ┌ 섞어 쓰면 안 된다 ─────────────────────────────────────────────┐
    │ 원본 Z 로 후보를 찾으면 오늘의 순위와 달라지고(중심화가 빠진다), │
    │ 변환 벡터로 평균을 내면 DNA 중심이 어긋난 공간에 놓인다.        │
    │ 둘 다 **오류가 나지 않는** 고장이다.                            │
    └────────────────────────────────────────────────────────────────┘

## metadata 를 넣지 않는다

Pinecone 에는 `external_id` 와 벡터만 둔다. 곡명·장르·무드·보컬·악기·시간은
MySQL 이 정본이고 Spring 내부 API 로 읽는다(`app/catalog_api.py`). 한 벌 더 넣으면
DB 에서 장르를 고쳐도 검색 쪽은 옛 값으로 계속 돈다 — **오류가 나지 않는 종류의
고장**이고, 이 저장소가 `flow_meta.parquet` 으로 이미 한 번 겪었다.

그래서 이 모듈이 돌려주는 것은 `(external_id, score)` 와 벡터뿐이다. 살을 붙이는
것은 부르는 쪽 일이다.

## 중심화 평균은 판에 고정한다

추천기의 `center()` 는 풀 평균을 빼고 다시 L2 한다. 요청마다 풀에서 다시 재면,
순간 하나를 upsert 할 때마다 평균이 움직여 이미 들어간 벡터 전부가 조용히 어긋난
공간에 놓인다. 그래서 `pinecone_center.npz` 에 굳혀 적재 도구와 서버가 **같은
변환**을 쓴다는 것을 파일 하나로 보장한다.

## 환경변수

    MUPID_PINECONE_API_KEY           비밀. Secret 에서만 온다. 로그·응답에 싣지 않는다
    MUPID_PINECONE_CANDIDATE_INDEX   후보 검색 인덱스 이름
    MUPID_PINECONE_RAW_INDEX         원본 벡터 인덱스 이름
    MUPID_PINECONE_NAMESPACE         환경 + 승인된 임베딩 버전 (역할은 담지 않는다)
    MUPID_PINECONE_ENABLED           검색 경로를 Pinecone 으로 돌릴지. 기본 꺼짐
    MUPID_PINECONE_TIMEOUT           초. 기본 10

**인덱스를 만들지 않는다.** 없으면 만들어야 할 설정을 문구로 알리고 멈춘다. 서버가
운영 인덱스를 자동 생성하면 차원이나 metric 이 하나 틀린 인덱스가 조용히 서고, 그
위에 적재가 끝난 뒤에야 드러난다.
"""
from __future__ import annotations

import os
from typing import Optional, Sequence

import numpy as np

# 판에 고정해 두는 중심화 평균. `tools/pinecone_sync.py --init` 이 만든다.
CENTER_FILE = "pinecone_center.npz"

DEFAULT_TIMEOUT = 10
# 한 번에 올리는 벡터 수. Pinecone 요청 상한이 2MB 이고 1536차 float 하나가
# 약 18KB 라, 100개면 1.8MB 로 상한 안쪽이다. 개수 상한(1000)으로 잡으면 크기
# 상한에 먼저 걸려 413 이 난다.
UPSERT_BATCH = 100
DELETE_BATCH = 1000
# fetch 는 한 번에 받을 수 있는 수가 작다. 온보딩 씨앗이 수백 개를 부른다.
FETCH_BATCH = 100

CANDIDATE = "candidate"
RAW = "raw"
ROLES = (CANDIDATE, RAW)


class PineconeError(Exception):
    """Pinecone 에 닿지 못했거나 거절당했다.

    **삼키지 않는다.** 검색을 Pinecone 으로 돌려 둔 배포에서 이것을 빈 목록으로
    바꾸면 "닮은 구간이 없다"(정상)와 구분되지 않는다. 둘은 전혀 다른 상태다.
    """


class PineconeNotConfigured(PineconeError):
    """켜 두었는데 키나 인덱스 이름이 없다."""


class DimensionMismatch(PineconeError):
    """인덱스 차원이 판의 벡터 차원과 다르다. 적재도 검색도 의미가 없다."""


class GenerationMismatch(PineconeError):
    """고정 평균이 지금 보는 namespace·판과 다른 세대다.

    셋이 한 묶음으로 움직여야 한다 — candidate 인덱스·raw 인덱스·`pinecone_center.npz`.
    하나만 갈아 끼우면 **오류 없이** 질의가 다른 공간으로 나가거나 평균이 어긋난
    공간에서 계산된다. 그래서 묶음이 어긋나면 멈춘다.
    """


# ------------------------------------------------------------------ 설정
def _env(name: str) -> str:
    return os.environ.get(name, "").strip()


def enabled() -> bool:
    """검색 경로를 Pinecone 으로 돌리는가.

    **기본은 꺼짐이다.** 켜는 것은 초기 적재와 대사가 끝난 뒤의 별도 동작이어야
    한다 — 키만 넣으면 켜지는 구조로 두면, 아직 비어 있는 인덱스로 검색이 나가
    모든 요청이 "닮은 구간 없음" 이 된다.
    """
    return _env("MUPID_PINECONE_ENABLED").lower() in ("1", "true", "yes", "on")


def index_name(role: str) -> str:
    if role == CANDIDATE:
        return _env("MUPID_PINECONE_CANDIDATE_INDEX")
    if role == RAW:
        return _env("MUPID_PINECONE_RAW_INDEX")
    raise ValueError(f"알 수 없는 역할: {role!r}")


def configured() -> bool:
    """적재·검색에 필요한 값이 다 있는가. **키 값 자체는 보지 않는다.**"""
    return bool(_env("MUPID_PINECONE_API_KEY")
                and index_name(CANDIDATE) and index_name(RAW))


def namespace() -> str:
    """환경 + 승인된 임베딩 버전. 빈 값도 유효한 namespace 라 강제하지 않는다."""
    return _env("MUPID_PINECONE_NAMESPACE")


def timeout() -> int:
    raw = _env("MUPID_PINECONE_TIMEOUT")
    try:
        return max(1, int(float(raw))) if raw else DEFAULT_TIMEOUT
    except ValueError:
        return DEFAULT_TIMEOUT


def settings() -> dict:
    """진단용. **키는 있는지 여부만** 싣는다 — 값도 앞자리도 내보내지 않는다."""
    return {
        "enabled": enabled(),
        "configured": configured(),
        "api_key_present": bool(_env("MUPID_PINECONE_API_KEY")),
        "candidate_index": index_name(CANDIDATE),
        "raw_index": index_name(RAW),
        "namespace": namespace(),
        "timeout": timeout(),
    }


# ------------------------------------------------------------------ 접속
#
# 공식 SDK(`pinecone`)만 쓴다. import 를 함수 안에 두는 것은 **SDK 가 없어도
# 모듈이 import 되게** 하기 위해서다 — 오프라인 계약 테스트가 이 모듈의 설정
# 판정을 읽고, CI 이미지에 SDK 가 아직 안 구워졌을 수 있다.
_CLIENTS: dict = {}


def _client():
    try:
        from pinecone import Pinecone            # noqa: PLC0415
    except ImportError as e:
        raise PineconeNotConfigured(
            "pinecone SDK 가 없다 — ai_server/requirements.txt 를 설치하고 "
            "CI 이미지를 다시 굽는다 (sh infra/runner/build-images.sh)") from e
    key = _env("MUPID_PINECONE_API_KEY")
    if not key:
        raise PineconeNotConfigured(
            "MUPID_PINECONE_API_KEY 가 비어 있다 — Secret mupid-ai-secrets 에서 온다")
    client = _CLIENTS.get("client")
    if client is None:
        client = Pinecone(api_key=key)
        _CLIENTS["client"] = client
    return client


def _index(role: str):
    """역할별 인덱스 핸들. **만들지 않는다.**"""
    name = index_name(role)
    if not name:
        raise PineconeNotConfigured(
            f"{'MUPID_PINECONE_CANDIDATE_INDEX' if role == CANDIDATE else 'MUPID_PINECONE_RAW_INDEX'}"
            f" 가 비어 있다 (ai_server/docs/PINECONE.md)")
    handle = _CLIENTS.get(f"index:{role}")
    if handle is None:
        handle = _client().Index(name)
        _CLIENTS[f"index:{role}"] = handle
    return handle


def reset() -> None:
    """시험 전용. 캐시해 둔 클라이언트를 버린다."""
    _CLIENTS.clear()


def _wrap(action: str, role: str, fn, *args, **kwargs):
    """SDK 예외를 이 모듈의 예외로 바꾼다. **메시지에 키가 섞이지 않게 지운다.**"""
    try:
        return fn(*args, **kwargs)
    except (PineconeError, DimensionMismatch):
        raise
    except Exception as e:                       # SDK 예외 종류가 판마다 다르다
        raise PineconeError(
            f"Pinecone {action} 실패 ({role}/{index_name(role)}): "
            f"{type(e).__name__}: {_redact(str(e))[:300]}") from None


def _redact(text: str) -> str:
    key = _env("MUPID_PINECONE_API_KEY")
    return text.replace(key, "***") if key else text


# ------------------------------------------------------------------ 데이터 평면
def upsert(role: str, ids: Sequence[str], vectors: np.ndarray) -> int:
    """`external_id` 로 벡터를 올린다. 같은 ID 면 **교체**된다.

    metadata 를 싣지 않는다 — 이 모듈 맨 위의 설명을 본다.
    """
    V = np.atleast_2d(np.asarray(vectors, dtype=np.float32))
    if len(ids) != len(V):
        raise ValueError(f"id {len(ids)}개와 벡터 {len(V)}개가 다르다")
    if not len(ids):
        return 0
    index, ns = _index(role), namespace()
    sent = 0
    for start in range(0, len(ids), UPSERT_BATCH):
        chunk = [{"id": str(i), "values": [float(x) for x in v]}
                 for i, v in zip(ids[start:start + UPSERT_BATCH],
                                 V[start:start + UPSERT_BATCH])]
        _wrap("upsert", role, index.upsert, vectors=chunk, namespace=ns)
        sent += len(chunk)
    return sent


def delete(ids, roles: Sequence[str] = ROLES) -> int:
    """삭제·비추천 전환된 순간을 지운다. **기본은 두 인덱스 모두.**

    한쪽만 지우면 후보로는 안 나오는데 DNA 평균에는 계속 섞이거나 그 반대가 된다.
    없는 ID 를 지우는 것은 오류가 아니다 — 재실행을 안전하게 만들려고 그렇게 둔다.
    """
    todo = [str(i) for i in ids]
    if not todo:
        return 0
    ns = namespace()
    for role in roles:
        index = _index(role)
        for start in range(0, len(todo), DELETE_BATCH):
            _wrap("delete", role, index.delete,
                  ids=todo[start:start + DELETE_BATCH], namespace=ns)
    return len(todo)


def search(vector: np.ndarray, top_k: int) -> list[tuple[str, float]]:
    """닮은 순간 후보. **후보 검색 인덱스에서만** 찾는다.

    질의 벡터는 부르는 쪽이 이미 `Center.apply()` 를 거친 것이어야 한다 — 저장된
    벡터와 같은 변환을 거치지 않으면 유사도가 통째로 내려앉는다.

    `include_metadata` 를 켜지 않는다. 넣지 않았으니 읽을 것도 없고, 켜 두면
    나중에 누군가 metadata 를 넣는 쪽으로 되돌리기 쉬워진다.
    """
    q = np.asarray(vector, dtype=np.float32).reshape(-1)
    body = _wrap("query", CANDIDATE, _index(CANDIDATE).query,
                 vector=[float(x) for x in q], top_k=int(top_k),
                 namespace=namespace(), include_values=False,
                 include_metadata=False)
    out: list[tuple[str, float]] = []
    for m in _matches(body):
        mid = m.get("id")
        if mid is not None:
            out.append((str(mid), float(m.get("score", 0.0))))
    return out


def fetch(ids: Sequence[str]) -> dict:
    """원본 벡터를 ID 로 가져온다. **원본 인덱스에서만.**

    DNA 군집과 온보딩 조건별 평균이 쓴다. 둘 다 "닮은 것 찾기" 가 아니라 "이
    구간들의 평균" 이라 ANN 질의로 표현되지 않는다 — 그래서 fetch 가 필요하고,
    그래서 원본 인덱스가 따로 있다.

    없는 ID 는 **조용히 빠진다.** 부르는 쪽이 받은 수를 세어 판단한다.
    """
    todo = [str(i) for i in ids]
    if not todo:
        return {}
    index, ns = _index(RAW), namespace()
    out: dict = {}
    for start in range(0, len(todo), FETCH_BATCH):
        body = _wrap("fetch", RAW, index.fetch,
                     ids=todo[start:start + FETCH_BATCH], namespace=ns)
        for vid, rec in _vectors(body).items():
            values = rec.get("values") if isinstance(rec, dict) else getattr(rec, "values", None)
            if values is None:
                continue
            out[str(vid)] = np.asarray(values, dtype=np.float32)
    return out


def stats(role: str) -> dict:
    """차원과 namespace 별 벡터 수."""
    body = _wrap("describe_index_stats", role, _index(role).describe_index_stats)
    data = _as_dict(body)
    ns = namespace()
    counts = data.get("namespaces") or {}
    mine = _as_dict(counts.get(ns) or {})
    return {"role": role, "index": index_name(role),
            "dimension": int(data["dimension"]) if data.get("dimension") is not None else None,
            "namespace": ns,
            "vectors_in_namespace": int(mine.get("vector_count")
                                        or mine.get("vectorCount") or 0),
            "vectors_total": int(data.get("total_vector_count")
                                 or data.get("totalVectorCount") or 0)}


def check_dimension(expected: int, role: str) -> dict:
    """인덱스 차원이 판의 벡터 차원과 같은가.

    같지 않으면 **적재도 검색도 하지 않는다.** 차원이 다르면 upsert 가 건건이
    거절되고, 그 실패가 배치 로그에만 쌓여 "왜 추천이 비나" 로 뒤늦게 드러난다.
    차원은 만든 뒤 바꿀 수 없다.
    """
    got = stats(role)
    actual = got.get("dimension")
    if actual is None:
        raise PineconeError(f"{role} 인덱스에서 dimension 을 읽지 못했다")
    if int(actual) != int(expected):
        raise DimensionMismatch(
            f"{role} 인덱스({index_name(role)}) 차원이 {actual} 인데 판의 순간 "
            f"벡터는 {expected} 다. 차원은 만든 뒤 바꿀 수 없다 — "
            f"dimension={expected} 인 인덱스를 새로 만든다")
    return got


def list_ids(role: str) -> list[str]:
    """namespace 안의 ID 전체. 대사(對査)와 가지치기에 쓴다.

    운영 요청 경로가 아니라 도구에서만 부른다 — 1만 구간이면 페이지를 100번 넘긴다.
    """
    index = _index(role)
    out: list[str] = []
    for page in _wrap("list", role, index.list, namespace=namespace()):
        # SDK 는 ID 목록을 그대로 주기도 하고 객체로 감싸 주기도 한다.
        for vid in (page if isinstance(page, (list, tuple)) else getattr(page, "ids", []) or []):
            out.append(str(vid.get("id") if isinstance(vid, dict) else vid))
    return out


# ---- SDK 응답 모양 흡수 ------------------------------------------------------
#
# SDK 판에 따라 dict 를 주기도 하고 객체를 주기도 한다. 부르는 쪽이 그 차이를
# 알 필요는 없으므로 여기서만 흡수한다.

def _as_dict(body) -> dict:
    if isinstance(body, dict):
        return body
    for attr in ("to_dict", "_asdict"):
        fn = getattr(body, attr, None)
        if callable(fn):
            return fn()
    return dict(getattr(body, "__dict__", {}) or {})


def _matches(body) -> list:
    data = _as_dict(body)
    got = data.get("matches")
    if got is None:
        got = getattr(body, "matches", None) or []
    return [_as_dict(m) for m in got]


def _vectors(body) -> dict:
    data = _as_dict(body)
    got = data.get("vectors")
    if got is None:
        got = getattr(body, "vectors", None) or {}
    return dict(got)


# ------------------------------------------------------------------ 중심화
class Center:
    """판에 고정된 중심화 평균. 적재 도구와 서버가 **같은 것**을 읽는다."""

    def __init__(self, mu: np.ndarray, embedding_version: str,
                 namespace_: str, rows: int, snapshot: str = "") -> None:
        self.mu = np.asarray(mu, dtype=np.float32).reshape(-1)
        self.embedding_version = embedding_version
        self.namespace = namespace_
        self.rows = int(rows)
        # 이 평균을 잰 적재본의 지문. 벡터가 하나만 바뀌어도 달라진다.
        self.snapshot = str(snapshot)

    @property
    def dim(self) -> int:
        return int(self.mu.shape[0])

    def apply(self, V: np.ndarray) -> np.ndarray:
        """`recommender.center()` 와 **같은 식**이다. 둘이 갈리면 순위가 갈린다."""
        W = np.atleast_2d(np.asarray(V, dtype=np.float32))
        if W.shape[1] != self.dim:
            raise DimensionMismatch(
                f"벡터 차원 {W.shape[1]} 이 고정 평균 {self.dim} 과 다르다")
        W = W - self.mu
        W /= np.clip(np.linalg.norm(W, axis=1, keepdims=True), 1e-9, None)
        return W

    def stats(self) -> dict:
        return {"dim": self.dim, "rows": self.rows,
                "embedding_version": self.embedding_version,
                "namespace": self.namespace, "snapshot": self.snapshot}

    def require_namespace(self, want: str) -> None:
        """지금 쓰는 namespace 와 같은 세대인가.

        새 namespace 로 돌려 놓고 평균을 갈아 끼우지 않으면, 질의는 새 벡터를
        보는데 변환은 옛 공간 기준이 된다 — 유사도만 조용히 내려앉는다.
        """
        if self.namespace != want:
            raise GenerationMismatch(
                f"고정 평균은 namespace {self.namespace!r} 에서 만든 것인데 지금은 "
                f"{want!r} 을 쓴다. 새 namespace 로 바꿀 때는 "
                f"`pinecone_sync --init` 으로 평균부터 다시 굳힌다")

    def require_snapshot(self, want: str) -> None:
        """이 평균을 굳힌 적재본과 지금 판의 적재본이 같은가.

        **벡터만 바뀐 재분석도 잡는다** — 그것이 id 목록이 아니라 값까지 담는
        이유다. 어긋나면 04:00 배치가 전체 갱신을 다시 돌려야 한다.
        """
        if not self.snapshot:                    # 옛 파일에는 지문이 없다
            return
        if want != self.snapshot:
            raise GenerationMismatch(
                f"고정 평균을 굳힌 적재본({self.snapshot[:12]}…, {self.rows}건)과 지금 "
                f"판의 적재본({want[:12]}…)이 다르다. 추천을 멈춘 채 "
                f"`pinecone_sync --reload` 로 전체를 다시 올린다")


def snapshot_hash(moment_ids, vectors, embedding_version: str,
                  pool_sources) -> str:
    """이 적재본의 지문. **id 목록만으로는 모자란다.**

    재분석으로 같은 `external_id` 의 **벡터만** 바뀌는 경우가 흔하다. id 만 세면
    그때 지문이 같게 나와, 옛 평균과 새 벡터가 섞인 상태를 아무도 잡지 못한다 —
    오류 없이 유사도만 내려앉는다. 그래서 값까지 넣는다.

    담는 것 넷:

        정렬된 external_id   구성이 같으면 차례가 달라도 같아야 한다
        벡터 값              float32 바이트. 한 구간만 바뀌어도 달라진다
        임베딩 버전          모델·층·풀링이 바뀌면 같은 값이라도 뜻이 다르다
        추천 풀 설정         MUPID_POOL / MUPID_POOL_EXTRA. 대상이 달라진다

    `candidate` 인덱스·`raw` 인덱스·`pinecone_center.npz` 가 **같은 지문**을 갖도록
    강제한다. 하나만 갈아 끼운 상태를 기동에서 잡기 위한 것이다.
    """
    import hashlib                               # noqa: PLC0415

    V = np.atleast_2d(np.asarray(vectors, dtype=np.float32))
    ids = [str(m) for m in moment_ids]
    if len(ids) != len(V):
        raise ValueError(f"id {len(ids)}개와 벡터 {len(V)}개가 다르다")

    h = hashlib.sha256()
    h.update(b"snapshot-v1\x00")
    h.update(str(embedding_version).encode("utf-8") + b"\x00")
    for src in sorted(str(s) for s in pool_sources):
        h.update(src.encode("utf-8") + b"\x00")
    h.update(b"--\x00")
    # id 차례로 정렬해 담는다. 같은 구성·같은 값이면 적재 차례와 무관하게 같다.
    for position in sorted(range(len(ids)), key=lambda i: ids[i]):
        # 구분자를 넣는다. 넣지 않으면 `A`+`BC` 와 `AB`+`C` 가 같은 지문이 된다.
        h.update(ids[position].encode("utf-8") + b"\x00")
        h.update(np.ascontiguousarray(V[position], dtype=np.float32).tobytes())
    return h.hexdigest()


def save_center(path, mu: np.ndarray, embedding_version: str,
                namespace_: str, rows: int, snapshot: str = "") -> None:
    np.savez(path, mu=np.asarray(mu, dtype=np.float32),
             embedding_version=np.asarray(embedding_version),
             namespace=np.asarray(namespace_), rows=np.asarray(int(rows)),
             snapshot=np.asarray(snapshot))


def load_center(path) -> Center:
    """없으면 **멈춘다.**

    여기서 즉석 평균으로 대신하면 적재 때와 다른 공간으로 질의가 나가 유사도가
    통째로 내려앉는다 — 오류 없이 품질만 나빠진다.
    """
    from pathlib import Path                     # noqa: PLC0415

    p = Path(path)
    if not p.exists():
        raise PineconeError(
            f"{p.name} 이 없다 — 중심화 평균이 판에 없으면 적재 때와 다른 공간으로 "
            f"질의가 나간다. `python -m ai_server.tools.pinecone_sync --init` 이 만든다")
    with np.load(p, allow_pickle=False) as d:
        return Center(d["mu"], str(d["embedding_version"]),
                      str(d["namespace"]), int(d["rows"]),
                      str(d["snapshot"]) if "snapshot" in d else "")


_CENTER: Optional[Center] = None


def center(path=None, reload: bool = False) -> Center:
    """고정 평균을 한 번만 읽어 들고 있는다."""
    global _CENTER
    if _CENTER is None or reload:
        if path is None:
            from . import paths                  # noqa: PLC0415

            path = paths.data_dir() / CENTER_FILE
        got = load_center(path)
        # **읽는 그 자리에서 세대를 본다.** 나중에 어딘가에서 검사하기로 하면
        # 검사하지 않는 경로가 하나씩 생긴다.
        got.require_namespace(namespace())
        _CENTER = got
    return _CENTER


def forget_center() -> None:
    """시험 전용."""
    global _CENTER
    _CENTER = None


# ------------------------------------------------------------------ 서빙 중단
#
# 전체 갱신 중에는 candidate 와 raw 가 서로 어긋나 있다. 그 상태의 추천은 오류가
# 아니라 그냥 이상한 결과로 나오므로, 갱신하는 동안에는 추천을 멈춘다.
#
# **무중단 namespace 전환을 만들지 않는다.** 배치가 도는 시간대의 짧은 중단을 받고
# 대신 구조를 하나로 둔다 — 활성 namespace 는 고정이고, 갈아 끼우는 것은 그 안의
# 벡터뿐이다.
#
# 표시를 파일로 두는 것은 **권한 때문**이다. 배치는 `/state` 에 쓸 수 있고 서빙은
# 같은 자리를 읽을 수 있다. 쿠버네티스 API 권한도 새 CronJob 도 필요 없다.

def pause_serving(reason: str, detail: Optional[dict] = None) -> None:
    import json                                  # noqa: PLC0415
    from datetime import datetime, timezone      # noqa: PLC0415

    from . import paths                          # noqa: PLC0415

    marker = paths.pause_marker()
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.write_text(json.dumps({
        "reason": reason,
        "since": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        **(detail or {})}, ensure_ascii=False), encoding="utf-8")


def resume_serving() -> None:
    """**검증이 전부 통과했을 때만** 부른다. 실패하면 표시를 남겨 둔다."""
    from . import paths                          # noqa: PLC0415

    paths.pause_marker().unlink(missing_ok=True)


def pause_state() -> Optional[dict]:
    """멈춰 있으면 그 까닭, 아니면 None.

    파일을 읽지 못하면 **멈춘 것으로 본다.** 표시가 있는데 못 읽었다고 추천을
    내보내면, 반쯤 적재된 벡터로 답하게 된다.
    """
    import json                                  # noqa: PLC0415

    from . import paths                          # noqa: PLC0415

    marker = paths.pause_marker()
    if not marker.exists():
        return None
    try:
        got = json.loads(marker.read_text(encoding="utf-8"))
        return got if isinstance(got, dict) else {"reason": "표시 파일이 dict 가 아니다"}
    except Exception as e:
        return {"reason": f"중단 표시를 읽지 못했다: {type(e).__name__}"}
