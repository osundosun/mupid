# -*- coding: utf-8 -*-
"""
일본어 곡 제목에 최소한도의 한국어 번역을 괄호로 덧붙이는 작업.
형식: "일본어 원제(한국어 번역)" (예: 明日への翼(내일을 향한 날개))

flow_title 필드를 직접 덮어씀 (title_rework와 동일 원칙 -- 별도 display 필드 안 만듦).
계획: title_translation_plan_japanese.json
절차: 사전 검증(계획의 old가 현재 metadata와 일치하는지) -> 전체 백업 -> 원자적 적용 -> 전수 검증.
"""
import json
import os
import shutil
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
METADATA_DIR = os.path.join(ROOT, "downloads", "metadata")
PLAN_PATH = os.path.join(ROOT, "title_translation_plan_japanese.json")
LOG_PATH = os.path.join(ROOT, "logs", "title_rework_log.jsonl")


def main():
    plan = json.load(open(PLAN_PATH, encoding="utf-8"))
    print(f"[INFO] plan has {len(plan)} entries")

    # 1) 사전 검증: 계획의 old가 현재 metadata의 flow_title과 정확히 일치하는지
    mismatches = []
    for track_id, entry in plan.items():
        meta_path = os.path.join(METADATA_DIR, f"{track_id}.json")
        with open(meta_path, encoding="utf-8") as f:
            meta = json.load(f)
        if meta["flow_title"] != entry["old"]:
            mismatches.append((track_id, meta["flow_title"], entry["old"]))
    if mismatches:
        print(f"[FAIL] {len(mismatches)} mismatches, aborting")
        for m in mismatches:
            print(" ", m)
        return
    print("[OK] all old titles match current metadata")

    # 2) 전체 백업
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup_dir = os.path.join(ROOT, "downloads", f"metadata.bak_before_ja_translation_{ts}")
    shutil.copytree(METADATA_DIR, backup_dir)
    print(f"[OK] backup created: {backup_dir}")

    # 3) 원자적 적용
    applied = []
    for track_id, entry in plan.items():
        meta_path = os.path.join(METADATA_DIR, f"{track_id}.json")
        with open(meta_path, encoding="utf-8") as f:
            meta = json.load(f)
        meta["flow_title"] = entry["new"]
        tmp_path = meta_path + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)
        os.replace(tmp_path, meta_path)
        applied.append(track_id)
    print(f"[OK] applied {len(applied)} changes")

    # 4) 전수 검증
    all_files = [f for f in os.listdir(METADATA_DIR) if f.endswith(".json")]
    assert len(all_files) == 1040, f"file count changed: {len(all_files)}"

    verify_fail = []
    for track_id, entry in plan.items():
        meta_path = os.path.join(METADATA_DIR, f"{track_id}.json")
        with open(meta_path, encoding="utf-8") as f:
            meta = json.load(f)
        if meta["flow_title"] != entry["new"]:
            verify_fail.append(track_id)
    if verify_fail:
        print(f"[FAIL] {len(verify_fail)} tracks did not verify: {verify_fail}")
        return
    print(f"[OK] verified all {len(plan)} tracks now have translated titles")

    # JSON 파싱 전수 확인
    parse_fail = []
    for fname in all_files:
        try:
            json.load(open(os.path.join(METADATA_DIR, fname), encoding="utf-8"))
        except Exception as e:
            parse_fail.append((fname, str(e)))
    if parse_fail:
        print(f"[FAIL] {len(parse_fail)} files failed to parse: {parse_fail}")
        return
    print(f"[OK] all {len(all_files)} metadata files parse successfully")

    # 5) 로그 기록
    os.makedirs(os.path.dirname(LOG_PATH), exist_ok=True)
    with open(LOG_PATH, "a", encoding="utf-8") as f:
        for track_id, entry in plan.items():
            f.write(json.dumps({
                "timestamp": ts,
                "wave": "ja_translation",
                "track_id": track_id,
                "old_title": entry["old"],
                "new_title": entry["new"],
            }, ensure_ascii=False) + "\n")
    print(f"[OK] logged {len(plan)} entries to {LOG_PATH}")
    print()
    print("DONE.")


if __name__ == "__main__":
    main()
