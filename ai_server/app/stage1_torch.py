"""모델 3 Stage 1 — Dual Encoder (검색용).

  구간 인코더 f(x_s) -> z_s ∈ R^128      사용자 무관 → 카탈로그 1회 인코딩 후 색인
  사용자 인코더 g({x_p}) -> z_u ∈ R^128  사용자의 L1 positive 구간 집합을 attention pooling
  점수 = cos(z_u, z_s)

설계 근거 (오늘 실측)
  · 사용자 ID 임베딩 테이블을 쓰지 않는다 — two-tower 가 12명 400step 에서
    사용자 간 후보 75% 중복으로 개인화에 실패했다
  · 사용자 벡터에서 전역 평균을 뺀다 — 규칙 DNA 가 사용자쌍 코사인 0.878 로 뭉쳤다
  · 구간 인코더를 사용자 히스토리에도 공유해 파라미터를 아낀다 (총 ~88k)

누출 차단
  ① 사용자 히스토리에서 대상 곡 구간 제거   ② 곡 분리 분할
  ③ 히스토리는 train 곡에서만               ④ PCA 는 train(FMA) 구간으로만 적합

  python -m ai_server.app.stage1_torch --selftest         # 누출 테스트만
  python -m ai_server.app.stage1_torch --train --steps 1500
  python -m ai_server.app.stage1_torch --export           # 카탈로그 z_s / 사용자 z_u 저장
"""
from __future__ import annotations

import argparse
import json
import sys
import time

import numpy as np
import pandas as pd

from . import features as feat
from . import paths

# 산출물 루트는 paths 한 곳에서만 계산한다. 예전에는 이 모듈이 따로 셈해서
# `MUPID_AI_ARTIFACT_DIR=` (빈 값) 일 때 recommender 와 다른 곳을 봤다 —
# os.environ.get 의 기본값은 빈 문자열을 덮지 않아 Path('') 가 되기 때문이다.
ROOT = paths.artifact_root()
OUT_DIR = ROOT / "data" / "proc" / "mupid"
CKPT = OUT_DIR / "stage1.pt"
SEC_VEC = OUT_DIR / "stage1_section.npy"
USER_VEC = OUT_DIR / "stage1_user.npz"
REPORT = OUT_DIR / "stage1_report.json"

DIM = 128
HIST_MAX = 32
VAL_FRAC = 0.2
TAU = 0.05
SEED = 0
HALF_LIFE_DAYS = 0.0   # 0 = 감쇠 없음 (--half-life 로 지정)


# ------------------------------------------------------------------ 데이터
def load_all(pool: str = "fma"):
    X, idx, info = feat.load()
    mom = pd.read_parquet(OUT_DIR / "moments.parquet")
    if "source" not in mom.columns:
        mom["source"] = "fma"
    lab = pd.read_parquet(OUT_DIR / f"labels_{pool}.parquet")
    lab = lab[~lab.is_merged]                     # 병합 라벨은 재추출 전이라 제외
    lab = lab[lab.section_key.isin(idx)]
    return X, idx, info, mom, lab


def split_tracks(tracks: list[str], val_frac: float = VAL_FRAC) -> tuple[set, set]:
    rng = np.random.default_rng(SEED)
    t = np.array(sorted(tracks))
    perm = rng.permutation(len(t))
    n_val = max(1, int(len(t) * val_frac))
    return set(t[perm[n_val:]]), set(t[perm[:n_val]])


