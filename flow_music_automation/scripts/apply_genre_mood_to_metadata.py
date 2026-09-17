# -*- coding: utf-8 -*-
"""
genre_mood_plan.json(build_genre_mood_plan.py 산출물)을 downloads/metadata/T*.json
1060개에 반영한다. 백엔드 DB(music_genres/music_moods)로 넘길 가상값 필드를
release_date 추가 때와 동일한 절차로 붙인다: 백업 -> 원자적 적용 -> 전수 검증.
"""
import json
import os
import shutil
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
METADATA_DIR = os.path.join(ROOT, "downloads", "metadata")
PLAN_PATH = os.path.join(ROOT, "genre_mood_plan.json")


def main():
    plan = json.load(open(PLAN_PATH, encoding="utf-8"))
    plan_by_id = {p["track_id"]: p for p in plan}
    print(f"[INFO] plan entries: {len(plan)}")

    all_meta_files = sorted(f for f in os.listdir(METADATA_DIR) if f.endswith(".json"))
    print(f"[INFO] total metadata files: {len(all_meta_files)}")

    already_has = []
    missing_in_plan = []
    for fname in all_meta_files:
        meta = json.load(open(os.path.join(METADATA_DIR, fname), encoding="utf-8"))
        if "genre" in meta or "mood" in meta:
            already_has.append(fname)
        if meta["track_id"] not in plan_by_id:
            missing_in_plan.append(meta["track_id"])

    if already_has:
        print(f"[FAIL] {len(already_has)} files already have genre/mood, aborting: {already_has[:10]}")
        return
    if missing_in_plan:
        print(f"[FAIL] {len(missing_in_plan)} track_ids missing from plan: {missing_in_plan[:10]}")
        return
    print("[OK] no existing genre/mood fields, all files covered by plan")

    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup_dir = os.path.join(ROOT, "downloads", f"metadata.bak_before_genre_mood_{ts}")
    shutil.copytree(METADATA_DIR, backup_dir)
    print(f"[OK] backup created: {backup_dir}")

    for fname in all_meta_files:
        meta_path = os.path.join(METADATA_DIR, fname)
        with open(meta_path, encoding="utf-8") as f:
            meta = json.load(f)
        entry = plan_by_id[meta["track_id"]]
        meta["genre"] = entry["genre"]
        meta["mood"] = entry["mood"]
        tmp_path = meta_path + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)
        os.replace(tmp_path, meta_path)

    print(f"[OK] added genre/mood on {len(all_meta_files)} files")

    # 전수 검증
    files_after = sorted(f for f in os.listdir(METADATA_DIR) if f.endswith(".json"))
    assert len(files_after) == len(all_meta_files), f"file count changed: {len(files_after)}"

    parse_fail = []
    mismatched = []
    other_field_changed = []
    for fname in files_after:
        meta_path = os.path.join(METADATA_DIR, fname)
        backup_path = os.path.join(backup_dir, fname)
        try:
            meta = json.load(open(meta_path, encoding="utf-8"))
        except Exception as e:
            parse_fail.append((fname, str(e)))
            continue
        entry = plan_by_id[meta["track_id"]]
        if meta.get("genre") != entry["genre"] or meta.get("mood") != entry["mood"]:
            mismatched.append(fname)
        backup_meta = json.load(open(backup_path, encoding="utf-8"))
        after_without = dict(meta)
        after_without.pop("genre", None)
        after_without.pop("mood", None)
        if after_without != backup_meta:
            other_field_changed.append(fname)

    if parse_fail:
        print(f"[FAIL] {len(parse_fail)} parse failures: {parse_fail[:10]}")
        return
    print(f"[OK] all {len(files_after)} files parse successfully")

    if mismatched:
        print(f"[FAIL] {len(mismatched)} files have wrong genre/mood: {mismatched[:10]}")
        return
    print(f"[OK] all {len(files_after)} files have exactly the planned genre/mood")

    if other_field_changed:
        print(f"[FAIL] {len(other_field_changed)} files changed other fields: {other_field_changed[:10]}")
        return
    print("[OK] all other fields unchanged (byte-equivalent to backup minus genre/mood)")

    genres = sorted(set(p["genre"] for p in plan))
    with_mood = sum(1 for p in plan if p["mood"])
    print(f"[INFO] distinct genre values: {len(genres)}")
    print(f"[INFO] tracks with non-null mood: {with_mood}/{len(plan)}")
    print()
    print("DONE.")


if __name__ == "__main__":
    main()
