# -*- coding: utf-8 -*-
"""제목 중복 해소 2건 (사용자 직접 지정, 2026-09-15).
T000097 (필름카메라, '낡은 운동화'): '한계 돌파 (Break The Limit)' -> 'Break the Limit'
  ('한계 돌파'가 T001004(달그림자, '레코드')와 겹쳐서 영어만 남김)
T000098 (스파클러, '재점화'): 'Break Through (뚫어버려) - Version 1' -> '뚫어버려'
  ('Break Through'가 T000317(언더독)의 'Breakthrough'와 겹쳐서 한국어만 남기고 버전 태그도 제거)
flow_title 직접 덮어쓰기 + logs/title_rework_log.jsonl 기록."""
import json
import os
import shutil
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
METADATA_DIR = os.path.join(ROOT, "downloads", "metadata")
LOG_PATH = os.path.join(ROOT, "logs", "title_rework_log.jsonl")

CHANGES = {
    "T000097": ("한계 돌파 (Break The Limit)", "Break the Limit",
                "사용자 직접 지정: T001004(달그림자 '한계 돌파')와 제목 중복 -> 영어만 남김"),
    "T000098": ("Break Through (뚫어버려) - Version 1", "뚫어버려",
                "사용자 직접 지정: T000317(언더독 'Breakthrough')와 제목 중복 -> 한국어만 남기고 버전 태그 제거"),
}


def main():
    all_meta_files = sorted(f for f in os.listdir(METADATA_DIR) if f.endswith(".json"))

    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup_dir = os.path.join(ROOT, "downloads", f"metadata.bak_before_title_collision_fix2_{ts}")
    shutil.copytree(METADATA_DIR, backup_dir)
    print(f"[OK] backup created: {backup_dir}")

    for tid, (expected_before, new_title, reason) in CHANGES.items():
        meta_path = os.path.join(METADATA_DIR, f"{tid}.json")
        meta = json.load(open(meta_path, encoding="utf-8"))
        assert meta["flow_title"] == expected_before, f"{tid}: expected {expected_before!r}, got {meta['flow_title']!r}"
        meta["flow_title"] = new_title
        tmp_path = meta_path + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)
        os.replace(tmp_path, meta_path)

    print(f"[OK] applied to {len(CHANGES)} files")

    files_after = sorted(f for f in os.listdir(METADATA_DIR) if f.endswith(".json"))
    assert len(files_after) == len(all_meta_files), "file count changed"
    for tid, (_, new_title, _) in CHANGES.items():
        meta = json.load(open(os.path.join(METADATA_DIR, f"{tid}.json"), encoding="utf-8"))
        assert meta["flow_title"] == new_title, f"{tid} title mismatch"
    print(f"[OK] verified {len(CHANGES)} files, file count unchanged ({len(files_after)})")

    with open(LOG_PATH, "a", encoding="utf-8") as f:
        for tid, (before, new_title, reason) in CHANGES.items():
            f.write(json.dumps({
                "ts": datetime.now(timezone.utc).isoformat(),
                "wave": "title_collision_fix2",
                "track_id": tid,
                "before": before,
                "after": new_title,
                "reason": reason,
            }, ensure_ascii=False) + "\n")
    print(f"[OK] logged to {LOG_PATH}")
    print("DONE.")


if __name__ == "__main__":
    main()