def build(pool: str = "fma"):
    X, idx, info, mom, lab = load_all(pool)
    tracks = sorted(lab.track_id.unique())
    tr_tracks, va_tracks = split_tracks(tracks)

    pos = lab[lab.grade.isin(("L1", "L2"))]
    neg = lab[lab.grade == "neg_strong"]
    hist: dict[str, dict[str, list[int]]] = {}       # user -> track -> [row]
    htime: dict[int, float] = {}                    # row -> epoch 초
    import datetime as _dt

    def _ts(v) -> float:
        try:
            return _dt.datetime.fromisoformat(str(v).replace("Z", "+00:00")).timestamp()
        except Exception:
            return 0.0

    for r in pos[pos.grade == "L1"].itertuples():
        if r.track_id in tr_tracks:
            row = idx[r.section_key]
            hist.setdefault(r.user_ref, {}).setdefault(r.track_id, []).append(row)
            htime[row] = max(htime.get(row, 0.0), _ts(getattr(r, "occurred_at", "")))
    t_max = max(htime.values()) if htime else 0.0

    train_ex, val_ex = [], []
    for r in pos.itertuples():
        e = {"user": r.user_ref, "track": r.track_id, "row": idx[r.section_key],
             "w": float(r.w), "grade": r.grade}
        (train_ex if r.track_id in tr_tracks else val_ex).append(e)
    neg_rows = [idx[r.section_key] for r in neg.itertuples() if r.track_id in tr_tracks]

    sec_by_track = {t: g.moment_id.tolist() for t, g in mom.groupby("track_id", sort=False)}
    rows_by_track = {t: [idx[k] for k in ks if k in idx] for t, ks in sec_by_track.items()}
    ages = [(t_max - v) / 86400.0 for v in htime.values() if v > 0]
    if ages:
        print(f"히스토리 나이(일): 중앙 {np.median(ages):.1f} / 최대 {max(ages):.1f}")
    return dict(X=X, idx=idx, info=info, mom=mom, lab=lab, hist=hist,
                train=train_ex, val=val_ex, neg_rows=neg_rows,
                tr_tracks=tr_tracks, va_tracks=va_tracks, sec_by_track=sec_by_track,
                rows_by_track=rows_by_track, pos=pos, htime=htime, t_max=t_max)


def user_history(D, user: str, exclude_track: str | None) -> list[int]:
    h = D["hist"].get(user, {})
    rows = [r for t, rs in h.items() if t != exclude_track for r in rs]
    if D.get("htime"):
        # 감쇠 on/off 와 무관하게 항상 최근순으로 자른다.
        # (정렬이 조건부면 baseline 입력까지 바뀌어 비교가 불공정해진다)
        rows.sort(key=lambda r: -D["htime"].get(r, 0.0))
    return rows[:HIST_MAX]


def history_weights(D, rows: list[int]) -> np.ndarray:
    """반감기 감쇠 가중치. HALF_LIFE_DAYS=0 이면 전부 1."""
    if HALF_LIFE_DAYS <= 0 or not D.get("htime"):
        return np.ones(len(rows), dtype=np.float32)
    tm = D["t_max"]
    age = np.array([(tm - D["htime"].get(r, tm)) / 86400.0 for r in rows], dtype=np.float64)
    w = np.power(0.5, np.clip(age, 0, None) / HALF_LIFE_DAYS)
    return np.maximum(w, 1e-3).astype(np.float32)


# ------------------------------------------------------------------ 누출 테스트
def selftest(D) -> int:
    ok = True

    def chk(name, cond, info=""):
        nonlocal ok
        ok = ok and bool(cond)
        print(f"[{'PASS' if cond else 'FAIL'}] {name}" + (f"  -> {info}" if info else ""))

    chk("① 곡 분리 분할", not (D["tr_tracks"] & D["va_tracks"]),
        f"train {len(D['tr_tracks'])}곡 / val {len(D['va_tracks'])}곡")
    bad = [e for e in D["val"] if e["track"] in D["tr_tracks"]]
    chk("② val 예시가 train 곡에 없음", not bad, f"위반 {len(bad)}건")
    tid_of_row = D["mom"].track_id.to_numpy()
    leak = 0
    for e in D["train"] + D["val"]:
        if any(tid_of_row[r] == e["track"] for r in user_history(D, e["user"], e["track"])):
            leak += 1
    chk("③ 히스토리에 대상 곡 구간 없음", leak == 0, f"위반 {leak}건")
    hv = [t for u in D["hist"] for t in D["hist"][u]]
    chk("④ 히스토리는 train 곡만", all(t in D["tr_tracks"] for t in hv), f"{len(hv)}건 확인")
    chk("⑤ PCA 적합 범위", D["info"]["pca_fit_on"] == "fma", D["info"]["pca_fit_on"])
    empty = sum(1 for e in D["val"] if not user_history(D, e["user"], e["track"]))
    # ⑥ 은 누출이 아니라 데이터 충분성 점검이므로 경고만 한다
    print(f"[INFO] 히스토리 없는 val 예시 {empty}/{len(D['val'])}건 (학습·평가에서 건너뜀)")
    return 0 if ok else 1


