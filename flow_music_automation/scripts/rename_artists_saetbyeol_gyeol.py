# -*- coding: utf-8 -*-
"""
쾌속선 -> 샛별 (female_korean_pop_mainstream_02)
여린볕 -> 결 (female_korean_indie_soft_01)
사용자가 "여성스러움 부족"으로 직접 재검토 요청, 두 이름 다 최종 확정.

절차: 백업 -> 적용 -> 검증. (artists.json만 여기서 수정; downloads/metadata 반영은
기존 scripts/apply_artist_album_to_metadata.py 재실행으로 처리)
"""
import json
import os
import shutil
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ARTISTS_PATH = os.path.join(ROOT, "artists.json")

RENAMES = {
    "female_korean_pop_mainstream_02": ("쾌속선", "샛별"),
    "female_korean_indie_soft_01": ("여린볕", "결"),
}


def main():
    artists = json.load(open(ARTISTS_PATH, encoding="utf-8"))

    for key, (old, new) in RENAMES.items():
        match = [a for a in artists if a["artist_key"] == key]
        assert len(match) == 1, f"{key}: expected exactly 1 match, got {len(match)}"
        assert match[0]["artist_name"] == old, f"{key}: expected current name {old!r}, got {match[0]['artist_name']!r}"

    all_names = [a["artist_name"] for a in artists]
    for key, (old, new) in RENAMES.items():
        assert new not in all_names, f"{new!r} already used by another artist"

    backup_path = ARTISTS_PATH + ".bak_before_rename_saetbyeol_gyeol_" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    shutil.copyfile(ARTISTS_PATH, backup_path)
    print(f"[OK] backup created: {backup_path}")

    for a in artists:
        if a["artist_key"] in RENAMES:
            old, new = RENAMES[a["artist_key"]]
            a["artist_name"] = new

    tmp_path = ARTISTS_PATH + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as f:
        json.dump(artists, f, ensure_ascii=False, indent=2)
    os.replace(tmp_path, ARTISTS_PATH)
    print("[OK] renamed 2 artists")

    # 전수 검증
    after = json.load(open(ARTISTS_PATH, encoding="utf-8"))
    before = json.load(open(backup_path, encoding="utf-8"))
    assert len(after) == len(before) == 87

    changed = []
    for b, af in zip(before, after):
        assert b["artist_key"] == af["artist_key"]
        if b["artist_name"] != af["artist_name"]:
            changed.append(af["artist_key"])
            b_without = dict(b)
            af_without = dict(af)
            b_without.pop("artist_name")
            af_without.pop("artist_name")
            assert b_without == af_without, f"{af['artist_key']}: other fields changed unexpectedly"

    assert set(changed) == set(RENAMES.keys()), f"unexpected changed set: {changed}"
    print(f"[OK] exactly the 2 intended artists changed, all other fields/artists untouched: {changed}")

    final_names = [a["artist_name"] for a in after]
    assert len(final_names) == len(set(final_names)), "duplicate artist_name after rename!"
    print("[OK] no duplicate artist_name after rename")
    print()
    print("DONE.")


if __name__ == "__main__":
    main()
