"""순간 벡터의 **유일한 운영 정본**. 물리 인덱스 하나에 담는다.

## 저장하는 것

    데이터 namespace   id = external_id
                       vector = u = (Z - mu) / ‖Z - mu‖      후보 검색이 쓰는 그 공간
                       metadata = {"r": ‖Z - mu‖}            원본 복원에 필요한 스칼라 하나

    control namespace  id = __center__
                       vector = mu                            중심화 평균
                       metadata = {embedding_version, snapshot, rows, dim}

**원본 Z 를 따로 저장하지 않는다.** 같은 레코드의 `r` 과 control 의 `mu` 로 되돌린다.

    Z = mu + r · u

정확한 복원이다 — 근사가 아니다. `u` 와 `r` 이 `Z - mu` 를 그대로 쪼갠 것이라
부동소수점 반올림만 남는다. 그래서 raw 전용 인덱스를 두지 않는다.

## metadata 에 DB 소유값을 넣지 않는다

곡명·장르·무드·보컬·악기·순간 시각·삭제 여부는 MySQL 이 정본이고 Spring 내부
카탈로그 API 로 읽는다(`app/catalog_api.py`). 여기 들어가는 `r` 은 **벡터를 쪼갠
값**이지 DB 가 아는 사실이 아니다. 그 구분이 이 파일의 경계다.

한 벌 더 넣으면 DB 에서 장르를 고쳐도 검색 쪽은 옛 값으로 계속 돈다 — **오류가
나지 않는 종류의 고장**이고, 이 저장소가 `flow_meta.parquet` 으로 이미 겪었다.

## 세대는 control 이 들고 있다

`mu` 와 `snapshot` 이 데이터와 같은 인덱스·같은 갱신에서 쓰이므로 둘이 어긋날
자리가 없다. 로컬에 평균 파일을 두지 않는 이유이기도 하다 — 파일은 판마다 따로
움직여서, 인덱스만 갈아 끼우고 파일은 그대로인 상태가 생긴다.

## 환경변수

    MUPID_PINECONE_API_KEY    비밀. Secret 에서만 온다. 로그·응답에 싣지 않는다
    MUPID_PINECONE_INDEX      인덱스 이름 (하나)
    MUPID_PINECONE_NAMESPACE  환경 + 승인된 임베딩 버전. 고정값
    MUPID_PINECONE_ENABLED    검색 경로를 Pinecone 으로 돌릴지. 기본 꺼짐
    MUPID_PINECONE_TIMEOUT    초. 기본 10

**인덱스를 만들지 않는다.** 없으면 만들어야 할 설정을 문구로 알리고 멈춘다.
"""
from __future__ import annotations

import os
from typing import Optional, Sequence

import numpy as np

DEFAULT_TIMEOUT = 10
# 한 번에 올리는 벡터 수. Pinecone 요청 상한이 2MB 이고 1536차 float 하나가 약
# 18KB 라, 100개면 1.8MB 로 상한 안쪽이다. 개수 상한(1000)으로 잡으면 크기 상한에
# 먼저 걸려 거절된다.
UPSERT_BATCH = 100
DELETE_BATCH = 1000
# fetch 는 한 번에 받을 수 있는 수가 작다. 온보딩 씨앗이 수백 개를 부른다.
FETCH_BATCH = 100

# control namespace 의 유일한 레코드. 데이터와 **다른 namespace** 라 검색에 섞이지 않는다.
CONTROL_ID = "__center__"
CONTROL_SUFFIX = "__control"
# control 이 들고 있는 서빙 상태. **추천을 멈추는 유일한 근거다** — 파일 표시를
# 따로 두지 않는다. 두 곳에 두면 어느 쪽이 참인지가 자리마다 갈린다.
STATE_KEY = "state"
UPDATING = "UPDATING"
READY = "READY"
# 원본 복원에 쓰는 스칼라. 벡터를 쪼갠 값이지 DB 가 아는 사실이 아니다.
RADIUS_KEY = "r"