# ------------------------------------------------------------------ 모델
def make_model(in_dim: int):
    import torch
    import torch.nn as nn

    class Stage1(nn.Module):
        def __init__(self) -> None:
            super().__init__()
            self.enc = nn.Sequential(
                nn.Linear(in_dim, 192), nn.LayerNorm(192), nn.GELU(), nn.Linear(192, DIM))
            self.att = nn.Sequential(nn.Linear(DIM, 64), nn.Tanh(), nn.Linear(64, 1))
            self.register_buffer("gmean", torch.zeros(DIM))

        def enc_sec(self, x):
            return torch.nn.functional.normalize(self.enc(x), dim=-1)

        def enc_user(self, xh, mask, update: bool = False, logw=None):
            h = self.enc(xh)                                    # (B,M,DIM)
            a = self.att(h).squeeze(-1)
            if logw is not None:                                # 시간 감쇠: attention 에 곱
                a = a + logw
            a = a.masked_fill(~mask, -1e9).softmax(-1)
            z = (h * a.unsqueeze(-1)).sum(1)
            if update:
                with torch.no_grad():
                    self.gmean.mul_(0.99).add_(0.01 * z.detach().mean(0))
            z = z - self.gmean
            return torch.nn.functional.normalize(z, dim=-1)

    return Stage1()


# ------------------------------------------------------------------ 평가
def eval_hit(D, score_fn, pool_tracks: set, name: str) -> dict:
    """(user, track) 마다 그 곡의 모든 구간을 순위 매겨 L1 positive 를 맞히는지."""
    pos = D["pos"][D["pos"].grade == "L1"]
    hits1 = hits3 = n = 0
    rnd = []
    per_user: dict[str, list[int]] = {}
    for (u, t), g in pos[pos.track_id.isin(pool_tracks)].groupby(["user_ref", "track_id"]):
        keys = D["sec_by_track"].get(t, [])
        rows = [D["idx"][k] for k in keys if k in D["idx"]]
        if len(rows) < 3:
            continue
        gold = {D["idx"][k] for k in g.section_key if k in D["idx"]}
        if not gold:
            continue
        s = score_fn(u, t, rows)
        order = [rows[i] for i in np.argsort(-np.asarray(s))]
        h1 = int(order[0] in gold)
        h3 = int(any(o in gold for o in order[:3]))
        hits1 += h1
        hits3 += h3
        n += 1
        rnd.append(len(gold) / len(rows))
        per_user.setdefault(u, []).append(h1)
    return {"name": name, "n": n,
            "hit@1": hits1 / max(n, 1), "hit@3": hits3 / max(n, 1),
            "random@1": float(np.mean(rnd)) if rnd else 0.0,
            "per_user_hit1": {u: float(np.mean(v)) for u, v in per_user.items()}}


