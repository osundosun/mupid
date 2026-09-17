"""라벨러 — LABELS.md 정의를 그대로 구현한다.

행동 로그(초 단위 스키마)를 구간 경계에 사상해 등급 라벨을 만든다.

핵심 규칙
  · MOMENT_LIKE / REPLAY 는 사용자가 범위를 직접 주므로 지연 보정 0
  · SEEK 되돌리기 도착 구간도 명시 지정이라 보정 0
  · 도달하지 않은 구간은 **절대 negative 로 세지 않는다** (마스킹)
  · 등급 우선순위 L1 > neg_strong > L2 > neg_weak  (명시 positive 가 암묵 negative 를 이긴다)
  · 커버리지는 baseline 잔차로만 쓴다 (위치 편향 제거)
  · 인접 두 구간에 걸친 MOMENT_LIKE 는 "#i+j" 로 병합 (3개 이상은 병합 금지)

출력: labels_<pool>.parquet

  python -m ai_server.app.labeler --pool local
  python -m ai_server.app.labeler --pool fma
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import uuid
from collections import defaultdict
from pathlib import Path

import numpy as np
import pandas as pd

from . import paths

# 산출물 루트는 paths 한 곳에서만 계산한다. 예전에는 이 모듈이 따로 셈해서
# `MUPID_AI_ARTIFACT_DIR=` (빈 값) 일 때 recommender 와 다른 곳을 봤다 —
# os.environ.get 의 기본값은 빈 문자열을 덮지 않아 Path('') 가 되기 때문이다.
ROOT = paths.artifact_root()
OUT_DIR = ROOT / "data" / "proc" / "mupid"
LOG_DIR = OUT_DIR / "logs"

LABELER_VERSION = "labels-v1"
# ================================ 설정 (LABELS.md §15) ================================
OVERLAP_MIN = 0.30         # L1 ① MOMENT_LIKE 겹침
REPLAY_OVERLAP_MIN = 0.50  # L1 ③ / L2 ③
RESIDUAL_POS = 0.25
RESIDUAL_NEG = -0.25
MERGE_OVERLAP_MIN = 0.25
MERGE_MAX_SEC = 90.0
FAST_SKIP_SEC = 5.0
REACH_MIN_SEC = 1.5
REACH_MIN_FRAC = 0.15
MUTED_MAX_FRAC = 0.5
BG_MAX_FRAC = 0.5
SHRINK_KAPPA = 5.0
COVERAGE_CAP = 3.0
POS_BINS = 10
W_L1, W_L2 = 1.0, 0.4
# =====================================================================================

GRADE_RANK = {"L1": 4, "neg_strong": 3, "L2": 2, "neg_weak": 1}


def ov(a0: float, a1: float, b0: float, b1: float) -> float:
    return max(0.0, min(a1, b1) - max(a0, b0))


def spans_of(ev) -> list[tuple[float, float]]:
    iv = getattr(ev, "intervals", None)
    if not isinstance(iv, list):
        return []
    out = []
    for x in iv:
        if isinstance(x, dict):
            out.append((float(x["start_sec"]), float(x["end_sec"])))
        elif isinstance(x, (list, tuple)) and len(x) == 2:   # ms 레거시 허용
            out.append((float(x[0]) / 1000.0, float(x[1]) / 1000.0))
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description="구간 선호 라벨 생성")
    ap.add_argument("--pool", default="local", help="local | fma | flow | service")
    ap.add_argument("--events", default=None, help="이벤트 JSONL 경로 (기본: logs/events_<pool>.jsonl)")
    ap.add_argument("--out", default=None, help="출력 parquet 경로")
    a = ap.parse_args()

    events = Path(a.events) if a.events else LOG_DIR / f"events_{a.pool}.jsonl"
    out = Path(a.out) if a.out else OUT_DIR / f"labels_{a.pool}.parquet"
    mom = pd.read_parquet(OUT_DIR / "moments.parquet")
    if "source" not in mom.columns:
        mom["source"] = "fma"
    ev = pd.DataFrame([json.loads(x) for x in events.open(encoding="utf-8")])
    ev["track_id"] = ev.track_id.astype("string")
    # 로그에 등장하는 곡만 펼친다. 카탈로그 전체(7천곡)를 records 로 바꾸면
    # 이벤트 수와 무관하게 11초가 든다. 결과는 동일하다 (secs 는 secs.get(tid) 로만 쓴다).
    used = set(ev.track_id.dropna().astype(str))
    mom_used = mom[mom.track_id.astype(str).isin(used)]
    secs = {t: g[["moment_id", "section_idx", "start_sec", "end_sec", "dur_sec",
                  "rel_start", "rel_end", "emb_row"]].to_dict("records")
            for t, g in mom_used.groupby("track_id", sort=False)}
    detector = {t: ("allin1_harmonix" if s in ("local", "flow") else "librosa-agglomerative-v1")
                for t, s in zip(mom.track_id, mom.source)}
    print(f"이벤트 {len(ev):,} | 사용자 {ev.user_ref.nunique()} | 풀={a.pool}")

    # (user, section_key) -> 누적
    acc: dict[tuple[str, str], dict] = {}

    def rec(user: str, key: str, tid: str, **kw):
        d = acc.setdefault((user, key), {
            "track_id": tid, "coverage": 0.0, "reached": False,
            "moment_like": 0, "replay": 0, "seek_back": 0, "seek_back_wf": 0,
            "seek_forward": 0, "fast_skip": 0, "track_like": 0, "playlist_add": 0,
            "dislike": 0, "event_ids": [], "listen_ordinal": 1, "session_id": "",
            "occurred_at": "", "is_merged": False, "member_idx": None,
        })
        for k, v in kw.items():
            if k in ("event_ids",):
                d[k].extend(v)
            elif isinstance(v, bool):
                d[k] = d[k] or v
            elif isinstance(v, (int, float)):
                d[k] += v
            else:
                d[k] = v
        return d

    # ---------------- 재생 진행: 커버리지 + 도달 ----------------
    ordinal: dict[tuple[str, str], int] = defaultdict(int)
    seen_sess: set[tuple[str, str, str]] = set()
    for r in ev[ev.event_type.isin(("PLAY_PROGRESS", "PLAY_END"))].itertuples():
        tid = str(r.track_id)
        S = secs.get(tid)
        if not S:
            continue
        sp = spans_of(r)
        if not sp:
            continue
        rate = float(getattr(r, "playback_rate", 1.0) or 1.0)
        total = sum(b - x for x, b in sp) or 1e-9
        muted_f = float(getattr(r, "muted_sec", 0.0) or 0.0) / total
        bg_f = float(getattr(r, "background_sec", 0.0) or 0.0) / total
        clean = (abs(rate - 1.0) < 1e-6) and muted_f < MUTED_MAX_FRAC and bg_f < BG_MAX_FRAC
        k3 = (r.user_ref, tid, r.session_id)
        if k3 not in seen_sess:
            seen_sess.add(k3)
            ordinal[(r.user_ref, tid)] += 1
        ordn = ordinal[(r.user_ref, tid)]
        for s in S:
            played = sum(ov(x, b, s["start_sec"], s["end_sec"]) for x, b in sp)
            if played <= 0:
                continue
            cov = min(played / max(s["dur_sec"], 1e-9), COVERAGE_CAP)
            reached = clean and played >= max(REACH_MIN_SEC, REACH_MIN_FRAC * s["dur_sec"])
            rec(r.user_ref, s["moment_id"], tid, coverage=cov, reached=reached,
                event_ids=[r.event_id], listen_ordinal=ordn,
                session_id=r.session_id, occurred_at=r.occurred_at)

    # ---------------- 명시 신호 ----------------
    merged_rows: list[dict] = []
    for r in ev[ev.event_type == "MOMENT_LIKE"].itertuples():
        tid = str(r.track_id)
        S = secs.get(tid)
        if not S:
            continue
        s0, e0 = float(r.start_sec), float(r.end_sec)
        ovs = [(i, ov(s0, e0, s["start_sec"], s["end_sec"]) / max(s["dur_sec"], 1e-9))
               for i, s in enumerate(S)]
        hits = [(i, o) for i, o in ovs if o >= OVERLAP_MIN]
        for i, _ in hits:
            rec(r.user_ref, S[i]["moment_id"], tid, moment_like=1, event_ids=[r.event_id])
        # 병합 판정: 인접 두 구간이 각각 MERGE_OVERLAP_MIN 이상, 3번째 없음
        cand = [i for i, o in ovs if o >= MERGE_OVERLAP_MIN]
        if len(cand) == 2 and cand[1] == cand[0] + 1:
            i, j = cand
            span = S[i]["dur_sec"] + S[j]["dur_sec"]
            if span <= MERGE_MAX_SEC:
                merged_rows.append({
                    "user_ref": r.user_ref, "track_id": tid,
                    "section_key": f"{tid}#{S[i]['section_idx']}+{S[j]['section_idx']}",
                    "member_idx": [int(S[i]["section_idx"]), int(S[j]["section_idx"])],
                    "member_moment_ids": [S[i]["moment_id"], S[j]["moment_id"]],
                    "event_ids": [r.event_id], "session_id": r.session_id,
                    "occurred_at": r.occurred_at,
                })

    for r in ev[ev.event_type == "REPLAY"].itertuples():
        tid = str(r.track_id)
        S = secs.get(tid)
        if not S:
            continue
        s0, e0 = float(r.start_sec), float(r.end_sec)
        for s in S:
            if ov(s0, e0, s["start_sec"], s["end_sec"]) / max(s["dur_sec"], 1e-9) >= REPLAY_OVERLAP_MIN:
                rec(r.user_ref, s["moment_id"], tid,
                    replay=int(getattr(r, "count", 1) or 1), event_ids=[r.event_id])

    for r in ev[ev.event_type == "SEEK"].itertuples():
        tid = str(r.track_id)
        S = secs.get(tid)
        if not S:
            continue
        f, t = float(r.from_sec), float(r.to_sec)
        trig = str(getattr(r, "trigger", "") or "")
        if t < f:                                  # 되돌리기 → positive 후보
            for s in S:
                if s["start_sec"] <= t < s["end_sec"]:
                    rec(r.user_ref, s["moment_id"], tid, seek_back=1,
                        seek_back_wf=1 if trig == "waveform_click" else 0,
                        event_ids=[r.event_id])
        else:                                      # 앞으로 건너뜀 → 강한 negative
            for s in S:
                if s["start_sec"] <= f < s["end_sec"]:
                    rec(r.user_ref, s["moment_id"], tid, seek_forward=1, event_ids=[r.event_id])

    for r in ev[ev.event_type == "PLAY_END"].itertuples():
        if str(getattr(r, "reason", "")) != "skip_next":
            continue
        if float(getattr(r, "position_sec", 1e9)) > FAST_SKIP_SEC:
            continue
        tid = str(r.track_id)
        S = secs.get(tid)
        if not S:
            continue
        pos = float(r.position_sec)
        for s in S:
            if ov(0.0, pos, s["start_sec"], s["end_sec"]) > 0:
                rec(r.user_ref, s["moment_id"], tid, fast_skip=1, event_ids=[r.event_id])

    heard = {(u, k) for (u, k), d in acc.items() if d["coverage"] > 0.5 and d["reached"]}
    by_ut: dict[tuple[str, str], list[str]] = defaultdict(list)
    for u, k in heard:
        by_ut[(u, acc[(u, k)]["track_id"])].append(k)
    for et, field in (("TRACK_LIKE", "track_like"), ("PLAYLIST_ADD", "playlist_add"),
                      ("DISLIKE", "dislike")):
        for r in ev[ev.event_type == et].itertuples():
            v = getattr(r, "value", 1)
            fld = "dislike" if (et == "TRACK_LIKE" and float(v or 1) < 0) else field
            for k in by_ut.get((r.user_ref, str(r.track_id)), []):
                rec(r.user_ref, k, str(r.track_id), **{fld: 1}, event_ids=[r.event_id])

    # ---------------- 잔차 ----------------
    rows = []
    minfo = mom.set_index("moment_id")
    for (u, k), d in acc.items():
        if k not in minfo.index:
            continue
        mi = minfo.loc[k]
        rows.append({"user_ref": u, "section_key": k, **d,
                     "rel_center": float((mi.rel_start + mi.rel_end) / 2),
                     "dur_sec": float(mi.dur_sec), "emb_row": int(mi.emb_row)})
    df = pd.DataFrame(rows)
    df["pos_bin"] = np.clip((df.rel_center * POS_BINS).astype(int), 0, POS_BINS - 1)
    prior = df.groupby("pos_bin").coverage.mean()
    gp = df.pos_bin.map(prior).to_numpy()
    grp = df.groupby("section_key").coverage
    baseline = (grp.transform("size") * grp.transform("mean") + SHRINK_KAPPA * gp) / \
               (grp.transform("size") + SHRINK_KAPPA)
    df["residual"] = df.coverage - baseline

    # ---------------- 등급 ----------------
    def grade(r) -> str:
        if r.moment_like > 0 or r.seek_back_wf > 0 or r.replay >= 2:
            return "L1"
        if not r.reached:
            return "mask"                                  # 미도달 → negative 금지
        if r.seek_forward > 0 or r.fast_skip > 0 or r.dislike > 0:
            return "neg_strong"
        weak_pos = int(r.residual >= RESIDUAL_POS) + int(r.seek_back > 0) + \
            int(r.replay >= 1) + int((r.track_like > 0 or r.playlist_add > 0))
        if weak_pos >= 2:
            return "L2"
        if r.residual <= RESIDUAL_NEG:
            return "neg_weak"
        return "L3"                                        # 커버리지만 → 제외

    df["grade"] = [grade(r) for r in df.itertuples()]
    lab = df[df.grade.isin(GRADE_RANK)].copy()
    lab["y"] = lab.grade.isin(("L1", "L2")).astype(int)
    lab["w"] = lab.grade.map({"L1": W_L1, "L2": W_L2, "neg_strong": W_L1, "neg_weak": W_L2})
    lab["is_merged"] = False
    lab["member_idx"] = None
    lab["evidence"] = [json.dumps({k: int(getattr(r, k)) for k in
                                   ("moment_like", "replay", "seek_back", "seek_back_wf",
                                    "seek_forward", "fast_skip", "track_like",
                                    "playlist_add", "dislike")}, ensure_ascii=False)
                       for r in lab.itertuples()]
    lab["section_detector"] = lab.track_id.map(detector)
    lab["delay_correction_sec"] = 0.0
    lab["labeler_version"] = LABELER_VERSION
    lab["pool"] = a.pool
    lab["label_id"] = [f"lbl_{uuid.uuid4().hex[:20]}" for _ in range(len(lab))]

    # 병합 라벨 (중복 제거 후 L1 로 추가)
    mg = pd.DataFrame(merged_rows).drop_duplicates(["user_ref", "section_key"]) \
        if merged_rows else pd.DataFrame()
    if len(mg):
        mg["grade"], mg["y"], mg["w"] = "L1", 1, W_L1
        mg["is_merged"] = True
        mg["coverage"] = np.nan
        mg["residual"] = np.nan
        mg["reached"] = True
        mg["listen_ordinal"] = 1
        mg["evidence"] = json.dumps({"moment_like": 1, "merged": 1}, ensure_ascii=False)
        mg["section_detector"] = mg.track_id.map(detector)
        mg["delay_correction_sec"] = 0.0
        mg["labeler_version"] = LABELER_VERSION
        mg["pool"] = a.pool
        mg["emb_row"] = -1                                  # 재추출 대기
        mg["needs_reextract"] = True
        mg["label_id"] = [f"lbl_{uuid.uuid4().hex[:20]}" for _ in range(len(mg))]
        lab["needs_reextract"] = False
        lab = pd.concat([lab, mg], ignore_index=True)
    else:
        lab["needs_reextract"] = False

    keep = ["label_id", "user_ref", "track_id", "section_key", "is_merged", "member_idx",
            "grade", "y", "w", "evidence", "coverage", "residual", "reached",
            "listen_ordinal", "section_detector", "delay_correction_sec",
            "labeler_version", "pool", "emb_row", "needs_reextract",
            "session_id", "occurred_at"]
    lab = lab[[c for c in keep if c in lab.columns]]
    lab.to_parquet(out, index=False)

    print(f"\n라벨 {len(lab):,} -> {out}")
    print(lab.grade.value_counts().to_string())
    print(f"제외: L3(커버리지만) {int((df.grade=='L3').sum()):,} | "
          f"마스킹(미도달) {int((df.grade=='mask').sum()):,}")
    print(f"병합 라벨 {int(lab.is_merged.sum()):,} (재추출 대기)")
    print(f"사용자당 L1 중위 "
          f"{lab[lab.grade=='L1'].groupby('user_ref').size().median():.0f}건")
    return 0


if __name__ == "__main__":
    sys.exit(main())
