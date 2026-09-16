"""신곡 구간을 **기존 특징 공간으로 옮기기만** 한다 (transform-only).

    X = [ MERT PCA 256 (동결 기저) | side 29 ]   = 285차

정본은 `ai_server/app/legacy/m10_features.py` 의 `fit()` 이다. 여기서는 그 계산을
**적합 없이** 다시 밟는다.

    P = (moments_emb - pca_mean) @ pca_comp
    P = P / ||P||          ← 행별 L2. 이 단계를 빼면 오차가 0.119 까지 벌어진다
    X = concat([P, side])

## 이 파일이 하지 않는 것

**PCA 를 적합하지 않는다.** `pca_mean`·`pca_comp` 를 읽어 쓰기만 한다. 적합 코드가 이
경로에 들어오면 기존 구간의 벡터가 통째로 무효가 되고, `stage1_weights.npz` 의
`in_dim 285` 와 어긋나 서비스가 조용히 규칙 점수로 내려앉는다.

**z-score 통계를 스스로 계산하지 않는다.** `feature_stats_v1.npz` 가 없으면 오류로
멈춘다 — 값이 조금 다른 특징이 섞이면 추천만 이상해지고 원인이 응답에 드러나지 않는다.
통계는 `ai_server/tools/freeze_feature_stats.py` 가 만든다.

## 왜 여기에 있는가

쓰는 쪽이 전처리 배치다. 서빙은 이 변환을 쓰지 않는다 — 이미 계산된 색인을 읽을 뿐이다.
`ai_server` 에는 왕복 검증 테스트만 둔다.
"""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Optional, Sequence

import numpy as np

FEATURES_NAME = "features_v1.npz"
STATS_NAME = "feature_stats_v1.npz"
INFO_NAME = "features_info.json"
WEIGHTS_NAME = "stage1_weights.npz"

LN_EPS = 1e-5           # nn.LayerNorm 기본값

# 구간 타워가 쓰는 가중치. 사용자 인코더의 주의(att*)·전역 평균(gmean)은 쓰지 않는다.
ENC_KEYS = ("enc0_w", "enc0_b", "ln_w", "ln_b", "enc1_w", "enc1_b")


class ArtifactMissing(RuntimeError):
    """동결 산출물이 없거나 서로 다른 판이다. 조용히 넘어가지 않는다."""


@dataclass
class Section:
    """구간 하나. 값의 정의는 `moments.parquet` 의 같은 이름 열과 같다."""

    rel_start: float
    rel_end: float
    dur_sec: float
    rms: float
    section_label: str


@dataclass
class TrackStats:
    """트랙 레벨 5값. `content.parquet` 의 같은 이름 열과 같은 정의여야 한다."""

    bpm: float
    centroid: float
    rolloff: float
    rms_mean: float
    flatness: float

    def as_list(self) -> list[float]:
        return [self.bpm, self.centroid, self.rolloff, self.rms_mean, self.flatness]


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for blk in iter(lambda: f.read(1 << 20), b""):
            h.update(blk)
    return h.hexdigest()