class PineconeError(Exception):
    """Pinecone 에 닿지 못했거나 거절당했다.

    **삼키지 않는다.** 검색을 Pinecone 으로 돌려 둔 배포에서 이것을 빈 목록으로
    바꾸면 "닮은 구간이 없다"(정상)와 구분되지 않는다. 둘은 전혀 다른 상태다.
    """


class PineconeNotConfigured(PineconeError):
    """켜 두었는데 키나 인덱스 이름이 없다."""


class DimensionMismatch(PineconeError):
    """인덱스 차원이 판의 벡터 차원과 다르다. 적재도 검색도 의미가 없다."""


class ServingPaused(PineconeError):
    """control 이 `UPDATING` 이다. 벡터를 갈아 끼우는 중이라 추천할 수 없다.

    이때 데이터 namespace 는 비었거나 반쯤 차 있다. 그 상태의 추천은 오류가
    아니라 그냥 이상한 결과로 나오므로 **분명한 503 으로 거절한다.**
    """


class GenerationMismatch(PineconeError):
    """control 의 세대가 지금 판과 다르다.

    평균과 데이터가 한 갱신에서 함께 들어가므로 보통은 어긋나지 않는다. 어긋났다면
    갱신이 중간에 끊긴 것이고, 그 상태의 추천은 오류 없이 품질만 나빠진다.
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


def index_name() -> str:
    return _env("MUPID_PINECONE_INDEX")


def configured() -> bool:
    """적재·검색에 필요한 값이 다 있는가. **키 값 자체는 보지 않는다.**"""
    return bool(_env("MUPID_PINECONE_API_KEY") and index_name())


def namespace() -> str:
    """환경 + 승인된 임베딩 버전. 고정값이다 — 교체 구조를 두지 않는다."""
    return _env("MUPID_PINECONE_NAMESPACE")


def control_namespace() -> str:
    """평균과 세대만 두는 자리. 데이터와 나뉘어 있어 검색에 섞이지 않는다."""
    return namespace() + CONTROL_SUFFIX


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
        "index": index_name(),
        "namespace": namespace(),
        "control_namespace": control_namespace(),
        "timeout": timeout(),
    }


# ------------------------------------------------------------------ 접속
#
# 공식 SDK(`pinecone`)만 쓴다. import 를 함수 안에 두는 것은 **SDK 가 없어도 모듈이
# import 되게** 하기 위해서다 — 오프라인 계약 테스트가 이 모듈의 설정 판정을 읽고,
# CI 이미지에 SDK 가 아직 안 구워졌을 수 있다.
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


def _index():
    """인덱스 핸들. **만들지 않는다.**"""
    name = index_name()
    if not name:
        raise PineconeNotConfigured(
            "MUPID_PINECONE_INDEX 가 비어 있다 (ai_server/docs/PINECONE.md)")
    handle = _CLIENTS.get("index")
    if handle is None:
        handle = _client().Index(name)
        _CLIENTS["index"] = handle
    return handle


def reset() -> None:
    """시험 전용. 캐시해 둔 클라이언트와 평균을 버린다."""
    _CLIENTS.clear()
    forget_center()


def _wrap(action: str, fn, *args, **kwargs):
    """SDK 예외를 이 모듈의 예외로 바꾼다. **메시지에 키가 섞이지 않게 지운다.**"""
    try:
        return fn(*args, **kwargs)
    except PineconeError:
        raise
    except Exception as e:                       # SDK 예외 종류가 판마다 다르다
        raise PineconeError(
            f"Pinecone {action} 실패 ({index_name()}): "
            f"{type(e).__name__}: {_redact(str(e))[:300]}") from None


def _redact(text: str) -> str:
    key = _env("MUPID_PINECONE_API_KEY")
    return text.replace(key, "***") if key else text


# ---- SDK 응답 모양 흡수 ------------------------------------------------------
#
# SDK 판에 따라 dict 를 주기도 하고 객체를 주기도 한다. 부르는 쪽이 그 차이를 알
# 필요는 없으므로 여기서만 흡수한다.

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


def _record(raw) -> tuple[Optional[np.ndarray], dict]:
    rec = raw if isinstance(raw, dict) else _as_dict(raw)
    values = rec.get("values")
    if values is None:
        values = getattr(raw, "values", None)
    meta = rec.get("metadata")
    if meta is None:
        meta = getattr(raw, "metadata", None) or {}
    if values is None:
        return None, dict(meta or {})
    return np.asarray(values, dtype=np.float32), dict(meta or {})


# ------------------------------------------------------------------ 중심화
class Center:
    """control namespace 가 들고 있는 평균과 세대."""

    def __init__(self, mu: np.ndarray, embedding_version: str,
                 snapshot: str, rows: int, state: str = READY) -> None:
        self.mu = np.asarray(mu, dtype=np.float32).reshape(-1)
        self.embedding_version = str(embedding_version)
        self.snapshot = str(snapshot)
        self.rows = int(rows)
        self.state = str(state)

    @property
    def dim(self) -> int:
        return int(self.mu.shape[0])

    def split(self, V: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        """`Z` 를 방향 `u` 와 반지름 `r` 로 쪼갠다. 적재가 쓰는 쪽이다.

        `u` 는 `recommender.center()` 와 **같은 식**이다. 둘이 갈리면 순위가 갈린다.
        """
        W = np.atleast_2d(np.asarray(V, dtype=np.float32))
        if W.shape[1] != self.dim:
            raise DimensionMismatch(
                f"벡터 차원 {W.shape[1]} 이 평균 {self.dim} 과 다르다")
        W = W - self.mu
        r = np.linalg.norm(W, axis=1)
        W = W / np.clip(r, 1e-9, None)[:, None]
        return W, r.astype(np.float32)

    def apply(self, V: np.ndarray) -> np.ndarray:
        """질의를 후보와 같은 공간으로. `split` 의 방향 성분만 쓴다."""
        return self.split(V)[0]

    def restore(self, U: np.ndarray, r: np.ndarray) -> np.ndarray:
        """`Z = mu + r · u`. **원본을 따로 저장하지 않는 이유가 이 한 줄이다.**"""
        W = np.atleast_2d(np.asarray(U, dtype=np.float32))
        radius = np.asarray(r, dtype=np.float32).reshape(-1, 1)
        return (self.mu + radius * W).astype(np.float32)

    def stats(self) -> dict:
        return {"dim": self.dim, "rows": self.rows,
                "embedding_version": self.embedding_version,
                "snapshot": self.snapshot, "state": self.state}

    def require_snapshot(self, want: str) -> None:
        """control 의 세대가 지금 판과 같은가.

        **벡터만 바뀐 재분석도 잡는다** — 그것이 id 목록이 아니라 값까지 담는 이유다.
        """
        if not self.snapshot:
            return
        if want != self.snapshot:
            raise GenerationMismatch(
                f"control 의 적재본({self.snapshot[:12]}…, {self.rows}건)과 지금 판의 "
                f"적재본({want[:12]}…)이 다르다. 추천을 멈춘 채 "
                f"`pinecone_sync --reload` 로 전체를 다시 올린다")


def snapshot_hash(moment_ids, vectors, embedding_version: str,
                  pool_sources) -> str:
    """이 적재본의 지문. **id 목록만으로는 모자란다.**

    재분석으로 같은 `external_id` 의 **벡터만** 바뀌는 경우가 흔하다. id 만 세면
    그때 지문이 같게 나와, 옛 평균과 새 벡터가 섞인 상태를 아무도 잡지 못한다 —
    오류 없이 유사도만 내려앉는다. 그래서 값까지 넣는다.

        정렬된 external_id   구성이 같으면 차례가 달라도 같아야 한다
        벡터 값              float32 바이트. 한 구간만 바뀌어도 달라진다
        임베딩 버전          모델·층·풀링이 바뀌면 같은 값이라도 뜻이 다르다
        추천 풀 설정         MUPID_POOL / MUPID_POOL_EXTRA. 대상이 달라진다
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
    for position in sorted(range(len(ids)), key=lambda i: ids[i]):
        # 구분자를 넣는다. 넣지 않으면 `A`+`BC` 와 `AB`+`C` 가 같은 지문이 된다.
        h.update(ids[position].encode("utf-8") + b"\x00")
        h.update(np.ascontiguousarray(V[position], dtype=np.float32).tobytes())
    return h.hexdigest()


