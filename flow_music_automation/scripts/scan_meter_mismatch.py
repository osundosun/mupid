"""Batch-scan all downloaded songs for a 3/4-vs-4/4 meter mismatch using
madmom's DBNDownBeatTrackingProcessor (beats_per_bar=[3,4]), parallelized
across worker processes.

Every prompt in prompts.jsonl explicitly requires "Strict 4/4 throughout".
A song that actually renders in 3/4 (waltz feel) is a real defect, most
audible in vocal tracks where the lyric phrasing was written assuming 4/4.

Usage:
    .venv\\Scripts\\python.exe scripts\\scan_meter_mismatch.py [--limit N] [--redo] [--workers N]

Writes/updates logs/meter_scan_result.jsonl (one JSON object per track_id).
Safe to re-run: it skips track_ids already present in the output file
(unless --redo). Results are written by the main process only (workers
return records over a queue), so the JSONL file is never corrupted by
concurrent writers.
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import sys
import time
import warnings

warnings.filterwarnings("ignore")

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AUDIO_DIR = os.path.join(PROJECT_ROOT, "downloads", "audio")
METADATA_DIR = os.path.join(PROJECT_ROOT, "downloads", "metadata")
OUT_PATH = os.path.join(PROJECT_ROOT, "logs", "meter_scan_result.jsonl")

_FFMPEG_BIN = (
    r"C:\Users\SSAFY\AppData\Local\Microsoft\WinGet\Packages"
    r"\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-9.0.1-full_build\bin"
)


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


_worker_proc = None
_worker_dbn = None


def _worker_init():
    global _worker_proc, _worker_dbn
    _apply_compat_shims()
    from madmom.features.downbeats import RNNDownBeatProcessor, DBNDownBeatTrackingProcessor
    _worker_proc = RNNDownBeatProcessor()
    _worker_dbn = DBNDownBeatTrackingProcessor(beats_per_bar=[3, 4], fps=100)


def _analyze_one(mp3_path):
    import numpy as np
    act = _worker_proc(mp3_path)
    result = _worker_dbn(act)
    positions = result[:, 1].astype(int)
    bar_lengths = []
    for i in range(1, len(positions)):
        if positions[i] == 1:
            bar_lengths.append(positions[i - 1])
    if not bar_lengths and len(positions):
        bar_lengths.append(int(positions.max()))
    bar_lengths = np.array(bar_lengths)
    total = len(bar_lengths)
    n3 = int(np.sum(bar_lengths == 3))
    n4 = int(np.sum(bar_lengths == 4))
    frac3 = n3 / total if total else 0.0
    if total == 0:
        verdict = "NO_BARS_DETECTED"
    elif frac3 > 0.6:
        verdict = "3/4_MISMATCH"
    elif frac3 < 0.2:
        verdict = "4/4_OK"
    else:
        verdict = "MIXED/UNSTABLE"
    return {"total_bars": total, "n3": n3, "n4": n4, "frac3": round(frac3, 3), "verdict": verdict}


def _worker_task(args):
    track_id, fp = args
    t0 = time.time()
    try:
        analysis = _analyze_one(fp)
        error = None
    except Exception as e:
        analysis = {"total_bars": 0, "n3": 0, "n4": 0, "frac3": 0.0, "verdict": "ANALYSIS_FAILED"}
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
    ap.add_argument("--redo", action="store_true", help="Re-scan track_ids already present in the output file.")
    ap.add_argument("--workers", type=int, default=10)
    args = ap.parse_args()

    mp3_files = sorted(glob.glob(os.path.join(AUDIO_DIR, "T*.mp3")))
    done = set() if args.redo else load_done_ids()

    pending = []
    for fp in mp3_files:
        track_id = os.path.basename(fp).replace(".mp3", "")
        if track_id in done:
            continue
        pending.append((track_id, fp))

    if args.limit is not None:
        pending = pending[: args.limit]

    print(f"[INFO] {len(mp3_files)} total mp3 files, {len(done)} already scanned, {len(pending)} to process this run, workers={args.workers}", flush=True)

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
            vocal_type = None
            category = None
            flow_title = None
            if os.path.exists(meta_path):
                with open(meta_path, encoding="utf-8") as f:
                    meta = json.load(f)
                vocal_type = meta.get("requested", {}).get("vocal_type")
                category = meta.get("requested", {}).get("category")
                flow_title = meta.get("flow_title")

            record = {
                "track_id": track_id,
                "category": category,
                "vocal_type": vocal_type,
                "flow_title": flow_title,
                "elapsed_s": round(elapsed, 2),
                "error": error,
                **analysis,
            }
            out_f.write(json.dumps(record, ensure_ascii=False) + "\n")
            out_f.flush()

            n_done += 1
            flag = " <== 3/4 MISMATCH" if analysis["verdict"] == "3/4_MISMATCH" else ""
            total_elapsed = time.time() - started
            rate = n_done / total_elapsed if total_elapsed else 0
            eta_s = (len(pending) - n_done) / rate if rate else 0
            print(
                f"[{n_done}/{len(pending)}] {track_id} ({elapsed:.1f}s) verdict={analysis['verdict']} "
                f"frac3={analysis['frac3']} | elapsed={total_elapsed/60:.1f}min eta={eta_s/60:.1f}min{flag}",
                flush=True,
            )


if __name__ == "__main__":
    main()
