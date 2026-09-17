# -*- coding: utf-8 -*-
"""
보컬 아티스트 재편(indie_rock 클러스터 버그 수정 + rock_edge/rnb_soul 강제분리 수정 +
267/277/287/317/318 여성->남성 전환) 최종 반영.

- 인스트루멘탈 38명: artists.json/albums.json 그대로 재사용 (전혀 안 건드림).
- 보컬 49명: scripts/_tmp_new_vocal_artists.json(재구성된 그룹) + scripts/_tmp_draft_names.json
  (확정된 이름들)을 합쳐서 최종 artists.json 항목 생성. vocal_tone_summary는
  build_artist_album_grouping.py와 동일한 로직으로 새로 계산.
- 앨범은 scripts/rebuild_albums_genre_aware.py와 동일한 "장르 블록 순수 유지" 알고리즘으로
  49명 전원에 대해 재계산하고, 트랙 구성이 기존 앨범과 정확히 같으면 기존 제목을 재사용하되
  _tmp_draft_names.json의 album_title_overrides가 있으면 그걸 우선 적용.

백업 후 원자적으로 반영 + 전수 검증까지 수행.
"""
import json
import math
import os
import re
import shutil
from collections import Counter
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
METADATA_DIR = os.path.join(ROOT, "downloads", "metadata")
ARTISTS_PATH = os.path.join(ROOT, "artists.json")
ALBUMS_PATH = os.path.join(ROOT, "albums.json")

ALBUM_TARGET_MIN = 4
ALBUM_TARGET_MAX = 7

VOCAL_TONE_KEYWORDS = [
    "soprano", "alto", "tenor", "baritone", "bass-baritone",
    "breathy", "raspy", "powerful", "airy", "warm", "bright",
    "husky", "smoky", "silky", "belting", "falsetto", "gritty",
    "sweet", "youthful", "mature", "soft", "clear",
]


def extract_vocal_tone(sound_raw):
    if not sound_raw:
        return []
    lower = sound_raw.lower()
    found = []
    for kw in VOCAL_TONE_KEYWORDS:
        for m in re.finditer(r"\b" + re.escape(kw) + r"\b", lower):
            tail = lower[m.end():m.end() + 15]
            if re.match(r"\s+sax", tail):
                continue
            found.append(kw)
            break
    return found


def meta_cache_get(tid, cache):
    if tid not in cache:
        with open(os.path.join(METADATA_DIR, f"{tid}.json"), encoding="utf-8") as f:
            cache[tid] = json.load(f)
    return cache[tid]


def group_into_genre_blocks(track_ids, meta_cache):
    blocks = []
    current = []
    current_genre = None
    for tid in track_ids:
        g = meta_cache_get(tid, meta_cache)["requested"]["category"]
        if current and g != current_genre:
            blocks.append(current)
            current = []
        current.append(tid)
        current_genre = g
    if current:
        blocks.append(current)
    return blocks


def split_oversized_block(block, max_size=ALBUM_TARGET_MAX):
    if len(block) <= max_size:
        return [block]
    n = len(block)
    num = math.ceil(n / max_size)
    base, rem = divmod(n, num)
    out, idx = [], 0
    for i in range(num):
        take = base + (1 if i < rem else 0)
        out.append(block[idx:idx + take])
        idx += take
    return out


def pack_pure_chunks(chunks, target_min=ALBUM_TARGET_MIN, target_max=ALBUM_TARGET_MAX):
    groups = []
    current = []
    for chunk in chunks:
        if current and (len(current) + len(chunk) > target_max):
            groups.append(current)
            current = []
        current.extend(chunk)
        if len(current) >= target_max:
            groups.append(current)
            current = []
    if current:
        if groups and len(current) < target_min:
            groups[-1] = groups[-1] + current
        else:
            groups.append(current)
    return groups


