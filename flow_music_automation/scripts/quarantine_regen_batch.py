"""
Quarantine + reset-to-WAITING for the 10 tracks being regenerated this round:
  - 267/277/287/317/318: female->male vocal prompt swap (rock_edge, moving into 언더독)
  - 2/14/16/17/18: prompt borrowed from a sibling track in the same album (quality/intro-lyrics issue)

Same pattern as scripts/quarantine_meter_mismatch.py (files MOVED not deleted).
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

TRACK_REASONS = {
    "T000002": "prompt_borrow_from_T000101: intro-lyrics/quality issue, replaced with sibling album prompt",
    "T000014": "prompt_borrow_from_T000001: intro-lyrics/quality issue, replaced with sibling album prompt",
    "T000016": "prompt_borrow_from_T000026: intro-lyrics/quality issue, replaced with sibling album prompt",
    "T000017": "prompt_borrow_from_T000027: intro-lyrics/quality issue, replaced with sibling album prompt",
    "T000018": "prompt_borrow_from_T000028: intro-lyrics/quality issue, replaced with sibling album prompt",
    "T000267": "gender_flip_female_to_male: moving into male_korean_rock_edge_01(언더독)",
    "T000277": "gender_flip_female_to_male: moving into male_korean_rock_edge_01(언더독)",
    "T000287": "gender_flip_female_to_male: moving into male_korean_rock_edge_01(언더독)",
    "T000317": "gender_flip_female_to_male: moving into male_korean_rock_edge_01(언더독)",
    "T000318": "gender_flip_female_to_male: moving into male_korean_rock_edge_01(언더독)",
}
TRACK_IDS = list(TRACK_REASONS.keys())


def track_id_to_prompt_id(track_id):
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
                "detail": f"reason={TRACK_REASONS[track_id]}, "
                          f"original files quarantined to quarantine/{track_id}/{ts}/",
            }, ensure_ascii=False) + "\n")

        print(f"[OK] {track_id} ({prompt_id}): quarantined + reset to WAITING")

    tmp_path = STATE_PATH + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as f:
        f.write(json.dumps(state, ensure_ascii=False, indent=2))
    os.replace(tmp_path, STATE_PATH)

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
        print(f"[OK] Removed {removed} stale entries from {METER_SCAN_PATH}")

    print()
    print(f"Done. {len(moved)} files moved to quarantine/*/{ts}/, {len(TRACK_IDS)} prompts reset to WAITING.")


if __name__ == "__main__":
    main()
