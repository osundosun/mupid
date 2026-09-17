# -*- coding: utf-8 -*-
"""
albums.json에 album_release_date를 추가한다.
규칙(사용자 확정): 소속 트랙들의 release_date(downloads/metadata에 이미 반영됨) 중 최댓값(max).
"""
import json
import os
import shutil
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
METADATA_DIR = os.path.join(ROOT, "downloads", "metadata")
ALBUMS_PATH = os.path.join(ROOT, "albums.json")


def main():
    albums = json.load(open(ALBUMS_PATH, encoding="utf-8"))
    print(f"[INFO] total albums: {len(albums)}")

    already_has = [a["album_key"] for a in albums if "album_release_date" in a]
    if already_has:
        print(f"[FAIL] {len(already_has)} albums already have album_release_date, aborting: {already_has[:10]}")
        return

    # track_id -> release_date 조회 테이블
    track_release_date = {}
    for fname in sorted(f for f in os.listdir(METADATA_DIR) if f.endswith(".json")):
        meta = json.load(open(os.path.join(METADATA_DIR, fname), encoding="utf-8"))
        rd = meta.get("release_date")
        assert rd, f"{fname}: release_date missing (run add_release_dates.py first)"
        track_release_date[meta["track_id"]] = rd

    computed = {}
    for album in albums:
        dates = [track_release_date[tid] for tid in album["track_ids"]]
        computed[album["album_key"]] = max(dates)

    backup_path = ALBUMS_PATH + ".bak_before_release_dates_" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    shutil.copyfile(ALBUMS_PATH, backup_path)
    print(f"[OK] backup created: {backup_path}")

    for album in albums:
        album["album_release_date"] = computed[album["album_key"]]

    tmp_path = ALBUMS_PATH + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as f:
        json.dump(albums, f, ensure_ascii=False, indent=2)
    os.replace(tmp_path, ALBUMS_PATH)
    print(f"[OK] added album_release_date on {len(albums)} albums")

    # 전수 검증
    backup_albums = json.load(open(backup_path, encoding="utf-8"))
    after_albums = json.load(open(ALBUMS_PATH, encoding="utf-8"))
    assert len(after_albums) == len(albums) == len(backup_albums)

    mismatched = []
    other_changed = []
    for before, after in zip(backup_albums, after_albums):
        assert before["album_key"] == after["album_key"]
        if after.get("album_release_date") != computed[after["album_key"]]:
            mismatched.append(after["album_key"])
        after_without = dict(after)
        after_without.pop("album_release_date", None)
        if after_without != before:
            other_changed.append(after["album_key"])

    if mismatched:
        print(f"[FAIL] {len(mismatched)} albums have wrong album_release_date: {mismatched[:10]}")
        return
    print(f"[OK] all {len(after_albums)} albums have exactly the planned album_release_date")

    if other_changed:
        print(f"[FAIL] {len(other_changed)} albums changed other fields: {other_changed[:10]}")
        return
    print("[OK] all other fields unchanged")

    dates = sorted(computed.values())
    print(f"[INFO] distinct album_release_date values: {len(set(dates))} (range {dates[0]} ~ {dates[-1]})")
    print()
    print("DONE.")


if __name__ == "__main__":
    main()