_CENTER: Optional[Center] = None


def set_state(state: str) -> None:
    """control 의 상태만 바꾼다. 평균·세대는 그대로 둔다.

    `UPDATING` 으로 바꾸면 그 순간부터 서빙이 503 을 돌려준다. `READY` 는
    **대사와 AI 서버 반영 확인이 끝난 뒤에만** 부른다.
    """
    got = load_center()
    _write_center(got.mu, got.embedding_version, got.snapshot, got.rows, state)
    forget_center()


def _write_center(mu, embedding_version: str, snapshot: str, rows: int,
                  state: str) -> None:
    values = [float(x) for x in np.asarray(mu, dtype=np.float32).reshape(-1)]
    _wrap("control upsert", _index().upsert,
          vectors=[{"id": CONTROL_ID, "values": values,
                    "metadata": {"embedding_version": str(embedding_version),
                                 "snapshot": str(snapshot), "rows": int(rows),
                                 "dim": len(values), STATE_KEY: str(state)}}],
          namespace=control_namespace())


def save_center(mu: np.ndarray, embedding_version: str, snapshot: str,
                rows: int, state: str = READY) -> None:
    """평균과 세대를 control namespace 에 쓴다. **파일로 떨구지 않는다.**

    파일은 판마다 따로 움직여서, 인덱스만 갈아 끼우고 파일은 그대로인 상태가
    생긴다. 같은 인덱스·같은 갱신에 두면 그 자리가 없어진다.
    """
    _write_center(mu, embedding_version, snapshot, rows, state)
    forget_center()


