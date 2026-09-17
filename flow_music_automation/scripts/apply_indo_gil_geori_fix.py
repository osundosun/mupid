# -*- coding: utf-8 -*-
"""'인도' 포함 제목 2곡 수정 (사용자 직접 지정, 2026-09-15).
T001057: 바닷바람 부는 인도 -> 바닷바람 부는 길
T001060: 필름 속 인도 -> 필름 속 거리
flow_title 직접 덮어쓰기 + logs/title_rework_log.jsonl 기록 (기존 title_rework 원칙 그대로)."""
import json
import os
import shutil
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
METADATA_DIR = os.path.join(ROOT, "downloads", "metadata")
LOG_PATH = os.path.join(ROOT, "logs", "title_rework_log.jsonl")

CHANGES = {
    "T001057": "潮風の歩道(바닷바람 부는 길)",
    "T001060": "フィルムの歩道(필름 속 거리)",
}
REASON = "사용자 직접 지정: '인도' 단어를 '길'/'거리'로 치환 (동일 앨범 내 두 곡 다 '歩道' 번역어였음)"


def main():
    all_meta_files = sorted(f for f in os.listdir(METADATA_DIR) if f.endswith(".json"))

    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup_dir = os.path.join(ROOT, "downloads", f"metadata.bak_before_indo_gil_geori_{ts}")
    shutil.copytree(METADATA_DIR, backup_dir)
    print(f"[OK] backup created: {backup_dir}")

    before = {}
    for tid, new_title in CHANGES.items():
        meta_path = os.path.join(METADATA_DIR, f"{tid}.json")
        meta = json.load(open(meta_path, encoding="utf-8"))
        before[tid] = meta["flow_title"]
        meta["flow_title"] = new_title
        tmp_path = meta_path + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)
        os.replace(tmp_path, meta_path)

    print(f"[OK] applied to {len(CHANGES)} files")

    files_after = sorted(f for f in os.listdir(METADATA_DIR) if f.endswith(".json"))
    assert len(files_after) == len(all_meta_files), "file count changed"
    for tid, new_title in CHANGES.items():
        meta = json.load(open(os.path.join(METADATA_DIR, f"{tid}.json"), encoding="utf-8"))
        assert meta["flow_title"] == new_title, f"{tid} title mismatch"
        assert "인도" not in meta["flow_title"], f"{tid} still contains 인도"
    print(f"[OK] verified {len(CHANGES)} files, file count unchanged ({len(files_after)})")

    with open(LOG_PATH, "a", encoding="utf-8") as f:
        for tid, new_title in CHANGES.items():
            f.write(json.dumps({
                "ts": datetime.now(timezone.utc).isoformat(),
                "wave": "indo_gil_geori_fix",
                "track_id": tid,
                "before": before[tid],
                "after": new_title,
                "reason": REASON,
            }, ensure_ascii=False) + "\n")
    print(f"[OK] logged to {LOG_PATH}")
    print("DONE.")


if __name__ == "__main__":
    main()
