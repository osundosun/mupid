"""After regenerating the 19 quarantined meter-mismatch tracks and
re-scanning them, permanently delete the quarantined ORIGINAL files only
for track_ids that are now confirmed 4/4_OK. Track_ids still showing
3/4_MISMATCH keep their quarantined original untouched (nothing to
compare against otherwise), and are printed out for a decision on the
next retry.
"""
import glob
import json
import os
import shutil

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
QUARANTINE_DIR = os.path.join(PROJECT_ROOT, "quarantine")
METER_SCAN_PATH = os.path.join(PROJECT_ROOT, "logs", "meter_scan_result.jsonl")

TRACK_IDS = [
    "T000947",
]


def main():
    verdicts = {}
    with open(METER_SCAN_PATH, encoding="utf-8") as f:
        for line in f:
            rec = json.loads(line)
            if rec["track_id"] in TRACK_IDS:
                verdicts[rec["track_id"]] = rec["verdict"]

    still_bad = []
    deleted = []
    for tid in TRACK_IDS:
        verdict = verdicts.get(tid)
        quarantine_dirs = sorted(glob.glob(os.path.join(QUARANTINE_DIR, tid, "*")))
        if verdict == "4/4_OK":
            for d in quarantine_dirs:
                shutil.rmtree(d)
                deleted.append(d)
            print(f"[DELETED] {tid}: confirmed 4/4_OK -> removed quarantined original(s): {quarantine_dirs}")
        else:
            still_bad.append((tid, verdict, quarantine_dirs))
            print(f"[KEPT] {tid}: verdict={verdict} -> quarantined original(s) kept: {quarantine_dirs}")

    print()
    print(f"Deleted {len(deleted)} quarantine dirs for confirmed-fixed tracks.")
    print(f"Still unresolved ({len(still_bad)}): {[t[0] for t in still_bad]}")


if __name__ == "__main__":
    main()
