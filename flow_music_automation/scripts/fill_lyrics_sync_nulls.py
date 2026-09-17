# -*- coding: utf-8 -*-
"""
apply_lyrics_sync_to_metadata.py가 보컬곡 516개에만 lyrics_sync 필드를 추가했음.
나머지 544개(인스트루멘탈 + lyrics_sections 파싱 실패 11곡)는 필드 자체가 없는
상태였는데, 이 프로젝트/문서 컨벤션(docs/metadata_schema_v2.md)상 "해당 없음"은
필드 누락이 아니라 명시적 null로 표기하는 게 일관됨(source.model, generated_card_count
등과 동일 패턴). 그 544개에 lyrics_sync: null을 명시적으로 채운다.

절차: 대상 파일 확인 -> 전체 백업 -> 원자적 적용 -> 전수 검증.
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
        if "lyrics_sync" not in meta:
            targets.append(fname)

    print(f"[INFO] total files: {len(all_meta_files)}, missing lyrics_sync key: {len(targets)}")
    if not targets:
        print("[OK] nothing to do")
        return

    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup_dir = os.path.join(ROOT, "downloads", f"metadata.bak_before_lyrics_sync_nullfill_{ts}")
    shutil.copytree(METADATA_DIR, backup_dir)
    print(f"[OK] backup created: {backup_dir}")

    for fname in targets:
        meta_path = os.path.join(METADATA_DIR, fname)
        with open(meta_path, encoding="utf-8") as f:
            meta = json.load(f)
        meta["lyrics_sync"] = None
        tmp_path = meta_path + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)
        os.replace(tmp_path, meta_path)

    print(f"[OK] filled lyrics_sync: null on {len(targets)} files")

    # 전수 검증
    files_after = sorted(f for f in os.listdir(METADATA_DIR) if f.endswith(".json"))
    assert len(files_after) == len(all_meta_files), f"file count changed: {len(files_after)}"

    parse_fail = []
    has_key = 0
    non_null_unexpected = []
    for fname in files_after:
        meta_path = os.path.join(METADATA_DIR, fname)
        try:
            meta = json.load(open(meta_path, encoding="utf-8"))
        except Exception as e:
            parse_fail.append((fname, str(e)))
            continue
        if "lyrics_sync" in meta:
            has_key += 1
        if fname in targets and meta.get("lyrics_sync") is not None:
            non_null_unexpected.append(fname)

    if parse_fail:
        print(f"[FAIL] {len(parse_fail)} parse failures: {parse_fail[:10]}")
        return
    print(f"[OK] all {len(files_after)} files parse successfully")

    if has_key != len(all_meta_files):
        print(f"[FAIL] not all files have lyrics_sync key: {has_key}/{len(all_meta_files)}")
        return
    print(f"[OK] all {len(all_meta_files)} files now have lyrics_sync key present")

    if non_null_unexpected:
        print(f"[FAIL] {len(non_null_unexpected)} target files not null: {non_null_unexpected[:10]}")
        return
    print(f"[OK] all {len(targets)} filled files are null as expected")
    print()
    print("DONE.")


if __name__ == "__main__":
    main()