def load_center() -> Center:
    """control 의 평균. 없으면 **멈춘다.**

    여기서 즉석 평균으로 대신하면 적재 때와 다른 공간으로 질의가 나가 유사도가
    통째로 내려앉는다 — 오류 없이 품질만 나빠진다.
    """
    body = _wrap("control fetch", _index().fetch,
                 ids=[CONTROL_ID], namespace=control_namespace())
    got = _vectors(body).get(CONTROL_ID)
    if got is None:
        raise PineconeError(
            f"control namespace({control_namespace()!r})에 {CONTROL_ID} 가 없다 — "
            f"평균 없이는 질의를 적재와 같은 공간으로 보낼 수 없다. "
            f"`python -m ai_server.tools.pinecone_sync --reload` 가 만든다")
    mu, meta = _record(got)
    if mu is None:
        raise PineconeError(f"{CONTROL_ID} 에 벡터가 없다")
    return Center(mu, meta.get("embedding_version", ""),
                  meta.get("snapshot", ""), int(meta.get("rows", 0) or 0),
                  str(meta.get(STATE_KEY) or READY))


def center(reload: bool = False) -> Center:
    """평균을 들고 있는다. **읽을 때마다 control 을 한 번 본다.**

    한 레코드를 id 로 가져오는 것이라 전체 목록을 훑지 않는다. 이 한 번이
    "배치가 μ 를 갈았는데 서버는 옛 μ 로 계속 돈다" 를 막는다 — 그 상태는 오류
    없이 추천 품질만 틀어진다.
    """
    global _CENTER
    if _CENTER is None or reload:
        _CENTER = load_center()
    return _CENTER


