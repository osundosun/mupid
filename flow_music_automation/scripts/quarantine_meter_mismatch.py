"""Quarantine the 19 tracks found to be in 3/4 (waltz) instead of the
required 4/4, and reset their prompts.jsonl entries back to WAITING in
queue_state.json so run_queue.py will regenerate them with a fresh Flow
session (same prompt text -- reused as-is, per user decision).

Files are MOVED (never deleted) to quarantine/{track_id}/{timestamp}/, so
originals stay recoverable until the regenerated result is confirmed 4/4.

Usage:
    .venv\\Scripts\\python.exe scripts\\quarantine_meter_mismatch.py
"""
import json
import os
import shutil
from datetime import datetime, timezone

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AUDIO_DIR = os.path.join(PROJECT_ROOT, "downloads", "audio")
COVER_DIR = os.path.join(PROJECT_ROOT, "downloads", "covers")
METADATA_DIR = os.path.join(PROJECT_ROOT, "downloads", "metadata")
QUARANTINE_DIR = os.path.join(PROJECT_ROOT, "quarantine")
STATE_PATH = os.path.join(PROJECT_ROOT, "logs", "queue_state.json")
EVENTS_LOG_PATH = os.path.join(PROJECT_ROOT, "logs", "queue_events.jsonl")
METER_SCAN_PATH = os.path.join(PROJECT_ROOT, "logs", "meter_scan_result.jsonl")

TRACK_IDS = [
    "T000794",
]


def track_id_to_prompt_id(track_id):
    # T000206 -> 206 -> P0206 ; T001012 -> 1012 -> P1012 (inverse of run_queue.track_id_for)
    num = int(track_id.lstrip("T"))
    return "P" + str(num).zfill(4)


def now_iso():
    return datetime.now(timezone.utc).isoformat()


def main():
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")

    state = json.loads(open(STATE_PATH, encoding="utf-8").read())

    moved = []
    for track_id in TRACK_IDS:
        dest_dir = os.path.join(QUARANTINE_DIR, track_id, ts)
        os.makedirs(dest_dir, exist_ok=True)

        for src_dir, ext in [(AUDIO_DIR, ".mp3"), (COVER_DIR, ".jpg"), (METADATA_DIR, ".json")]:
            src = os.path.join(src_dir, f"{track_id}{ext}")
            if os.path.exists(src):
                dest = os.path.join(dest_dir, f"{track_id}{ext}")
                shutil.move(src, dest)
                moved.append((track_id, src, dest))
            else:
                print(f"[WARN] {track_id}: expected file not found, skipping: {src}")

        prompt_id = track_id_to_prompt_id(track_id)
        rec = state["tracks"].get(prompt_id)
        if rec is None:
            print(f"[ERROR] {prompt_id} not found in queue_state.json tracks -- ABORTING this one")
            continue
        if rec["status"] != "COMPLETED":
            print(f"[WARN] {prompt_id} status is {rec['status']!r}, not COMPLETED -- resetting anyway")

        old_status = rec["status"]
        fresh = {
            "track_id": rec["track_id"],
            "batch_id": rec["batch_id"],
            "batch_position": rec["batch_position"],
            "status": "WAITING",
            "flow_title": None,
            "new_titles": [],
            "selected_card_index": None,
            "generated_card_count": None,
            "audio_path": None,
            "cover_path": None,
            "session_url": None,
            "song_url": None,
            "song_id": None,
            "sound_raw": None,
            "lyrics_raw": None,
            "lyrics_present": False,
            "metadata_path": None,
            "error_code": None,
            "result_card_count": None,
            "detected_at": None,
            "retry_session_urls": rec.get("retry_session_urls", []),
            "retry_count": rec.get("retry_count", 0),
            "created_at": rec["created_at"],
            "updated_at": now_iso(),
        }
        state["tracks"][prompt_id] = fresh

        with open(EVENTS_LOG_PATH, "a", encoding="utf-8") as f:
            f.write(json.dumps({
                "ts": now_iso(),
                "prompt_id": prompt_id,
                "track_id": track_id,
                "batch_id": rec["batch_id"],
                "from_status": old_status,
                "to_status": "WAITING",
                "detail": f"reason=meter_mismatch_requeue: 3/4 time signature detected (frac3=1.0), "
                          f"original files quarantined to quarantine/{track_id}/{ts}/, regenerating with same prompt",
            }, ensure_ascii=False) + "\n")

        print(f"[OK] {track_id} ({prompt_id}): quarantined + reset to WAITING")

    tmp_path = STATE_PATH + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as f:
        f.write(json.dumps(state, ensure_ascii=False, indent=2))
    os.replace(tmp_path, STATE_PATH)

    # Remove these track_ids from the meter scan result so the scanner will
    # naturally re-check them once the regenerated files exist.
    if os.path.exists(METER_SCAN_PATH):
        kept = []
        removed = 0
        with open(METER_SCAN_PATH, encoding="utf-8") as f:
            for line in f:
                rec = json.loads(line)
                if rec["track_id"] in TRACK_IDS:
                    removed += 1
                else:
                    kept.append(line)
        with open(METER_SCAN_PATH, "w", encoding="utf-8") as f:
            f.writelines(kept)
        print(f"[OK] Removed {removed} stale entries from {METER_SCAN_PATH} (will be re-scanned after regeneration)")

    print()
    print(f"Done. {len(moved)} files moved to quarantine/*/{ts}/, {len(TRACK_IDS)} prompts reset to WAITING.")


if __name__ == "__main__":
    main()
