"""Stage 1 사용자 인코더의 NumPy 판.

**서빙에서 torch 가 하는 일은 `enc_user` 한 번뿐이다.** 구간 벡터는 이미
`stage1_section.npy` 로 계산돼 있고, 점수는 그 행렬과의 내적이다. 그 한 번을 위해 CPU
빌드라도 수백 MB 인 torch 를 이미지에 넣는다.

여기서는 그 한 함수만 옮긴다. 학습·내보내기는 그대로 torch 로 한다 — 그쪽은 이미지에
들어가지 않는다.

**정확히 같아야 한다.** 근사 GELU 를 쓰면 안 된다. `nn.GELU()` 의 기본은 erf 판이고,
tanh 근사를 쓰면 4번째 자리부터 갈라져 순위가 바뀐다. scipy 는 이미 의존성에 있다.

가중치는 `stage1_weights.npz` 에서 읽는다. `tools/export_stage1_npz.py` 가 `stage1.pt`
에서 만든다. 파일이 없으면 이 모듈은 아무 일도 하지 않고 서비스는 torch 경로로 간다.
"""
from __future__ import annotations

from pathlib import Path
from typing import Optional

import numpy as np

# `stage1.pt` 에서 뽑아야 하는 텐서들. 이름이 곧 내보내기 계약이다.
KEYS = ("enc0_w", "enc0_b", "ln_w", "ln_b", "enc1_w", "enc1_b",
        "att0_w", "att0_b", "att1_w", "att1_b", "gmean")
LN_EPS = 1e-5           # nn.LayerNorm 기본값


def _gelu(x: np.ndarray) -> np.ndarray:
    """nn.GELU() 기본(approximate='none')과 같은 erf 판.

    scipy 는 이 저장소의 관례대로 **쓰는 자리에서** 불러온다. 모듈 최상단에서 들이면
    npz 가 없어 이 경로를 아예 쓰지 않는 배포에서도 import 시점에 scipy 를 요구하게 된다.
    """
    from scipy.special import erf

    return x * 0.5 * (1.0 + erf(x / np.sqrt(2.0, dtype=np.float64)))


def _layer_norm(x: np.ndarray, w: np.ndarray, b: np.ndarray) -> np.ndarray:
    mu = x.mean(-1, keepdims=True)
    # torch 의 LayerNorm 은 **편향 없는 분산이 아니라** 모집단 분산을 쓴다.
    var = ((x - mu) ** 2).mean(-1, keepdims=True)
    return (x - mu) / np.sqrt(var + LN_EPS) * w + b


def _l2(x: np.ndarray, eps: float = 1e-12) -> np.ndarray:
    n = np.linalg.norm(x, axis=-1, keepdims=True)
    return x / np.maximum(n, eps)


class UserEncoder:
    """`enc_user` 만 담는다. 학습도 구간 인코딩도 하지 않는다."""

    def __init__(self, w: dict[str, np.ndarray]) -> None:
        missing = [k for k in KEYS if k not in w]
        if missing:
            raise ValueError(f"가중치가 없다: {missing}")
        self.w = {k: np.asarray(w[k], dtype=np.float32) for k in KEYS}
        self.dim = int(self.w["gmean"].shape[0])
        self.in_dim = int(self.w["enc0_w"].shape[1])

    def _enc(self, x: np.ndarray) -> np.ndarray:
        w = self.w
        h = x @ w["enc0_w"].T + w["enc0_b"]
        h = _layer_norm(h, w["ln_w"], w["ln_b"])
        h = _gelu(h)
        return h @ w["enc1_w"].T + w["enc1_b"]

    def encode(self, xh: np.ndarray, mask: np.ndarray,
               logw: Optional[np.ndarray] = None) -> np.ndarray:
        """xh (M, in_dim) · mask (M,) · logw (M,) → (dim,)

        torch 판과 같은 순서로 계산한다: enc → attention(+logw) → mask → softmax →
        가중합 → gmean 빼기 → L2.
        """
        w = self.w
        h = self._enc(np.asarray(xh, dtype=np.float32))
        a = (np.tanh(h @ w["att0_w"].T + w["att0_b"]) @ w["att1_w"].T
             + w["att1_b"]).squeeze(-1)
        if logw is not None:
            a = a + np.asarray(logw, dtype=np.float32)
        # masked_fill(~mask, -1e9) 와 같은 값을 쓴다. -inf 로 바꾸면 전부 가려진 입력에서
        # softmax 가 NaN 이 되어 torch 판과 갈라진다.
        a = np.where(np.asarray(mask, dtype=bool), a, -1e9)
        a = a - a.max()
        e = np.exp(a)
        a = e / e.sum()
        z = (h * a[:, None]).sum(0) - w["gmean"]
        return _l2(z).astype(np.float32)


def load(path: Path) -> Optional[UserEncoder]:
    """없으면 None. 서비스는 torch 경로로 간다."""
    if not Path(path).exists():
        return None
    with np.load(path) as d:
        return UserEncoder({k: d[k] for k in d.files})


def from_state_dict(state, gmean, in_dim: int) -> dict[str, np.ndarray]:
    """torch state_dict → npz 로 쓸 배열들. 내보내기 도구와 테스트가 함께 쓴다."""
    def g(name):
        return np.asarray(state[name].detach().cpu().numpy(), dtype=np.float32)

    out = {
        "enc0_w": g("enc.0.weight"), "enc0_b": g("enc.0.bias"),
        "ln_w": g("enc.1.weight"), "ln_b": g("enc.1.bias"),
        "enc1_w": g("enc.3.weight"), "enc1_b": g("enc.3.bias"),
        "att0_w": g("att.0.weight"), "att0_b": g("att.0.bias"),
        "att1_w": g("att.2.weight"), "att1_b": g("att.2.bias"),
        "gmean": np.asarray(gmean, dtype=np.float32),
    }
    if out["enc0_w"].shape[1] != in_dim:
        raise ValueError(f"입력 차원 불일치: {out['enc0_w'].shape[1]} vs {in_dim}")
    return out
