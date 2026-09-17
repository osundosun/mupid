# -*- coding: utf-8 -*-
"""
lyrics_sections가 애초에 null이었던 11곡이 lyrics_sync: [](빈 배열)로 저장돼 있던 것을
lyrics_sync: null로 통일. 스키마 컨벤션(해당 없음은 항상 명시적 null)에 맞춤.

대상은 정확히 이 11개뿐 -- 다른 파일은 절대 안 건드림.
"""
import json
import os
import shutil
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
METADATA_DIR = os.path.join(ROOT, "downloads", "metadata")


def main():
    all_meta_files = sorted(f for f in os.listdir(METADATA_DIR) if f.endswith(".json"))

    targets = []
    for fname in all_meta_files:
        meta_path = os.path.join(METADATA_DIR, fname)
        meta = json.load(open(meta_path, encoding="utf-8"))
        ls = meta.get("lyrics_sync")
        if ls is not None and len(ls) == 0:
            targets.append(fname)

    print(f"[INFO] empty-array targets: {len(targets)} -> {[t[:-5] for t in targets]}")
    if not targets:
        print("[OK] nothing to do")
        return

    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup_dir = os.path.join(ROOT, "downloads", f"metadata.bak_before_lyrics_sync_emptyfix_{ts}")
    shutil.copytree(METADATA_DIR, backup_dir)
    print(f"[OK] backup created: {backup_dir}")

    for fname in targets:
        meta_path = os.path.join(METADATA_DIR, fname)
        meta = json.load(open(meta_path, encoding="utf-8"))
        meta["lyrics_sync"] = None
        tmp_path = meta_path + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)
        os.replace(tmp_path, meta_path)

    print(f"[OK] fixed {len(targets)} files to lyrics_sync: null")

    files_after = sorted(f for f in os.listdir(METADATA_DIR) if f.endswith(".json"))
    assert len(files_after) == len(all_meta_files), "file count changed"
    bad = []
    for fname in files_after:
        meta = json.load(open(os.path.join(METADATA_DIR, fname), encoding="utf-8"))
        if "lyrics_sync" not in meta:
            bad.append((fname, "key missing"))
        if fname in targets and meta["lyrics_sync"] is not None:
            bad.append((fname, "still not null"))
    if bad:
        print(f"[FAIL] {bad[:10]}")
        return
    print(f"[OK] all {len(files_after)} files verified, key always present, target files now null")
    print("DONE.")


if __name__ == "__main__":
    main()
