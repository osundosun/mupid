# -*- coding: utf-8 -*-
"""T001028("Paper and Ink")를 사용자가 Flow에서 다른 버전(ver 1, 같은 가사/제목)으로
교체함(2026-09-15). downloads/audio/T001028.mp3 파일 자체는 이미 교체돼 있었고,
metadata는 옛 파일 기준 그대로였음. 이 스크립트는:
  1. assets.audio.sha256/size_bytes를 새 파일 기준으로 갱신
  2. lyrics_sync 필드를 새로 처리된 결과(downloads/lyrics_sync/T001028.json,
     medium 모델 + 보간, 32/38 실매칭)로 갱신

source.song_id/song_url/selected_card_index/generated_at은 새 버전의 정확한 값을
알 수 없어서(사용자가 Flow UI에서 수동으로 교체, 이 스크립트가 추적 못 함) 그대로
둠 -- 확인되기 전까지 임의로 채우지 않는다는 이 프로젝트 원칙(source.model 등과 동일).
"""
import hashlib
import json
import os
import shutil
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
METADATA_DIR = os.path.join(ROOT, "downloads", "metadata")
AUDIO_PATH = os.path.join(ROOT, "downloads", "audio", "T001028.mp3")
SYNC_PATH = os.path.join(ROOT, "downloads", "lyrics_sync", "T001028.json")
TRACK_ID = "T001028"


def main():
    meta_path = os.path.join(METADATA_DIR, f"{TRACK_ID}.json")
    all_meta_files = sorted(f for f in os.listdir(METADATA_DIR) if f.endswith(".json"))

    with open(AUDIO_PATH, "rb") as f:
        new_sha256 = hashlib.sha256(f.read()).hexdigest()
    new_size = os.path.getsize(AUDIO_PATH)

    sync_data = json.load(open(SYNC_PATH, encoding="utf-8"))
    new_lines = [
        {"text": ln["line_text"], "start_sec": ln["start_sec"], "end_sec": ln["end_sec"]}
        for ln in sync_data["lines"] if not ln["is_empty_section"]
    ]

    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup_dir = os.path.join(ROOT, "downloads", f"metadata.bak_before_t001028_newversion_{ts}")
    shutil.copytree(METADATA_DIR, backup_dir)
    print(f"[OK] backup created: {backup_dir}")

    meta = json.load(open(meta_path, encoding="utf-8"))
    old_sha = meta["assets"]["audio"]["sha256"]
    meta["assets"]["audio"]["sha256"] = new_sha256
    meta["assets"]["audio"]["size_bytes"] = new_size
    meta["lyrics_sync"] = new_lines

    tmp_path = meta_path + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
    os.replace(tmp_path, meta_path)
    print(f"[OK] applied: sha256 {old_sha[:12]}... -> {new_sha256[:12]}..., lyrics_sync {len(new_lines)} lines")

    files_after = sorted(f for f in os.listdir(METADATA_DIR) if f.endswith(".json"))
    assert len(files_after) == len(all_meta_files), "file count changed"
    meta_check = json.load(open(meta_path, encoding="utf-8"))
    assert meta_check["assets"]["audio"]["sha256"] == new_sha256
    assert len(meta_check["lyrics_sync"]) == len(new_lines)
    print(f"[OK] verified, file count unchanged ({len(files_after)})")
    print("DONE.")


if __name__ == "__main__":
    main()