def baselines(D, pool_tracks: set) -> list[dict]:
    X = D["X"]
    mom = D["mom"]
    cols = D["info"]["side_cols"]
    ci = {c: D["info"]["pca_dim"] + i for i, c in enumerate(cols)}
    rng = np.random.default_rng(1)

    # 위치 prior: train 라벨에서 pos_bin 별 P(L1)
    tr = D["lab"][D["lab"].track_id.isin(D["tr_tracks"])].copy()
    rows = [D["idx"][k] for k in tr.section_key]
    pb = np.argmax(X[np.asarray(rows)][:, ci["pos0"]:ci["pos0"] + 10], axis=1)
    tr["pb"] = pb
    prior = tr.assign(is1=(tr.grade == "L1").astype(int)).groupby("pb").is1.mean()
    prior_v = np.array([prior.get(i, 0.0) for i in range(10)])

    Z = np.load(OUT_DIR / "moments_emb.npy", mmap_mode="r")
    row_of_emb = mom.emb_row.to_numpy()

    def dna_mean(u: str, t: str, rows: list[int]) -> np.ndarray:
        h = user_history(D, u, t)
        if not h:
            return np.zeros(len(rows))
        V = np.asarray(Z[row_of_emb[np.asarray(h)]], dtype=np.float32).mean(0)
        V /= max(np.linalg.norm(V), 1e-9)
        C = np.asarray(Z[row_of_emb[np.asarray(rows)]], dtype=np.float32)
        return C @ V

    return [
        eval_hit(D, lambda u, t, r: rng.random(len(r)), pool_tracks, "random"),
        eval_hit(D, lambda u, t, r: X[np.asarray(r)][:, ci["log_dur"]], pool_tracks, "구간 길이"),
        eval_hit(D, lambda u, t, r: prior_v[np.argmax(
            X[np.asarray(r)][:, ci["pos0"]:ci["pos0"] + 10], axis=1)], pool_tracks, "위치 prior"),
        eval_hit(D, dna_mean, pool_tracks, "DNA 평균 코사인 (현재 규칙)"),
    ]


# ------------------------------------------------------------------ 학습
KMAX = 16
W_LIST = 0.5      # 곡 내 listwise 손실 가중치 (지표와 목적을 정렬)


