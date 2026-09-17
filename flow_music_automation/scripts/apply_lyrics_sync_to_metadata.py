# -*- coding: utf-8 -*-
"""
downloads/lyrics_sync/{track_id}.json (GPU 배치 결과, 스테이징)의 줄 단위 타임스탬프를
각 트랙 metadata에 lyrics_sync 필드로 추가.

기존 필드는 전혀 건드리지 않고 새 필드만 추가함 (artist/album 필드 추가 때와 동일 원칙).
raw whisper transcript_segments는 메타데이터에 넣지 않음 -- 그건 downloads/lyrics_sync/
스테이징 파일에만 남겨서 감사용으로 보존 (title_rework의 logs/ 분리 원칙과 동일).

lyrics_sync가 null인 경우:
  - 인스트루멘탈(flow_generated.lyrics_present == false): 애초에 대상 아님.
  - 보컬곡인데 GPU 배치에서 error가 난 경우: null로 두고 실패 목록에 기록.

절차: 사전 검증(커버리지) -> 전체 백업 -> 원자적 적용 -> 전수 검증.
"""
import json
import os
import shutil
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
METADATA_DIR = os.path.join(ROOT, "downloads", "metadata")
SYNC_DIR = os.path.join(ROOT, "downloads", "lyrics_sync")
LOG_PATH = os.path.join(ROOT, "logs", "lyrics_sync_metadata_apply_log.jsonl")


def build_lyrics_sync_field(sync_data):
    """가사 텍스트 + 타임스탬프만 남긴다 (엔진/모델/정렬성공여부/신뢰도 등 부가 정보는 넣지 않음
    -- 사용자 지시, 2026-09-15). 정렬 실패한 줄도 그냥 start_sec/end_sec만 null로 두고
    나머지 줄과 동일하게 취급 -- 별도 "실패" 표시 없음."""
    lines_out = []
    for ln in sync_data["lines"]:
        if ln["is_empty_section"]:
            continue
        lines_out.append({
            "text": ln["line_text"],
            "start_sec": ln["start_sec"],
            "end_sec": ln["end_sec"],
        })
    return lines_out


def main():
    all_meta_files = sorted(f for f in os.listdir(METADATA_DIR) if f.endswith(".json"))
    all_track_ids = [f[:-5] for f in all_meta_files]

    vocal_track_ids = []
    for fname in all_meta_files:
        meta = json.load(open(os.path.join(METADATA_DIR, fname), encoding="utf-8"))
        if meta.get("flow_generated", {}).get("lyrics_present"):
            vocal_track_ids.append(meta["track_id"])

    # 1) 사전 검증: 보컬 트랙 전부 스테이징 결과가 있는지, 에러 여부
    sync_by_track = {}
    missing, errored = [], []
    for tid in vocal_track_ids:
        sync_path = os.path.join(SYNC_DIR, f"{tid}.json")
        if not os.path.exists(sync_path):
            missing.append(tid)
            continue
        d = json.load(open(sync_path, encoding="utf-8"))
        if "error" in d:
            errored.append((tid, d["error"]))
            continue
        sync_by_track[tid] = d

    print(f"[INFO] vocal tracks: {len(vocal_track_ids)}")
    print(f"[INFO] staged results ready: {len(sync_by_track)}")
    if missing:
        print(f"[WARN] missing staged results: {len(missing)} (예: {missing[:5]})")
    if errored:
        print(f"[WARN] errored tracks: {len(errored)} (예: {errored[:5]})")

    if len(sync_by_track) == 0:
        print("[ABORT] 적용할 데이터가 없음")
        return

    # 2) 전체 백업
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup_dir = os.path.join(ROOT, "downloads", f"metadata.bak_before_lyrics_sync_{ts}")
    shutil.copytree(METADATA_DIR, backup_dir)
    print(f"[OK] backup created: {backup_dir}")

    # 3) 원자적 적용 (보컬곡 중 스테이징 데이터 있는 것만 필드 추가, 나머지는 안 건드림)
    applied = []
    for tid in sync_by_track:
        fname = f"{tid}.json"
        meta_path = os.path.join(METADATA_DIR, fname)
        with open(meta_path, encoding="utf-8") as f:
            meta = json.load(f)

        meta["lyrics_sync"] = build_lyrics_sync_field(sync_by_track[tid])

        tmp_path = meta_path + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)
        os.replace(tmp_path, meta_path)
        applied.append(tid)

    print(f"[OK] applied lyrics_sync field to {len(applied)} files")

    # 4) 전수 검증
    files_after = sorted(f for f in os.listdir(METADATA_DIR) if f.endswith(".json"))
    assert len(files_after) == len(all_meta_files), f"file count changed: {len(files_after)}"
    print(f"[OK] file count unchanged: {len(files_after)}")

    parse_fail, field_fail, untouched_fail = [], [], []
    applied_set = set(applied)
    for fname in files_after:
        track_id = fname[:-5]
        meta_path = os.path.join(METADATA_DIR, fname)
        try:
            with open(meta_path, encoding="utf-8") as f:
                meta = json.load(f)
        except Exception as e:
            parse_fail.append((fname, str(e)))
            continue

        if track_id in applied_set:
            if "lyrics_sync" not in meta or meta["lyrics_sync"] is None:
                field_fail.append((track_id, "lyrics_sync missing after apply"))
            elif len(meta["lyrics_sync"]) != len([
                l for l in sync_by_track[track_id]["lines"] if not l["is_empty_section"]
            ]):
                field_fail.append((track_id, "line count mismatch"))
        else:
            if meta.get("lyrics_sync") is not None:
                untouched_fail.append((track_id, "lyrics_sync unexpectedly present"))

    if parse_fail:
        print(f"[FAIL] {len(parse_fail)} files failed to parse: {parse_fail[:10]}")
        return
    print(f"[OK] all {len(files_after)} files parse successfully")

    if field_fail:
        print(f"[FAIL] {len(field_fail)} field mismatches: {field_fail[:10]}")
        return
    print(f"[OK] all {len(applied)} applied files verified")

    if untouched_fail:
        print(f"[FAIL] {len(untouched_fail)} non-target files unexpectedly changed: {untouched_fail[:10]}")
        return
    print(f"[OK] non-target files ({len(files_after) - len(applied)}) confirmed untouched")

    # 5) 로그
    os.makedirs(os.path.dirname(LOG_PATH), exist_ok=True)
    with open(LOG_PATH, "w", encoding="utf-8") as f:
        for tid in applied:
            f.write(json.dumps({"track_id": tid, "n_lines": len(sync_by_track[tid]["lines"])}, ensure_ascii=False) + "\n")
        for tid, err in errored:
            f.write(json.dumps({"track_id": tid, "status": "error", "error": err}, ensure_ascii=False) + "\n")
        for tid in missing:
            f.write(json.dumps({"track_id": tid, "status": "missing"}, ensure_ascii=False) + "\n")
    print(f"[OK] logged to {LOG_PATH}")
    print()
    print("DONE.")


if __name__ == "__main__":
    main()
