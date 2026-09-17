# -*- coding: utf-8 -*-
"""
lyrics_sections가 null이던 11곡을 Flow 원본 페이지에서 재수집한 결과
(스크래치패드의 refetch_results.json)를 metadata에 반영.

건드리는 필드: flow_generated.lyrics_raw, flow_generated.lyrics_present,
lyrics_sections, structure_validation.section_order_parsed. 그 외 필드는 전혀
안 건드림.

절차: 사전검증 -> 전체백업 -> 원자적적용 -> 전수검증.
"""
import json
import os
import shutil
import sys
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
METADATA_DIR = os.path.join(ROOT, "downloads", "metadata")


def main():
    results_path = sys.argv[1] if len(sys.argv) > 1 else None
    if not results_path or not os.path.exists(results_path):
        print("[ABORT] pass path to refetch_results.json")
        return

    results = json.load(open(results_path, encoding="utf-8"))

    ok_tracks = {tid: r for tid, r in results.items() if r.get("status") == "ok"}
    bad_tracks = {tid: r["status"] for tid, r in results.items() if r.get("status") != "ok"}

    print(f"[INFO] ok: {len(ok_tracks)}, not ok: {len(bad_tracks)} {bad_tracks}")
    if not ok_tracks:
        print("[ABORT] nothing to apply")
        return

    all_meta_files = sorted(f for f in os.listdir(METADATA_DIR) if f.endswith(".json"))

    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup_dir = os.path.join(ROOT, "downloads", f"metadata.bak_before_lyrics_refetch_{ts}")
    shutil.copytree(METADATA_DIR, backup_dir)
    print(f"[OK] backup created: {backup_dir}")

    applied = []
    for tid, r in ok_tracks.items():
        meta_path = os.path.join(METADATA_DIR, f"{tid}.json")
        meta = json.load(open(meta_path, encoding="utf-8"))

        meta["flow_generated"]["lyrics_raw"] = r["lyrics_raw"]
        meta["flow_generated"]["lyrics_present"] = r["lyrics_present"]
        meta["lyrics_sections"] = r["lyrics_sections"]
        meta["structure_validation"]["section_order_parsed"] = True

        tmp_path = meta_path + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)
        os.replace(tmp_path, meta_path)
        applied.append(tid)

    print(f"[OK] applied to {len(applied)} files: {applied}")

    files_after = sorted(f for f in os.listdir(METADATA_DIR) if f.endswith(".json"))
    assert len(files_after) == len(all_meta_files), "file count changed"

    fail = []
    for tid in applied:
        meta = json.load(open(os.path.join(METADATA_DIR, f"{tid}.json"), encoding="utf-8"))
        if meta.get("lyrics_sections") is None:
            fail.append((tid, "lyrics_sections still null"))
        if not meta["flow_generated"]["lyrics_present"]:
            fail.append((tid, "lyrics_present still false"))
    if fail:
        print(f"[FAIL] {fail}")
        return
    print(f"[OK] all {len(applied)} verified: lyrics_sections populated, lyrics_present true")
    print("DONE.")


if __name__ == "__main__":
    main()
