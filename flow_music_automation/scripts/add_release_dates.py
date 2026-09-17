# -*- coding: utf-8 -*-
"""
GPU 서버 이전 서비스용 관계형 DB에 필요한 "곡 발매일" 필드를 추가한다.
가상 수치 사용 OK(사용자 확정) -> generated_at의 날짜 부분을 그대로 release_date로 쓴다.

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
    print(f"[INFO] total files: {len(all_meta_files)}")

    # 사전 검증: generated_at 전부 존재하고 파싱 가능한지, release_date 필드가 이미 있는지
    already_has = []
    parsed = {}
    for fname in all_meta_files:
        meta_path = os.path.join(METADATA_DIR, fname)
        meta = json.load(open(meta_path, encoding="utf-8"))
        if "release_date" in meta:
            already_has.append(fname)
        gen_at = meta.get("generated_at")
        assert gen_at, f"{fname}: generated_at missing"
        date_str = datetime.fromisoformat(gen_at).date().isoformat()
        parsed[fname] = date_str

    if already_has:
        print(f"[FAIL] {len(already_has)} files already have release_date, aborting: {already_has[:10]}")
        return
    print("[OK] no existing release_date field, all generated_at parseable")

    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup_dir = os.path.join(ROOT, "downloads", f"metadata.bak_before_release_dates_{ts}")
    shutil.copytree(METADATA_DIR, backup_dir)
    print(f"[OK] backup created: {backup_dir}")

    for fname in all_meta_files:
        meta_path = os.path.join(METADATA_DIR, fname)
        with open(meta_path, encoding="utf-8") as f:
            meta = json.load(f)
        meta["release_date"] = parsed[fname]
        tmp_path = meta_path + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)
        os.replace(tmp_path, meta_path)

    print(f"[OK] added release_date on {len(all_meta_files)} files")

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
        if meta.get("release_date") != parsed[fname]:
            mismatched.append(fname)
        backup_meta = json.load(open(backup_path, encoding="utf-8"))
        after_without = dict(meta)
        after_without.pop("release_date", None)
        if after_without != backup_meta:
            other_field_changed.append(fname)

    if parse_fail:
        print(f"[FAIL] {len(parse_fail)} parse failures: {parse_fail[:10]}")
        return
    print(f"[OK] all {len(files_after)} files parse successfully")

    if mismatched:
        print(f"[FAIL] {len(mismatched)} files have wrong release_date: {mismatched[:10]}")
        return
    print(f"[OK] all {len(files_after)} files have exactly the planned release_date")

    if other_field_changed:
        print(f"[FAIL] {len(other_field_changed)} files changed other fields: {other_field_changed[:10]}")
        return
    print(f"[OK] all other fields unchanged (byte-equivalent to backup minus release_date)")

    dates = sorted(set(parsed.values()))
    print(f"[INFO] distinct release_date values: {len(dates)} (range {dates[0]} ~ {dates[-1]})")
    print()
    print("DONE.")


if __name__ == "__main__":
    main()
