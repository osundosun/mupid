"""Repoint metadata.assets.{audio,cover}.path (and size_bytes/sha256) to the
surviving base-named files, for track_ids where the user manually deleted
the "_N" duplicate files that metadata used to point to.

Backs up the affected metadata files before editing. Writes via .tmp +
os.replace for atomicity.
"""
import hashlib
import json
import os
import shutil
from datetime import datetime, timezone

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
METADATA_DIR = os.path.join(PROJECT_ROOT, "downloads", "metadata")
AUDIO_DIR = os.path.join(PROJECT_ROOT, "downloads", "audio")
COVER_DIR = os.path.join(PROJECT_ROOT, "downloads", "covers")

TRACK_IDS = ["T000350", "T000746", "T000847", "T000987", "T000992", "T001026"]


def sha256_of(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def main():
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup_dir = os.path.join(PROJECT_ROOT, "downloads", f"metadata.bak_before_orphan_path_fix_{ts}")
    os.makedirs(backup_dir, exist_ok=True)

    for tid in TRACK_IDS:
        meta_path = os.path.join(METADATA_DIR, f"{tid}.json")
        shutil.copy2(meta_path, os.path.join(backup_dir, f"{tid}.json"))

        with open(meta_path, encoding="utf-8") as f:
            data = json.load(f)

        audio_path = os.path.join(AUDIO_DIR, f"{tid}.mp3")
        cover_path = os.path.join(COVER_DIR, f"{tid}.jpg")

        old_audio = data["assets"]["audio"].copy()
        old_cover = data["assets"]["cover"].copy()

        data["assets"]["audio"]["path"] = f"downloads/audio/{tid}.mp3"
        data["assets"]["audio"]["size_bytes"] = os.path.getsize(audio_path)
        data["assets"]["audio"]["sha256"] = sha256_of(audio_path)

        data["assets"]["cover"]["path"] = f"downloads/covers/{tid}.jpg"
        data["assets"]["cover"]["size_bytes"] = os.path.getsize(cover_path)
        data["assets"]["cover"]["sha256"] = sha256_of(cover_path)

        tmp_path = meta_path + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        os.replace(tmp_path, meta_path)

        print(f"[OK] {tid}:")
        print(f"     audio: {old_audio['path']} (size={old_audio['size_bytes']}) -> {data['assets']['audio']['path']} (size={data['assets']['audio']['size_bytes']})")
        print(f"     cover: {old_cover['path']} (size={old_cover['size_bytes']}) -> {data['assets']['cover']['path']} (size={data['assets']['cover']['size_bytes']})")

    print()
    print(f"Backup of original 6 metadata files: {backup_dir}")


if __name__ == "__main__":
    main()