def serving_center() -> Center:
    """추천 경로가 부르는 자리. 상태를 보고 필요하면 μ 를 갈아 끼운다.

        UPDATING              → ServingPaused (503). 로컬로 내려가지 않는다
        READY + 같은 snapshot → 들고 있던 μ 를 그대로 쓴다
        READY + 다른 snapshot → **새 μ 를 읽어 캐시를 바꾼 뒤** 추천한다

    `UPDATING` 일 때도 캐시를 갱신한다. 배치가 "서버가 새 세대를 읽었는가" 를
    확인한 뒤에 `READY` 로 넘어가기 때문이다 — 안 갱신하면 서로 기다리다 멈춘다.
    """
    global _CENTER
    fresh = load_center()
    if _CENTER is None or fresh.snapshot != _CENTER.snapshot:
        _CENTER = fresh
    else:
        _CENTER.state = fresh.state
    if fresh.state == UPDATING:
        raise ServingPaused(
            "순간 벡터를 갱신하는 중이다 — 갱신이 끝나고 대사를 통과해야 재개된다")
    return _CENTER


def forget_center() -> None:
    global _CENTER
    _CENTER = None


# ------------------------------------------------------------------ 데이터 평면
def upsert(ids: Sequence[str], vectors: np.ndarray,
           center_: Optional[Center] = None) -> int:
    """원본 `Z` 를 방향과 반지름으로 쪼개 올린다. 같은 ID 면 **교체**된다.

    DB 소유 메타데이터를 싣지 않는다 — 실리는 것은 `r` 하나이고, 그것은 벡터를
    쪼갠 값이지 DB 가 아는 사실이 아니다.
    """
    V = np.atleast_2d(np.asarray(vectors, dtype=np.float32))
    if len(ids) != len(V):
        raise ValueError(f"id {len(ids)}개와 벡터 {len(V)}개가 다르다")
    if not len(ids):
        return 0
    got = center_ or center()
    U, radius = got.split(V)
    index, ns = _index(), namespace()
    for start in range(0, len(ids), UPSERT_BATCH):
        chunk = [{"id": str(i), "values": [float(x) for x in u],
                  "metadata": {RADIUS_KEY: float(r)}}
                 for i, u, r in zip(ids[start:start + UPSERT_BATCH],
                                    U[start:start + UPSERT_BATCH],
                                    radius[start:start + UPSERT_BATCH])]
        _wrap("upsert", index.upsert, vectors=chunk, namespace=ns)
    return len(ids)


def delete(ids) -> int:
    """삭제·비추천 전환된 순간을 지운다.

    없는 ID 를 지우는 것은 오류가 아니다 — 재실행을 안전하게 만들려고 그렇게 둔다.
    """
    todo = [str(i) for i in ids]
    if not todo:
        return 0
    index, ns = _index(), namespace()
    for start in range(0, len(todo), DELETE_BATCH):
        _wrap("delete", index.delete,
              ids=todo[start:start + DELETE_BATCH], namespace=ns)
    return len(todo)


def delete_all() -> None:
    """데이터 namespace 를 비운다. **control 은 건드리지 않는다.**

    전체 갱신의 첫 단계다. 지우고 다시 넣으면 잔존이 남을 자리가 없다.
    """
    _wrap("delete_all", _index().delete, delete_all=True, namespace=namespace())


def search(query_vector: np.ndarray, top_k: int) -> list[tuple[str, float]]:
    """닮은 순간 후보. 질의는 **이미 중심화된** 벡터여야 한다.

    `include_metadata` 를 켜지 않는다. 후보 검색에 `r` 이 필요하지 않고, 켜 두면
    나중에 누군가 metadata 를 읽는 쪽으로 되돌리기 쉬워진다.
    """
    q = np.asarray(query_vector, dtype=np.float32).reshape(-1)
    body = _wrap("query", _index().query,
                 vector=[float(x) for x in q], top_k=int(top_k),
                 namespace=namespace(), include_values=False,
                 include_metadata=False)
    out: list[tuple[str, float]] = []
    for m in _matches(body):
        mid = m.get("id")
        if mid is not None:
            out.append((str(mid), float(m.get("score", 0.0))))
    return out