def train(D, steps: int, batch: int, lr: float) -> dict:
    import torch
    import torch.nn.functional as Fn

    torch.manual_seed(SEED)
    dev = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
    X = torch.tensor(D["X"], device=dev)
    model = make_model(X.shape[1]).to(dev)
    n_par = sum(p.numel() for p in model.parameters())
    print(f"device={dev} | 파라미터 {n_par:,} | train {len(D['train']):,} val {len(D['val']):,}")

    opt = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=1e-4)
    sch = torch.optim.lr_scheduler.OneCycleLR(opt, lr, total_steps=steps, pct_start=0.2)
    rng = np.random.default_rng(SEED)
    ex = D["train"]
    negs = np.asarray(D["neg_rows"], dtype=np.int64) if D["neg_rows"] else None
    t0 = time.time()
    hist_log = []

    for step in range(steps):
        pick = rng.choice(len(ex), size=min(batch, len(ex)), replace=len(ex) < batch)
        H, M, P, W, TR, TM, TT, LW = [], [], [], [], [], [], [], []
        for i in pick:
            e = ex[i]
            h = user_history(D, e["user"], e["track"])
            if not h:
                continue
            hh = list(h)[:HIST_MAX]
            M.append([True] * len(hh) + [False] * (HIST_MAX - len(hh)))
            H.append(hh + [0] * (HIST_MAX - len(hh)))
            hw = history_weights(D, hh)
            LW.append(np.log(np.concatenate([hw, np.full(HIST_MAX - len(hh), 1e-3)])).tolist())
            P.append(e["row"])
            W.append(e["w"])
            # 곡 내 후보: 대상 곡의 구간들 (positive 를 반드시 포함)
            tr_rows = list(D["rows_by_track"].get(e["track"], [e["row"]]))
            if e["row"] not in tr_rows:
                tr_rows.append(e["row"])
            if len(tr_rows) > KMAX:
                others = [r for r in tr_rows if r != e["row"]]
                others = list(rng.choice(others, size=KMAX - 1, replace=False))
                tr_rows = others + [e["row"]]
            tgt = tr_rows.index(e["row"])
            TM.append([True] * len(tr_rows) + [False] * (KMAX - len(tr_rows)))
            TR.append(tr_rows + [0] * (KMAX - len(tr_rows)))
            TT.append(tgt)
        if len(P) < 8:
            continue
        Ht = torch.tensor(np.asarray(H), device=dev)
        Mt = torch.tensor(np.asarray(M), device=dev)
        Pt = torch.tensor(np.asarray(P), device=dev)
        Wt = torch.tensor(np.asarray(W), dtype=torch.float32, device=dev)

        LWt = torch.tensor(np.asarray(LW, dtype=np.float32), device=dev)
        zu = model.enc_user(X[Ht], Mt, update=True, logw=LWt)
        zs = model.enc_sec(X[Pt])
        cand = zs
        if negs is not None:
            nb = torch.tensor(rng.choice(negs, size=min(256, len(negs)), replace=False), device=dev)
            cand = torch.cat([zs, model.enc_sec(X[nb])], 0)
        logits = zu @ cand.T / TAU
        target = torch.arange(len(P), device=dev)
        loss_ret = (Fn.cross_entropy(logits, target, reduction="none") * Wt).mean()

        # 곡 내 listwise: "이 곡의 어느 구간인가" 를 직접 최적화
        TRt = torch.tensor(np.asarray(TR), device=dev)
        TMt = torch.tensor(np.asarray(TM), device=dev)
        TTt = torch.tensor(np.asarray(TT), device=dev)
        zt = model.enc_sec(X[TRt])                      # (B,KMAX,DIM)
        ll = (zu.unsqueeze(1) * zt).sum(-1) / TAU
        ll = ll.masked_fill(~TMt, -1e9)
        loss_list = (Fn.cross_entropy(ll, TTt, reduction="none") * Wt).mean()
        loss = (1 - W_LIST) * loss_ret + W_LIST * loss_list
        opt.zero_grad()
        loss.backward()
        opt.step()
        sch.step()
        if (step + 1) % 250 == 0:
            print(f"  step {step+1}/{steps} loss {loss.item():.4f} "
                  f"(retrieval {loss_ret.item():.3f} / listwise {loss_list.item():.3f}) "
                  f"{time.time()-t0:.0f}s", flush=True)
            hist_log.append({"step": step + 1, "loss": float(loss.item()),
                             "loss_retrieval": float(loss_ret.item()),
                             "loss_listwise": float(loss_list.item())})

    model.eval()
    with torch.no_grad():
        def model_score(u: str, t: str, rows: list[int]) -> np.ndarray:
            h = user_history(D, u, t)
            if not h:
                return np.zeros(len(rows))
            hh = list(h)[:HIST_MAX]
            Ht = torch.tensor([hh + [0] * (HIST_MAX - len(hh))], device=dev)
            Mt = torch.tensor([[True] * len(hh) + [False] * (HIST_MAX - len(hh))], device=dev)
            hw = history_weights(D, hh)
            LWt = torch.tensor(
                np.log(np.concatenate([hw, np.full(HIST_MAX - len(hh), 1e-3)]))[None].astype(
                    np.float32), device=dev)
            zu = model.enc_user(X[Ht], Mt, logw=LWt)
            zs = model.enc_sec(X[torch.tensor(np.asarray(rows), device=dev)])
            return (zs @ zu.T).squeeze(-1).cpu().numpy()

        res = baselines(D, D["va_tracks"])
        res.append(eval_hit(D, model_score, D["va_tracks"], "Stage1 (학습)"))
        # 사용자 벡터 분리도
        us = sorted(D["hist"])
        ZU = []
        for u in us:
            h = user_history(D, u, None)[:HIST_MAX]
            if not h:
                continue
            Ht = torch.tensor([h + [0] * (HIST_MAX - len(h))], device=dev)
            Mt = torch.tensor([[True] * len(h) + [False] * (HIST_MAX - len(h))], device=dev)
            hw = history_weights(D, h)
            LWt = torch.tensor(np.log(np.concatenate(
                [hw, np.full(HIST_MAX - len(h), 1e-3)]))[None].astype(np.float32), device=dev)
            ZU.append(model.enc_user(X[Ht], Mt, logw=LWt).cpu().numpy()[0])
        ZU = np.asarray(ZU)
        S = ZU @ ZU.T
        iu = np.triu_indices(len(ZU), 1)
        pair = float(np.median(S[iu])) if len(ZU) > 1 else 0.0
        torch.save({"state": model.state_dict(), "in_dim": int(X.shape[1]),
                    "dim": DIM, "hist_max": HIST_MAX,
                    "half_life_days": HALF_LIFE_DAYS,
                    "feature_version": D["info"]["version"]}, CKPT)
    return {"results": res, "user_pair_cos_median": pair, "params": n_par,
            "loss_log": hist_log, "users": len(ZU)}


