# -*- coding: utf-8 -*-
"""
artists.json에 profile_image 필드 추가.
downloads/metadata의 assets.cover 관례(path/size_bytes/sha256/mime_type/width/height)를
그대로 따름 -- path는 프로젝트 루트 기준 상대경로.

manifest.json(artists/manifest.json)이 이미 sha256/width/height를 갖고 있으므로
그대로 가져다 쓴다(재계산 불필요, 이미 전수 검증된 값).
"""
import hashlib
import json
import os
import shutil
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ARTISTS_PATH = os.path.join(ROOT, "artists.json")
MANIFEST_PATH = os.path.join(ROOT, "artists", "manifest.json")
IMG_DIR = os.path.join(ROOT, "artists", "artists_images")


def main():
    artists = json.load(open(ARTISTS_PATH, encoding="utf-8"))
    manifest = json.load(open(MANIFEST_PATH, encoding="utf-8"))

    manifest_by_key = {r["artist_key"]: r for r in manifest["records"]}

    # 사전 검증: 87명 전부 manifest에 매칭되는지, 파일이 실제로 존재하고 sha256이 manifest와 일치하는지
    missing = [a["artist_key"] for a in artists if a["artist_key"] not in manifest_by_key]
    assert not missing, f"manifest missing for: {missing}"
    already_has = [a["artist_key"] for a in artists if "profile_image" in a]
    assert not already_has, f"already has profile_image: {already_has}"

    for a in artists:
        r = manifest_by_key[a["artist_key"]]
        img_path = os.path.join(IMG_DIR, r["output_name"])
        assert os.path.exists(img_path), f"missing file: {img_path}"
        actual_sha256 = hashlib.sha256(open(img_path, "rb").read()).hexdigest()
        assert actual_sha256 == r["sha256"], f"{a['artist_key']}: sha256 mismatch vs manifest"

    print(f"[OK] all {len(artists)} artists matched to manifest, files exist, sha256 verified")

    backup_path = ARTISTS_PATH + ".bak_before_profile_image_" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    shutil.copyfile(ARTISTS_PATH, backup_path)
    print(f"[OK] backup: {backup_path}")

    planned = {}
    for a in artists:
        r = manifest_by_key[a["artist_key"]]
        rel_path = f"artists/artists_images/{r['output_name']}"
        profile_image = {
            "path": rel_path,
            "size_bytes": os.path.getsize(os.path.join(ROOT, rel_path)),
            "sha256": r["sha256"],
            "mime_type": "image/png",
            "width": r["width"],
            "height": r["height"],
        }
        a["profile_image"] = profile_image
        planned[a["artist_key"]] = profile_image

    tmp = ARTISTS_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(artists, f, ensure_ascii=False, indent=2)
    os.replace(tmp, ARTISTS_PATH)
    print(f"[OK] added profile_image field to {len(artists)} artists")

    # 전수 검증
    before = json.load(open(backup_path, encoding="utf-8"))
    after = json.load(open(ARTISTS_PATH, encoding="utf-8"))
    assert len(before) == len(after) == 87

    for b, af in zip(before, after):
        assert b["artist_key"] == af["artist_key"]
        if af.get("profile_image") != planned[af["artist_key"]]:
            raise AssertionError(f"{af['artist_key']}: profile_image mismatch")
        af_without = dict(af)
        af_without.pop("profile_image")
        assert af_without == b, f"{af['artist_key']}: other fields changed unexpectedly"

    print(f"[OK] all {len(after)} artists verified: profile_image matches plan, other fields unchanged")
    print()
    print("DONE.")


if __name__ == "__main__":
    main()