def fetch_raw(ids: Sequence[str]) -> dict:
    """원본 `Z` 를 되돌려 준다. **raw 전용 인덱스를 두지 않는 자리다.**

        Z = mu + r · u

    DNA 군집과 온보딩 조건별 평균이 쓴다. 둘 다 "닮은 것 찾기" 가 아니라 "이
    구간들의 평균" 이라 ANN 질의로 표현되지 않는다.

    `r` 이 없는 레코드는 **건너뛴다.** 0 으로 채우면 평균이 조용히 평균 벡터 쪽으로
    끌려간다 — 없는 것을 지어내지 않는다.
    """
    todo = [str(i) for i in ids]
    if not todo:
        return {}
    got = center()
    index, ns = _index(), namespace()
    out: dict = {}
    for start in range(0, len(todo), FETCH_BATCH):
        body = _wrap("fetch", index.fetch,
                     ids=todo[start:start + FETCH_BATCH], namespace=ns)
        for vid, raw in _vectors(body).items():
            u, meta = _record(raw)
            if u is None or meta.get(RADIUS_KEY) is None:
                continue
            out[str(vid)] = got.restore(u, float(meta[RADIUS_KEY]))[0]
    return out


def stats() -> dict:
    """차원과 namespace 별 벡터 수."""
    body = _wrap("describe_index_stats", _index().describe_index_stats)
    data = _as_dict(body)
    counts = data.get("namespaces") or {}
    mine = _as_dict(counts.get(namespace()) or {})
    control = _as_dict(counts.get(control_namespace()) or {})
    return {"index": index_name(),
            "dimension": int(data["dimension"]) if data.get("dimension") is not None else None,
            "namespace": namespace(),
            "vectors_in_namespace": int(mine.get("vector_count")
                                        or mine.get("vectorCount") or 0),
            "control_namespace": control_namespace(),
            "vectors_in_control": int(control.get("vector_count")
                                      or control.get("vectorCount") or 0),
            "vectors_total": int(data.get("total_vector_count")
                                 or data.get("totalVectorCount") or 0)}


def check_dimension(expected: int) -> dict:
    """인덱스 차원이 판의 벡터 차원과 같은가.

    같지 않으면 **적재도 검색도 하지 않는다.** 차원이 다르면 upsert 가 건건이
    거절되고, 그 실패가 배치 로그에만 쌓여 "왜 추천이 비나" 로 뒤늦게 드러난다.
    차원은 만든 뒤 바꿀 수 없다.
    """
    got = stats()
    actual = got.get("dimension")
    if actual is None:
        raise PineconeError("인덱스에서 dimension 을 읽지 못했다")
    if int(actual) != int(expected):
        raise DimensionMismatch(
            f"인덱스({index_name()}) 차원이 {actual} 인데 판의 순간 벡터는 "
            f"{expected} 다. 차원은 만든 뒤 바꿀 수 없다 — dimension={expected} 인 "
            f"인덱스를 새로 만든다")
    return got


def list_ids() -> list[str]:
    """데이터 namespace 안의 ID 전체. 대사(對査)에 쓴다.

    운영 요청 경로가 아니라 도구에서만 부른다 — 1만 구간이면 페이지를 여러 번 넘긴다.
    """
    out: list[str] = []
    for page in _wrap("list", _index().list, namespace=namespace()):
        for vid in (page if isinstance(page, (list, tuple))
                    else getattr(page, "ids", []) or []):
            out.append(str(vid.get("id") if isinstance(vid, dict) else vid))
    return out