def export(D) -> None:
    import torch

    ck = torch.load(CKPT, map_location="cpu", weights_only=False)
    dev = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
    model = make_model(ck["in_dim"]).to(dev)
    model.load_state_dict(ck["state"])
    model.eval()
    X = torch.tensor(D["X"], device=dev)
    with torch.no_grad():
        ZS = []
        for i in range(0, len(X), 8192):
            ZS.append(model.enc_sec(X[i:i + 8192]).cpu().numpy())
        ZS = np.concatenate(ZS)
        np.save(SEC_VEC, ZS.astype(np.float32))
        us, ZU = [], []
        for u in sorted(D["hist"]):
            h = user_history(D, u, None)[:HIST_MAX]
            if not h:
                continue
            Ht = torch.tensor([h + [0] * (HIST_MAX - len(h))], device=dev)
            Mt = torch.tensor([[True] * len(h) + [False] * (HIST_MAX - len(h))], device=dev)
            ZU.append(model.enc_user(X[Ht], Mt).cpu().numpy()[0])
            us.append(u)
        np.savez(USER_VEC, user_ref=np.array(us), z=np.asarray(ZU, dtype=np.float32))
    print(f"구간 벡터 {ZS.shape} -> {SEC_VEC}\n사용자 벡터 {len(us)}명 -> {USER_VEC}")


def main() -> int:
    ap = argparse.ArgumentParser(description="Stage 1 Dual Encoder")
    ap.add_argument("--pool", default="fma")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--train", action="store_true")
    ap.add_argument("--export", action="store_true")
    ap.add_argument("--steps", type=int, default=1500)
    ap.add_argument("--batch", type=int, default=256)
    ap.add_argument("--lr", type=float, default=3e-3)
    ap.add_argument("--half-life", type=float, default=0.0,
                    help="히스토리 반감기(일). 0 = 감쇠 없음")
    a = ap.parse_args()
    global HALF_LIFE_DAYS
    HALF_LIFE_DAYS = float(a.half_life)
    if HALF_LIFE_DAYS > 0:
        print(f"시간 감쇠 반감기 {HALF_LIFE_DAYS}일")

    D = build(a.pool)
    print(f"라벨 {len(D['lab']):,} | positive {len(D['pos']):,} | "
          f"train곡 {len(D['tr_tracks'])} / val곡 {len(D['va_tracks'])}")
    if a.selftest or not (a.train or a.export):
        return selftest(D)
    if a.train:
        if selftest(D) != 0:
            print("누출 테스트 실패 — 학습 중단")
            return 1
        out = train(D, a.steps, a.batch, a.lr)
        print(f"\n{'모델/기준선':<26}{'n':>6}{'hit@1':>9}{'hit@3':>9}{'random@1':>10}")
        for r in out["results"]:
            print(f"{r['name']:<26}{r['n']:>6}{r['hit@1']:>9.4f}{r['hit@3']:>9.4f}{r['random@1']:>10.4f}")
        print(f"\n사용자쌍 z_u 코사인 중앙 {out['user_pair_cos_median']:.3f} (목표 <0.5, 규칙 DNA 0.878)")
        REPORT.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"보고서 -> {REPORT}")
    if a.export:
        export(D)
    return 0


if __name__ == "__main__":
    sys.exit(main())