def main():
    old_arts = json.load(open(ARTISTS_PATH, encoding="utf-8"))
    old_albums = json.load(open(ALBUMS_PATH, encoding="utf-8"))
    new_vocal = json.load(open(os.path.join(ROOT, "scripts", "_tmp_new_vocal_artists.json"), encoding="utf-8"))
    drafts = json.load(open(os.path.join(ROOT, "scripts", "_tmp_draft_names.json"), encoding="utf-8"))

    old_album_by_trackset = {frozenset(al["track_ids"]): al for al in old_albums}
    instrumental_arts = [a for a in old_arts if a["vocal_type"] == "instrumental"]
    instrumental_keys = {a["artist_key"] for a in instrumental_arts}
    instrumental_albums = [al for al in old_albums if al["artist_key"] in instrumental_keys]

    meta_cache = {}
    final_vocal_arts = []
    final_vocal_albums = []
    missing_names = []
    missing_titles = []

    for art in new_vocal:
        key = art["artist_key"]
        name = art["artist_name"]
        if name is None:
            name = drafts["keep_names"].get(key) or drafts["new_artist_names"].get(key)
        if name is None:
            missing_names.append(key)

        # vocal tone
        tone_counter = Counter()
        tone_lists = []
        for tid in art["track_ids"]:
            m = meta_cache_get(tid, meta_cache)
            tones = extract_vocal_tone(m.get("flow_generated", {}).get("sound_raw", ""))
            tone_lists.append(tones)
            for t in tones:
                tone_counter[t] += 1
        word_track_coverage = Counter()
        for tones in tone_lists:
            for w in set(tones):
                word_track_coverage[w] += 1
        max_coverage = (max(word_track_coverage.values()) / len(art["track_ids"])) if word_track_coverage else 0.0

        final_vocal_arts.append({
            "artist_key": key,
            "vocal_type": art["vocal_type"],
            "language": art["language"],
            "genre_cluster": art["genre_cluster"],
            "genres": art["genres"],
            "track_count": art["track_count"],
            "track_ids": art["track_ids"],
            "vocal_tone_summary": dict(tone_counter),
            "vocal_tone_max_coverage": round(max_coverage, 2),
            "artist_name": name,
        })

        # albums (genre-aware rebuild)
        blocks = group_into_genre_blocks(art["track_ids"], meta_cache)
        pure_chunks = []
        for b in blocks:
            pure_chunks.extend(split_oversized_block(b))
        album_groups = pack_pure_chunks(pure_chunks)

        for i, g in enumerate(album_groups, start=1):
            album_key = f"{key}_album{i:02d}"
            override = drafts["album_title_overrides"].get(album_key)
            old_al = old_album_by_trackset.get(frozenset(g))
            if override:
                title = override
            elif old_al is not None:
                title = old_al["album_title"]
            else:
                title = None
                missing_titles.append(album_key)
            final_vocal_albums.append({
                "album_key": album_key,
                "artist_key": key,
                "track_ids": g,
                "track_count": len(g),
                "album_title": title,
            })

    if missing_names:
        raise SystemExit(f"이름 누락: {missing_names}")
    if missing_titles:
        raise SystemExit(f"앨범명 누락: {missing_titles}")

    final_artists = instrumental_arts + final_vocal_arts
    final_artists.sort(key=lambda a: a["artist_key"])
    final_albums = instrumental_albums + final_vocal_albums
    final_albums.sort(key=lambda a: a["album_key"])

    # ---- sanity checks before writing ----
    total_tracks_artists = sum(a["track_count"] for a in final_artists)
    total_tracks_albums = sum(a["track_count"] for a in final_albums)
    all_track_ids_artists = set()
    for a in final_artists:
        all_track_ids_artists.update(a["track_ids"])
    all_track_ids_albums = set()
    for al in final_albums:
        all_track_ids_albums.update(al["track_ids"])
    assert all_track_ids_artists == all_track_ids_albums, "아티스트/앨범 트랙 집합 불일치"
    assert len(final_artists) == len(instrumental_arts) + len(new_vocal), "아티스트 수 불일치"

    print(f"최종 아티스트: {len(final_artists)}명 (인스트루멘탈 {len(instrumental_arts)} + 보컬 {len(final_vocal_arts)})")
    print(f"최종 앨범: {len(final_albums)}개 (인스트루멘탈 {len(instrumental_albums)} + 보컬 {len(final_vocal_albums)})")
    print(f"트랙 총합(아티스트 기준): {total_tracks_artists}, (앨범 기준): {total_tracks_albums}")
    print(f"고유 트랙 수: {len(all_track_ids_artists)}")

    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    shutil.copy2(ARTISTS_PATH, ARTISTS_PATH + f".bak_before_gender_flip_rename_{ts}")
    shutil.copy2(ALBUMS_PATH, ALBUMS_PATH + f".bak_before_gender_flip_rename_{ts}")

    tmp = ARTISTS_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(final_artists, f, ensure_ascii=False, indent=2)
    os.replace(tmp, ARTISTS_PATH)

    tmp = ALBUMS_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(final_albums, f, ensure_ascii=False, indent=2)
    os.replace(tmp, ALBUMS_PATH)

    print("\n반영 완료.")
    print(f"백업: {ARTISTS_PATH}.bak_before_gender_flip_rename_{ts}")
    print(f"백업: {ALBUMS_PATH}.bak_before_gender_flip_rename_{ts}")


if __name__ == "__main__":
    main()
