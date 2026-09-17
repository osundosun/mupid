# -*- coding: utf-8 -*-
"""T000096 (스파클러, '재점화'): 'Fireworks (불꽃)' -> 'Fireworks'
같은 앨범 T000086 '다시, 불꽃'과 '불꽃' 단어 중복 -> 영어만 남김(사용자 직접 지정, 2026-09-15).
가사 내용 비교 결과 테마가 달라(재기 서사 vs 축제 로맨스) 곡 이동은 불필요로 판단, 제목만 수정."""
import json
import os
import shutil
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
METADATA_DIR = os.path.join(ROOT, "downloads", "metadata")
LOG_PATH = os.path.join(ROOT, "logs", "title_rework_log.jsonl")

TRACK_ID = "T000096"
BEFORE = "Fireworks (불꽃)"
AFTER = "Fireworks"
REASON = "사용자 직접 지정: 같은 앨범 T000086 '다시, 불꽃'과 '불꽃' 단어 중복 -> 영어만 남김. 가사 테마는 상이(재기 서사 vs 축제 로맨스)해 곡 이동은 불필요로 판단."


def main():
    all_meta_files = sorted(f for f in os.listdir(METADATA_DIR) if f.endswith(".json"))

    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup_dir = os.path.join(ROOT, "downloads", f"metadata.bak_before_fireworks_title_fix_{ts}")
    shutil.copytree(METADATA_DIR, backup_dir)
    print(f"[OK] backup created: {backup_dir}")

    meta_path = os.path.join(METADATA_DIR, f"{TRACK_ID}.json")
    meta = json.load(open(meta_path, encoding="utf-8"))
    assert meta["flow_title"] == BEFORE, f"expected {BEFORE!r}, got {meta['flow_title']!r}"
    meta["flow_title"] = AFTER
    tmp_path = meta_path + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
    os.replace(tmp_path, meta_path)
    print(f"[OK] applied to {TRACK_ID}")

    files_after = sorted(f for f in os.listdir(METADATA_DIR) if f.endswith(".json"))
    assert len(files_after) == len(all_meta_files), "file count changed"
    meta = json.load(open(meta_path, encoding="utf-8"))
    assert meta["flow_title"] == AFTER
    print(f"[OK] verified, file count unchanged ({len(files_after)})")

    with open(LOG_PATH, "a", encoding="utf-8") as f:
        f.write(json.dumps({
            "ts": datetime.now(timezone.utc).isoformat(),
            "wave": "fireworks_title_fix",
            "track_id": TRACK_ID,
            "before": BEFORE,
            "after": AFTER,
            "reason": REASON,
        }, ensure_ascii=False) + "\n")
    print(f"[OK] logged to {LOG_PATH}")
    print("DONE.")


if __name__ == "__main__":
    main()
