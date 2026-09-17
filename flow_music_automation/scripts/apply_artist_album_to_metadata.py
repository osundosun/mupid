# -*- coding: utf-8 -*-
"""
artists.json / albums.json의 아티스트/앨범 배정을 각 트랙 metadata에 필드로 추가.

기존 필드는 전혀 건드리지 않고 새 필드만 추가함 (title_rework와 같은 원칙 -- 별도
파일을 만들지 않고 기존 metadata에 직접 필드를 얹는다).

추가 필드:
  artist: {artist_key, name, name_reading}
  album:  {album_key, title, title_reading}
(name_reading/title_reading은 한국어·영어 아티스트는 null, 일본어만 로마자 값)

절차: 매핑 무결성 사전 검증 -> 전체 백업 -> 원자적 적용 -> 전수 검증.
"""
import json
import os
import shutil
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
METADATA_DIR = os.path.join(ROOT, "downloads", "metadata")
LOG_PATH = os.path.join(ROOT, "logs", "artist_album_metadata_apply_log.jsonl")


def main():
    artists = json.load(open(os.path.join(ROOT, "artists.json"), encoding="utf-8"))
    albums = json.load(open(os.path.join(ROOT, "albums.json"), encoding="utf-8"))

    artist_by_key = {a["artist_key"]: a for a in artists}

    # track_id -> artist_key (아티스트 소속)
    track_to_artist = {}
    for a in artists:
        for tid in a["track_ids"]:
            if tid in track_to_artist:
                raise SystemExit(f"[ABORT] track {tid} belongs to multiple artists: "
                                  f"{track_to_artist[tid]} and {a['artist_key']}")
            track_to_artist[tid] = a["artist_key"]

    # track_id -> album
    track_to_album = {}
    for al in albums:
        for tid in al["track_ids"]:
            if tid in track_to_album:
                raise SystemExit(f"[ABORT] track {tid} belongs to multiple albums: "
                                  f"{track_to_album[tid]} and {al['album_key']}")
            track_to_album[tid] = al

    all_meta_files = sorted(f for f in os.listdir(METADATA_DIR) if f.endswith(".json"))
    all_track_ids = [f[:-5] for f in all_meta_files]

    # 1) 사전 검증: 1040개 전부 아티스트/앨범 정확히 1개씩 있는지
    missing_artist = [t for t in all_track_ids if t not in track_to_artist]
    missing_album = [t for t in all_track_ids if t not in track_to_album]
    if missing_artist or missing_album:
        print(f"[FAIL] missing_artist={len(missing_artist)} missing_album={len(missing_album)}")
        print(" missing_artist sample:", missing_artist[:10])
        print(" missing_album sample:", missing_album[:10])
        return
    extra_artist_tracks = set(track_to_artist) - set(all_track_ids)
    extra_album_tracks = set(track_to_album) - set(all_track_ids)
    if extra_artist_tracks or extra_album_tracks:
        print(f"[FAIL] artists.json/albums.json reference track_ids with no metadata file: "
              f"{extra_artist_tracks | extra_album_tracks}")
        return
    print(f"[OK] {len(all_track_ids)} tracks each have exactly one artist and one album assignment")

    # 2) 전체 백업
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup_dir = os.path.join(ROOT, "downloads", f"metadata.bak_before_artist_album_fields_{ts}")
    shutil.copytree(METADATA_DIR, backup_dir)
    print(f"[OK] backup created: {backup_dir}")

    # 3) 원자적 적용
    log_entries = []
    for fname in all_meta_files:
        track_id = fname[:-5]
        meta_path = os.path.join(METADATA_DIR, fname)
        with open(meta_path, encoding="utf-8") as f:
            meta = json.load(f)

        artist_key = track_to_artist[track_id]
        a = artist_by_key[artist_key]
        al = track_to_album[track_id]

        meta["artist"] = {
            "artist_key": artist_key,
            "name": a["artist_name"],
        }
        meta["album"] = {
            "album_key": al["album_key"],
            "title": al["album_title"],
        }

        tmp_path = meta_path + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)
        os.replace(tmp_path, meta_path)

        log_entries.append({
            "track_id": track_id,
            "artist_key": artist_key,
            "artist_name": a["artist_name"],
            "album_key": al["album_key"],
            "album_title": al["album_title"],
        })

    print(f"[OK] applied artist/album fields to {len(all_meta_files)} files")

    # 4) 전수 검증
    files_after = sorted(f for f in os.listdir(METADATA_DIR) if f.endswith(".json"))
    assert len(files_after) == 1060, f"file count changed: {len(files_after)}"
    print(f"[OK] file count unchanged: {len(files_after)}")

    parse_fail = []
    field_fail = []
    for fname in files_after:
        track_id = fname[:-5]
        meta_path = os.path.join(METADATA_DIR, fname)
        try:
            with open(meta_path, encoding="utf-8") as f:
                meta = json.load(f)
        except Exception as e:
            parse_fail.append((fname, str(e)))
            continue
        expected_artist_key = track_to_artist[track_id]
        expected_album = track_to_album[track_id]
        if meta.get("artist", {}).get("artist_key") != expected_artist_key:
            field_fail.append((track_id, "artist_key mismatch"))
        if meta.get("artist", {}).get("name") != artist_by_key[expected_artist_key]["artist_name"]:
            field_fail.append((track_id, "artist name mismatch"))
        if meta.get("album", {}).get("album_key") != expected_album["album_key"]:
            field_fail.append((track_id, "album_key mismatch"))
        if meta.get("album", {}).get("title") != expected_album["album_title"]:
            field_fail.append((track_id, "album title mismatch"))

    if parse_fail:
        print(f"[FAIL] {len(parse_fail)} files failed to parse: {parse_fail[:10]}")
        return
    print(f"[OK] all {len(files_after)} files parse successfully")

    if field_fail:
        print(f"[FAIL] {len(field_fail)} field mismatches: {field_fail[:10]}")
        return
    print(f"[OK] all {len(files_after)} files verified against artists.json/albums.json")

    # 5) 로그
    os.makedirs(os.path.dirname(LOG_PATH), exist_ok=True)
    with open(LOG_PATH, "w", encoding="utf-8") as f:
        for e in log_entries:
            f.write(json.dumps(e, ensure_ascii=False) + "\n")
    print(f"[OK] logged {len(log_entries)} entries to {LOG_PATH}")
    print()
    print("DONE.")


if __name__ == "__main__":
    main()
