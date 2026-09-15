"""`stage1.pt` → `stage1_weights.npz`.

서빙 이미지에서 torch 를 걷어내기 위한 **한 번 돌리는 도구**다. 이 스크립트 자체는 torch 가
필요하지만 이미지에 들어가지 않는다 — 산출물을 만드는 쪽에서 돌린다.

    MUPID_AI_ARTIFACT_DIR=/경로/artifacts python -m ai_server.tools.export_stage1_npz

만들어진 `stage1_weights.npz` 를 `data/proc/mupid/` 에 두면 서비스가 그쪽을 먼저 쓴다.
파일이 없으면 종전대로 `stage1.pt` 를 torch 로 읽는다.

내보낸 뒤 **같은 값이 나오는지 직접 확인한다.** 무작위 입력으로 torch 판과 NumPy 판을
비교해 최대 오차와 순위 일치를 출력한다. 기준은 오차 < 1e-5, 순위 완전 일치다.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app" / "legacy"))

from ai_server.app import paths, stage1_np  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", default=None, help="기본: <ARTIFACT_DIR>/.../stage1.pt")
    ap.add_argument("--out", default=None, help="기본: 같은 자리에 stage1_weights.npz")
    ap.add_argument("--trials", type=int, default=64, help="동치 확인 표본 수")
    a = ap.parse_args()

    import torch

    import m11_stage1 as s1

    data = paths.data_dir()
    ckpt = Path(a.ckpt) if a.ckpt else data / "stage1.pt"
    out = Path(a.out) if a.out else data / "stage1_weights.npz"
    if not ckpt.exists():
        print(f"체크포인트가 없다: {ckpt}", file=sys.stderr)
        return 2

    ck = torch.load(ckpt, map_location="cpu", weights_only=True)
    model = s1.make_model(ck["in_dim"])
    model.load_state_dict(ck["state"])
    model.eval()

    w = stage1_np.from_state_dict(model.state_dict(), model.gmean.detach().cpu().numpy(),
                                  ck["in_dim"])
    w["hist_max"] = np.asarray(int(ck["hist_max"]), dtype=np.int64)
    w["in_dim"] = np.asarray(int(ck["in_dim"]), dtype=np.int64)
    if ck.get("half_life_days") is not None:
        w["half_life_days"] = np.asarray(float(ck["half_life_days"]), dtype=np.float64)
    if ck.get("feature_version") is not None:
        w["feature_version"] = np.asarray(str(ck["feature_version"]))

    enc = stage1_np.UserEncoder({k: v for k, v in w.items() if k in stage1_np.KEYS})

    # ---- 동치 확인. 통과하지 못하면 파일을 쓰지 않는다 ----
    rng = np.random.default_rng(0)
    hist_max, in_dim = int(ck["hist_max"]), int(ck["in_dim"])
    worst, mismatched = 0.0, 0
    for _ in range(a.trials):
        n = int(rng.integers(1, hist_max + 1))
        xh = rng.normal(size=(hist_max, in_dim)).astype(np.float32)
        mask = np.zeros(hist_max, dtype=bool)
        mask[:n] = True
        logw = np.log(np.clip(rng.random(hist_max), 1e-3, None)).astype(np.float32)

        with torch.no_grad():
            zt = model.enc_user(torch.tensor(xh)[None], torch.tensor(mask)[None],
                                logw=torch.tensor(logw)[None]).numpy()[0]
        zn = enc.encode(xh, mask, logw)
        worst = max(worst, float(np.abs(zt - zn).max()))
        # 이 벡터는 후보 행렬과의 내적으로만 쓰인다. 순위가 같은지가 최종 기준이다.
        cand = rng.normal(size=(200, zt.shape[0])).astype(np.float32)
        if not np.array_equal(np.argsort(-(cand @ zt)), np.argsort(-(cand @ zn))):
            mismatched += 1

    print(f"최대 절대 오차 {worst:.3e} · 순위 불일치 {mismatched}/{a.trials}")
    if worst >= 1e-5 or mismatched:
        print("동치 확인 실패 — 파일을 쓰지 않는다.", file=sys.stderr)
        return 1

    np.savez(out, **w)
    print(f"저장 {out} ({out.stat().st_size / 1024:.0f}KB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
