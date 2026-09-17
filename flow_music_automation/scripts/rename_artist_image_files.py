# -*- coding: utf-8 -*-
"""
아티스트 이름 변경(쾌속선->샛별, 여린볕->결)에 맞춰 이미지 파일명 + manifest.json +
artist_mapping.md도 같이 갱신한다. source_path/source_file(원본 업로드 출처 기록)은
과거 사실이라 건드리지 않고, artist_name/output_name(현재 식별자)만 바꾼다.
"""
import hashlib
import json
import os
import shutil
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
IMG_DIR = os.path.join(ROOT, "artists", "artists_images")
MANIFEST_PATH = os.path.join(ROOT, "artists", "manifest.json")
MAPPING_PATH = os.path.join(ROOT, "artists", "artist_mapping.md")

RENAMES = {
    "쾌속선": "샛별",
    "여린볕": "결",
}


def sha256_of(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def main():
    # 사전 검증: 새 이름 파일이 이미 존재하면 안 됨
    for old, new in RENAMES.items():
        old_path = os.path.join(IMG_DIR, f"{old}.png")
        new_path = os.path.join(IMG_DIR, f"{new}.png")
        assert os.path.exists(old_path), f"missing: {old_path}"
        assert not os.path.exists(new_path), f"already exists: {new_path}"

    hashes_before = {old: sha256_of(os.path.join(IMG_DIR, f"{old}.png")) for old in RENAMES}

    # 파일명 변경 (내용은 그대로, rename만)
    for old, new in RENAMES.items():
        os.rename(os.path.join(IMG_DIR, f"{old}.png"), os.path.join(IMG_DIR, f"{new}.png"))
    print(f"[OK] renamed {len(RENAMES)} image files")

    for old, new in RENAMES.items():
        new_hash = sha256_of(os.path.join(IMG_DIR, f"{new}.png"))
        assert new_hash == hashes_before[old], f"{new}.png content changed unexpectedly!"
    print("[OK] file content unchanged after rename (sha256 verified)")

    # manifest.json 갱신 (artist_name, output_name만; source_path/source_file/sha256/file_id는
    # 원본 업로드 출처 기록이므로 그대로 둔다)
    backup_manifest = MANIFEST_PATH + ".bak_before_rename_" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    shutil.copyfile(MANIFEST_PATH, backup_manifest)
    print(f"[OK] manifest backup: {backup_manifest}")

    manifest = json.load(open(MANIFEST_PATH, encoding="utf-8"))
    changed_records = []
    for r in manifest["records"]:
        if r["artist_name"] in RENAMES:
            old = r["artist_name"]
            new = RENAMES[old]
            r["artist_name"] = new
            r["output_name"] = f"{new}.png"
            changed_records.append((old, new))

    assert set(o for o, n in changed_records) == set(RENAMES.keys()), changed_records
    tmp = MANIFEST_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    os.replace(tmp, MANIFEST_PATH)
    print(f"[OK] manifest.json updated: {changed_records}")

    # 전수 검증: manifest 87건 유지, sha256/file_id/source_* 등 나머지 필드 불변
    manifest_after = json.load(open(MANIFEST_PATH, encoding="utf-8"))
    manifest_before = json.load(open(backup_manifest, encoding="utf-8"))
    assert len(manifest_after["records"]) == len(manifest_before["records"]) == 87
    for b, a in zip(manifest_before["records"], manifest_after["records"]):
        assert b["artist_key"] == a["artist_key"]
        if b["artist_name"] in RENAMES:
            assert a["artist_name"] == RENAMES[b["artist_name"]]
            assert a["output_name"] == f"{RENAMES[b['artist_name']]}.png"
            for field in ("source_path", "source_file", "sha256", "file_id", "library_file_id", "width", "height"):
                assert a[field] == b[field], f"{a['artist_key']}: {field} changed unexpectedly"
        else:
            assert a == b, f"{a['artist_key']}: unrelated record changed"
    print("[OK] all 87 manifest records verified (only the 2 intended renamed, rest byte-identical)")

    # artist_mapping.md 갱신 (표에서 이름/파일명 칸만 치환)
    backup_mapping = MAPPING_PATH + ".bak_before_rename_" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    shutil.copyfile(MAPPING_PATH, backup_mapping)
    text = open(MAPPING_PATH, encoding="utf-8").read()
    for old, new in RENAMES.items():
        text = text.replace(f"| {old} |", f"| {new} |")
        text = text.replace(f"{old}.png", f"{new}.png")
    with open(MAPPING_PATH, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"[OK] artist_mapping.md updated (backup: {backup_mapping})")

    print()
    print("DONE.")


if __name__ == "__main__":
    main()
