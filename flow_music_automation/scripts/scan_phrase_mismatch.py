# -*- coding: utf-8 -*-
"""
보컬곡 프레이즈 길이(마디 수) 이탈 탐지 스캐너.

배경: 모든 프롬프트가 "Strict 4/4, 모든 섹션은 완전한 4마디 또는 8마디 단위로만
구성"을 명시하는데도, 가끔 특정 곡의 한 섹션 내부에서 4+4+8이어야 할 프레이즈가
4+5+8이나 3+4+8처럼 마디 수가 어긋나는 경우가 있다(곡 전체는 4/4가 맞지만 국소적으로
리듬이 어긋나 보컬-반주가 튀는 느낌). 기존 scripts/scan_meter_mismatch.py(3/4 vs 4/4
전역 미터 판별)와는 다른 문제.

핵심 판별 신호: madmom으로 곡 전체의 다운비트를 4/4 고정 가정(beats_per_bar=[4])으로
측정했을 때, 총 마디 수가 4의 배수가 아니면 어딘가에서 프레이즈 길이가 어긋난 것으로
본다(모든 섹션이 4/8마디 단위라는 프롬프트 규칙상, 정상이면 합계도 반드시 4의 배수여야
함). T000195(사용자가 105초 지점에서 직접 들은 문제)로 검증 완료: 총 86마디 검출,
86 % 4 == 2로 이 신호가 정확히 잡음.

보조 힌트(참고용, 정밀하지 않음):
  - duration_outlier_hint: 마디 길이 중앙값 대비 편차가 가장 큰 내부 마디의 시각.
    (검증 결과 신뢰도 낮음 -- 삽입/누락된 마디 자체는 보통 "정상 길이"라 이 방법으론
    잘 안 잡힘. 그래도 거의 공짜라 계속 계산은 해서 참고용으로 남긴다.)
  - template_boundary_hints: 프롬프트의 "base section plan in bars: ..." 문구를
    파싱하고 실제 가사 섹션 순서(lyrics_sections)와 대조해서, 각 섹션 경계의 예상
    타임스탬프를 계산. T000195에서는 이 방식이 실제 문제 지점(105초)을 정확히
    짚어냈음(Bridge->Final Chorus 경계 103.1초 계산값). 보컬 496곡 중 약 70%만
    이 문구가 있어서 나머지는 힌트 없이 "곡 전체 확인 필요"로 표시됨.

Usage:
    .venv\\Scripts\\python.exe scripts\\scan_phrase_mismatch.py [--limit N] [--redo] [--workers N] [--track-ids T000195,T000011]

Writes/updates logs/phrase_mismatch_scan_result.jsonl (one JSON object per track_id).
Safe to re-run: skips track_ids already present in the output file (unless --redo).
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import re
import time
import warnings

warnings.filterwarnings("ignore")

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AUDIO_DIR = os.path.join(PROJECT_ROOT, "downloads", "audio")
METADATA_DIR = os.path.join(PROJECT_ROOT, "downloads", "metadata")
OUT_PATH = os.path.join(PROJECT_ROOT, "logs", "phrase_mismatch_scan_result.jsonl")

_FFMPEG_BIN = (
    r"C:\Users\SSAFY\AppData\Local\Microsoft\WinGet\Packages"
    r"\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-9.0.1-full_build\bin"
)

MIN_BARS_FOR_VERDICT = 10
BPM_MISMATCH_TOLERANCE = 0.05  # 5%

ROLE_ORDER = ["intro", "verse", "pre_chorus", "chorus", "bridge", "outro"]


def _apply_compat_shims():
    import collections
    import collections.abc
    import numpy as np

    for _name in ["MutableSequence", "MutableMapping", "Mapping", "Sequence", "Iterable"]:
        if not hasattr(collections, _name):
            setattr(collections, _name, getattr(collections.abc, _name))
    for _name, _alias in [("float", float), ("int", int), ("bool", bool), ("object", object), ("str", str), ("complex", complex)]:
        if not hasattr(np, _name):
            setattr(np, _name, _alias)
    if os.path.isdir(_FFMPEG_BIN) and _FFMPEG_BIN not in os.environ.get("PATH", ""):
        os.environ["PATH"] = _FFMPEG_BIN + os.pathsep + os.environ.get("PATH", "")


# ---------------------------------------------------------------------------
# 섹션 라벨 -> 역할(role) 분류. 프롬프트의 base plan 라벨(Main A/Hook/Break 등)과
# 실제 가사 섹션의 raw_label/type 양쪽에 공통으로 적용해서, 서로 다른 어휘를 쓰는
# 장르(팝 vs 펑크 등)에서도 같은 역할끼리 매칭할 수 있게 한다.
# ---------------------------------------------------------------------------
def classify_role(label: str, schema_type: str | None = None) -> str | None:
    if schema_type in ROLE_ORDER:
        return schema_type
    raw = (label or "").strip().lower()
    if not raw or raw.startswith("end") or re.match(r"^end\b", raw):
        return None
    if raw == "pre" or "pre-chorus" in raw or "pre chorus" in raw or "prechorus" in raw or ("pre" in raw and "hook" in raw):
        return "pre_chorus"
    if "chorus" in raw or "hook" in raw:
        return "chorus"
    if raw == "bridge" or "solo" in raw or "break" in raw:
        return "bridge"
    if "verse" in raw or re.match(r"^main\b", raw):
        return "verse"
    if "intro" in raw:
        return "intro"
    if "outro" in raw:
        return "outro"
    return None


def parse_nominal_bpm(prompt: str) -> float | None:
    m = re.search(r"\b(\d{2,3})\s*BPM\b", prompt)
    return float(m.group(1)) if m else None


def parse_section_template(prompt: str):
    """'base section plan in bars: Intro 4 | Verse 8 | ...' -> [(role, bars), ...]"""
    m = re.search(r"base section plan in bars:\s*(.+?)\.", prompt)
    if not m:
        return None
    template = []
    for part in m.group(1).split("|"):
        part = part.strip()
        mm = re.match(r"(.+?)\s+(\d+)\s*$", part)
        if not mm:
            return None
        role = classify_role(mm.group(1))
        if role is None:
            return None
        template.append((role, int(mm.group(2))))
    return template


def realized_roles(lyrics_sections):
    roles = []
    for s in lyrics_sections or []:
        role = classify_role(s.get("raw_label"), s.get("type"))
        if role is not None:
            roles.append(role)
    return roles


def compute_template_boundary_hints(prompt: str, lyrics_sections, nominal_bpm: float):
    template = parse_section_template(prompt)
    if not template or not lyrics_sections or not nominal_bpm:
        return None
    realized = realized_roles(lyrics_sections)
    if not realized:
        return None

    bar_duration = 240.0 / nominal_bpm
    ti = 0
    cumulative_bars = 0
    boundaries = []
    matched_all = True
    for role in realized:
        while ti < len(template) and template[ti][0] != role:
            ti += 1
        if ti >= len(template):
            matched_all = False
            break
        bars = template[ti][1]
        start_bar = cumulative_bars
        cumulative_bars += bars
        end_bar = cumulative_bars
        boundaries.append({
            "role": role,
            "start_s": round(start_bar * bar_duration, 2),
            "end_s": round(end_bar * bar_duration, 2),
        })
        ti += 1

    if not boundaries:
        return None
    return {
        "matched_all_sections": matched_all,
        "expected_total_bars_from_template": cumulative_bars if matched_all else None,
        "boundaries": boundaries,
    }


_worker_proc = None
_worker_dbn = None


def _worker_init():
    global _worker_proc, _worker_dbn
    _apply_compat_shims()
    from madmom.features.downbeats import RNNDownBeatProcessor, DBNDownBeatTrackingProcessor
    _worker_proc = RNNDownBeatProcessor()
    _worker_dbn = DBNDownBeatTrackingProcessor(beats_per_bar=[4], fps=100)


def _analyze_one(mp3_path, prompt, lyrics_sections):
    import numpy as np
    from mutagen.mp3 import MP3

    act = _worker_proc(mp3_path)
    result = _worker_dbn(act)
    times = result[:, 0]
    pos = result[:, 1].astype(int)
    downbeat_times = times[pos == 1]

    total_bars = max(len(downbeat_times) - 1, 0)
    if total_bars < MIN_BARS_FOR_VERDICT:
        return {
            "total_bars": total_bars,
            "bars_mod4": None,
            "verdict": "NO_BARS_DETECTED",
            "measured_bpm": None,
            "nominal_bpm": parse_nominal_bpm(prompt),
            "bpm_mismatch_suspected": None,
            "duration_outlier_hint": None,
            "template_boundary_hints": None,
            "leading_gap_bars": None,
            "trailing_gap_bars": None,
        }

    bar_durations = np.diff(downbeat_times)
    median_bar = float(np.median(bar_durations))

    # 참고용: 오디오 시작/끝과 첫/마지막 다운비트 사이의 간격을 "마디" 단위로 환산.
    # 검증 결과 이 값 하나로는 진짜 결함과 트래커의 콜드스타트 특성을 구분할 수 없었음
    # (확인된 실제 문제곡 T000195도 leading_gap이 1마디에 가깝게 나옴) -- 그래서
    # 자동 보정에는 쓰지 않고, 사람이 후보를 검토할 때 참고할 부가 정보로만 남긴다.
    try:
        audio_duration = MP3(mp3_path).info.length
        leading_gap_bars = round(float(downbeat_times[0]) / median_bar, 2) if median_bar else None
        trailing_gap_bars = round((audio_duration - float(downbeat_times[-1])) / median_bar, 2) if median_bar else None
    except Exception:
        leading_gap_bars = None
        trailing_gap_bars = None
    measured_bpm = round(240.0 / median_bar, 1) if median_bar > 0 else None
    nominal_bpm = parse_nominal_bpm(prompt)
    bpm_mismatch_suspected = None
    if measured_bpm and nominal_bpm:
        bpm_mismatch_suspected = abs(measured_bpm - nominal_bpm) / nominal_bpm > BPM_MISMATCH_TOLERANCE

    bars_mod4 = total_bars % 4
    verdict = "PHRASE_OK" if bars_mod4 == 0 else "PHRASE_MISMATCH_CANDIDATE"

    duration_outlier_hint = None
    if len(bar_durations) > 2:
        interior = bar_durations[1:-1]
        interior_idx = np.arange(1, len(bar_durations) - 1)
        dev = np.abs(interior - median_bar) / median_bar
        worst_i = int(np.argmax(dev))
        worst_bar_idx = int(interior_idx[worst_i])
        duration_outlier_hint = {
            "time_s": round(float(downbeat_times[worst_bar_idx]), 2),
            "deviation_pct": round(float(dev[worst_i]) * 100, 1),
        }

    template_hints = None
    if nominal_bpm:
        template_hints = compute_template_boundary_hints(prompt, lyrics_sections, nominal_bpm)

    return {
        "total_bars": total_bars,
        "bars_mod4": bars_mod4,
        "verdict": verdict,
        "measured_bpm": measured_bpm,
        "nominal_bpm": nominal_bpm,
        "bpm_mismatch_suspected": bpm_mismatch_suspected,
        "duration_outlier_hint": duration_outlier_hint,
        "template_boundary_hints": template_hints,
        "leading_gap_bars": leading_gap_bars,
        "trailing_gap_bars": trailing_gap_bars,
    }


def _worker_task(args):
    track_id, fp, prompt, lyrics_sections = args
    t0 = time.time()
    try:
        analysis = _analyze_one(fp, prompt, lyrics_sections)
        error = None
    except Exception as e:
        analysis = {
            "total_bars": 0, "bars_mod4": None, "verdict": "ANALYSIS_FAILED",
            "measured_bpm": None, "nominal_bpm": None, "bpm_mismatch_suspected": None,
            "duration_outlier_hint": None, "template_boundary_hints": None,
            "leading_gap_bars": None, "trailing_gap_bars": None,
        }
        error = f"{type(e).__name__}: {e}"
    elapsed = time.time() - t0
    return track_id, analysis, error, elapsed


def load_done_ids():
    done = set()
    if os.path.exists(OUT_PATH):
        with open(OUT_PATH, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    done.add(json.loads(line)["track_id"])
                except Exception:
                    pass
    return done


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--redo", action="store_true")
    ap.add_argument("--workers", type=int, default=10)
    ap.add_argument("--track-ids", type=str, default=None, help="Comma-separated track_ids to scan (overrides full scan).")
    args = ap.parse_args()

    meta_files = sorted(glob.glob(os.path.join(METADATA_DIR, "T*.json")))
    done = set() if args.redo else load_done_ids()
    want_ids = set(args.track_ids.split(",")) if args.track_ids else None

    pending = []
    for mf in meta_files:
        track_id = os.path.basename(mf).replace(".json", "")
        if want_ids is not None and track_id not in want_ids:
            continue
        if track_id in done and want_ids is None:
            continue
        with open(mf, encoding="utf-8") as f:
            meta = json.load(f)
        if meta.get("requested", {}).get("vocal_type") == "instrumental":
            continue
        fp = os.path.join(AUDIO_DIR, f"{track_id}.mp3")
        if not os.path.exists(fp):
            continue
        pending.append((track_id, fp, meta.get("prompt", ""), meta.get("lyrics_sections")))

    if args.limit is not None:
        pending = pending[: args.limit]

    print(f"[INFO] {len(pending)} vocal tracks to process this run, workers={args.workers}", flush=True)
    if not pending:
        return

    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)

    import multiprocessing as mp
    ctx = mp.get_context("spawn")

    started = time.time()
    n_done = 0
    with ctx.Pool(processes=args.workers, initializer=_worker_init) as pool, open(OUT_PATH, "a", encoding="utf-8") as out_f:
        for track_id, analysis, error, elapsed in pool.imap_unordered(_worker_task, pending):
            meta_path = os.path.join(METADATA_DIR, f"{track_id}.json")
            category = None
            flow_title = None
            if os.path.exists(meta_path):
                with open(meta_path, encoding="utf-8") as f:
                    meta = json.load(f)
                category = meta.get("requested", {}).get("category")
                flow_title = meta.get("flow_title")

            record = {
                "track_id": track_id,
                "category": category,
                "flow_title": flow_title,
                "elapsed_s": round(elapsed, 2),
                "error": error,
                **analysis,
            }
            out_f.write(json.dumps(record, ensure_ascii=False) + "\n")
            out_f.flush()

            n_done += 1
            flag = " <== PHRASE MISMATCH CANDIDATE" if analysis["verdict"] == "PHRASE_MISMATCH_CANDIDATE" else ""
            total_elapsed = time.time() - started
            rate = n_done / total_elapsed if total_elapsed else 0
            eta_s = (len(pending) - n_done) / rate if rate else 0
            print(
                f"[{n_done}/{len(pending)}] {track_id} ({elapsed:.1f}s) verdict={analysis['verdict']} "
                f"bars={analysis['total_bars']} mod4={analysis['bars_mod4']} | "
                f"elapsed={total_elapsed/60:.1f}min eta={eta_s/60:.1f}min{flag}",
                flush=True,
            )


if __name__ == "__main__":
    main()