class FeatureSpace:
    """동결된 기저와 통계를 한 번만 읽어 들고 있는다.

    **열 이름을 코드에 박지 않는다.** 순서까지 포함한 스키마는 동결 파일이 정본이고,
    여기서는 그것을 읽어 해석만 한다. 양쪽에 적어 두면 한쪽만 고쳐도 오차 없이 엉뚱한
    열이 모델에 들어간다.
    """

    def __init__(self, data_dir: Path, *, verify_hash: bool = True) -> None:
        d = Path(data_dir)
        feat, stats, info = d / FEATURES_NAME, d / STATS_NAME, d / INFO_NAME

        if not feat.exists():
            raise ArtifactMissing(f"PCA 기저가 없다: {feat}")
        if not stats.exists():
            raise ArtifactMissing(
                f"동결 통계가 없다: {stats}\n"
                f"  python -m ai_server.tools.freeze_feature_stats 로 만든다.\n"
                f"  없는 채로 계산하면 신곡만 다른 분포의 특징을 갖게 된다.")

        with np.load(feat, allow_pickle=False) as fz:
            self.pca_mean = np.asarray(fz["pca_mean"], dtype=np.float32)
            self.pca_comp = np.asarray(fz["pca_comp"], dtype=np.float32)
            file_cols = [str(x) for x in fz["side_cols"]]

        with np.load(stats, allow_pickle=False) as sz:
            keys = [str(x) for x in sz["keys"]]
            self.z_mean = {k: float(v) for k, v in zip(keys, sz["mean"])}
            self.z_std = {k: float(v) for k, v in zip(keys, sz["std"])}
            self.side_cols = [str(x) for x in sz["side_cols"]]
            self.feature_version = str(sz["feature_version"])

        if self.side_cols != file_cols:
            raise ArtifactMissing(
                "side 열 순서가 어긋난다 — 특징과 통계가 다른 판이다.\n"
                f"  특징 {file_cols}\n  통계 {self.side_cols}")

        self._check_info(info, feat, verify_hash)

        # 스키마를 열 이름에서 되읽는다 (코드에 목록을 두지 않는다).
        self.pos_bins = sum(1 for c in self.side_cols if c.startswith("pos"))
        self.labels = [c[4:] for c in self.side_cols if c.startswith("lab_")]
        self.track_cols = [c[4:] for c in self.side_cols if c.startswith("trk_")]
        if "other" not in self.labels:
            raise ArtifactMissing("라벨 목록에 other 가 없다 — 떨어뜨릴 자리가 없다")

        self.side_dim = len(self.side_cols)
        self.pca_dim = int(self.pca_comp.shape[1])
        self.dim = self.pca_dim + self.side_dim
        self._weights = d / WEIGHTS_NAME
        self._w: Optional[dict] = None

    def _check_info(self, info: Path, feat: Path, verify_hash: bool) -> None:
        """`features_info.json` 의 판 정보와 대조한다. 어긋나면 **오류로 멈춘다.**

        경고로 두면 다른 판의 기저로 계산한 특징이 섞인 채 배치가 성공으로 끝난다.
        그 상태는 추천 품질로만 드러나고 원인은 응답 어디에도 남지 않는다.
        """
        if not info.exists():
            raise ArtifactMissing(f"판 정보가 없다: {info}")
        d = json.loads(info.read_text(encoding="utf-8"))

        want = d.get("feature_version")
        if want and want != self.feature_version:
            raise ArtifactMissing(
                f"feature_version 불일치 — 통계 {self.feature_version} vs "
                f"정보 {want}")

        recorded = d.get("features_sha256")
        if not recorded:
            raise ArtifactMissing(
                f"{INFO_NAME} 에 features_sha256 이 없다 — "
                f"freeze_feature_stats 를 다시 돌린다")
        if verify_hash:
            actual = sha256(feat)
            if actual != recorded:
                raise ArtifactMissing(
                    f"{FEATURES_NAME} 이 통계를 뽑을 때와 다른 파일이다.\n"
                    f"  기록 {recorded[:16]}…  실제 {actual[:16]}…\n"
                    f"  색인을 다시 만들었다면 freeze_feature_stats 도 다시 돌린다.")

    # ------------------------------------------------------------------ PCA

    def pca(self, emb: np.ndarray) -> np.ndarray:
        """1536차 MERT 임베딩 → 256차. 적합하지 않고 **적용만** 한다."""
        E = np.atleast_2d(np.asarray(emb, dtype=np.float32))
        if E.shape[1] != self.pca_mean.shape[0]:
            raise ValueError(
                f"임베딩 차원이 다르다: {E.shape[1]} vs {self.pca_mean.shape[0]}")
        P = (E - self.pca_mean) @ self.pca_comp
        # 행별 L2. `fit()` 이 하는 것과 같은 자리다 — 빼면 조용히 크게 어긋난다.
        return P / np.clip(np.linalg.norm(P, axis=1, keepdims=True), 1e-9, None)

    # ----------------------------------------------------------------- side

    def _z(self, key: str, v: np.ndarray) -> np.ndarray:
        """동결 통계로만 정규화한다. 입력에서 평균을 다시 잡지 않는다."""
        return np.nan_to_num(
            (np.asarray(v, dtype=np.float64) - self.z_mean[key]) / self.z_std[key])

    def side(self, sections: Sequence[Section], track: TrackStats) -> np.ndarray:
        """한 곡의 구간들 → (n, 29).

        `rms_pct` 는 **곡 안에서의 순위**다. 동결 대상이 아니라 신곡에서도 그대로
        계산된다 — 원본이 `groupby("track_id").rank(pct=True)` 로 곡 단위로 매긴다.
        """
        n = len(sections)
        if n == 0:
            return np.zeros((0, self.side_dim), dtype=np.float32)

        rel_start = np.array([s.rel_start for s in sections], dtype=np.float64)
        rel_end = np.array([s.rel_end for s in sections], dtype=np.float64)
        rel_c = (rel_start + rel_end) / 2.0
        dur = np.array([s.dur_sec for s in sections], dtype=np.float64)
        rms = np.array([s.rms for s in sections], dtype=np.float64)

        blocks = [rel_start, rel_end, rel_c, np.log1p(dur),
                  np.nan_to_num(rank_pct(rms), nan=0.5), self._z("rms", rms)]

        pb = np.clip((rel_c * self.pos_bins).astype(int), 0, self.pos_bins - 1)
        onehot_pos = np.zeros((n, self.pos_bins))
        onehot_pos[np.arange(n), pb] = 1.0

        idx = {L: i for i, L in enumerate(self.labels)}
        onehot_lab = np.zeros((n, len(self.labels)))
        for i, s in enumerate(sections):
            # 목록에 없는 라벨은 other 로 떨어뜨린다 (기존 규칙 그대로).
            onehot_lab[i, idx.get(str(s.section_label), idx["other"])] = 1.0

        vals = dict(zip(self.track_cols, track.as_list()))
        tvals = [self._z(f"trk_{c}", np.full(n, float(vals[c]))) for c in self.track_cols]

        X = np.column_stack(blocks + [onehot_pos, onehot_lab] + tvals)
        return np.nan_to_num(X).astype(np.float32)

    # ------------------------------------------------------------- 합치기

    def transform(self, sections: Sequence[Section], emb: np.ndarray,
                  track: TrackStats) -> np.ndarray:
        """구간 메타 + MERT 임베딩 + 트랙 5값 → (n, 285) float32."""
        P = self.pca(emb)
        if len(P) != len(sections):
            raise ValueError(f"구간 {len(sections)}개인데 임베딩은 {len(P)}행이다")
        return np.concatenate([P, self.side(sections, track)], axis=1).astype(np.float32)

    # ------------------------------------------------------- 구간 타워 인코딩

    def enc_sec(self, X: np.ndarray) -> np.ndarray:
        """(n, 285) → (n, dim). `stage1_section.npy` 한 행과 같은 값이다.

        구간 타워는 `enc` 를 태우고 L2 로 맞추는 것이 전부다 — 사용자 인코더처럼 주의·
        전역 평균 빼기가 붙지 않는다. **torch 가 필요 없다.**

        수식은 `ai_server/app/stage1_np.py` 의 `_enc`·`_l2` 와 같아야 한다. 두 벌이
        갈라지지 않도록, 저장된 `stage1_section.npy` 와 대조하는 시험이
        `ai_server/tests/test_feature_transform.py` 에 있다.
        """
        w = self._weights_dict()
        A = np.atleast_2d(np.asarray(X, dtype=np.float32))
        if A.shape[1] != w["enc0_w"].shape[1]:
            raise ValueError(
                f"입력 차원이 다르다: {A.shape[1]} vs {w['enc0_w'].shape[1]}")
        h = A @ w["enc0_w"].T + w["enc0_b"]
        h = _layer_norm(h, w["ln_w"], w["ln_b"])
        h = _gelu(h)
        h = h @ w["enc1_w"].T + w["enc1_b"]
        return _l2(h).astype(np.float32)

    def _weights_dict(self) -> dict:
        """구간 타워에 쓰는 가중치만 읽는다.

        `stage1_weights.npz` 에는 `hist_max`·`in_dim`·`feature_version` 처럼 숫자가
        아닌 항목도 들어 있다. 전부 float 로 바꾸려 들면 거기서 터진다.
        """
        if self._w is None:
            if not self._weights.exists():
                raise ArtifactMissing(f"랭킹 가중치가 없다: {self._weights}")
            with np.load(self._weights, allow_pickle=False) as d:
                missing = [k for k in ENC_KEYS if k not in d.files]
                if missing:
                    raise ArtifactMissing(f"가중치가 없다: {missing}")
                self._w = {k: np.asarray(d[k], dtype=np.float32) for k in ENC_KEYS}
                want = str(d["feature_version"]) if "feature_version" in d.files else ""
            if want and want != self.feature_version:
                raise ArtifactMissing(
                    f"가중치의 feature_version {want} 이 특징 판 "
                    f"{self.feature_version} 과 다르다")
            got = int(self._w["enc0_w"].shape[1])
            if got != self.dim:
                raise ArtifactMissing(
                    f"체크포인트 입력 차원 {got} 과 특징 차원 {self.dim} 이 다르다 "
                    f"— 기저를 다시 적합한 흔적이다")
        return self._w


# ------------------------------------------------------------------ 수식 조각

def _gelu(x: np.ndarray) -> np.ndarray:
    """`nn.GELU()` 기본(approximate='none')과 같은 erf 판.

    tanh 근사를 쓰면 4번째 자리부터 갈라져 순위가 바뀐다.
    """
    from scipy.special import erf

    return x * 0.5 * (1.0 + erf(x / np.sqrt(2.0, dtype=np.float64)))


def _layer_norm(x: np.ndarray, w: np.ndarray, b: np.ndarray) -> np.ndarray:
    mu = x.mean(-1, keepdims=True)
    # torch 의 LayerNorm 은 편향 없는 분산이 아니라 **모집단 분산**을 쓴다.
    var = ((x - mu) ** 2).mean(-1, keepdims=True)
    return (x - mu) / np.sqrt(var + LN_EPS) * w + b


def _l2(x: np.ndarray, eps: float = 1e-12) -> np.ndarray:
    return x / np.maximum(np.linalg.norm(x, axis=-1, keepdims=True), eps)


def rank_pct(v: np.ndarray) -> np.ndarray:
    """pandas `rank(pct=True)` 와 같은 평균 순위 백분율. 동점은 평균으로 누른다."""
    v = np.asarray(v, dtype=np.float64)
    n = len(v)
    if n == 0:
        return v
    order = np.argsort(v, kind="mergesort")
    ranks = np.empty(n, dtype=np.float64)
    ranks[order] = np.arange(1, n + 1, dtype=np.float64)
    uniq, inv, cnt = np.unique(v, return_inverse=True, return_counts=True)
    sums = np.zeros(len(uniq), dtype=np.float64)
    np.add.at(sums, inv, ranks)
    return (sums / cnt)[inv] / n
