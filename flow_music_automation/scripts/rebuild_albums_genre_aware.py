# -*- coding: utf-8 -*-
"""
앨범 재구성 v2: 세부 장르(requested.category) 블록을 절대 쪼개지 않는 방식.

v1의 문제: 장르 블록이 MAX(7)보다 크면 강제로 끊었는데, 그 강제 절단이 트랙 단위로
일어나서 "블록의 나머지"가 다음 장르와 한 앨범에 섞이는 경우가 남아있었음(예: 여러
아티스트에서 장르 A 8곡 중 7곡+장르 B 일부가 한 앨범에 뒤섞임). 실제로 사용자가
듣고 발견한 문제(indie_rock 2곡이 alt_rock/hard_rock 앨범에 섞여 있던 것)와 같은
유형이 여전히 통계적으로 남아있었음.

v2 방식:
  1) 트랙을 세부 장르(requested.category) 블록으로 먼저 나눈다(순서 보존).
  2) 블록이 MAX보다 크면 그 블록 "안에서만" balanced_split으로 균등 분할한다
     (다른 장르가 섞여 들어갈 일이 없음 -- 항상 순수 단일 장르 청크가 됨).
  3) 이렇게 만들어진 순수 단일장르 청크들을 순서대로 훑으면서, 앨범이 MIN(4) 미만이면
     다음 청크를 이어붙이고, 이어붙이면 MAX(7)를 넘길 경우에만 먼저 끊는다.
     -> 청크를 쪼개서 섞는 일이 없고, "이어붙임"은 항상 청크 전체 단위로만 일어남.
  4) 마지막에 남은 잔여 청크가 MIN 미만이면 직전 앨범에 통째로 합친다(장르가 섞일 수는
     있지만, 최소한 해당 소수 장르의 곡들이 여러 앨범에 걸쳐 흩어지지는 않음 -- 인접한
     하나의 앨범에만 온전히 들어감).
"""
import json
import os
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ALBUM_TARGET_MIN = 4
ALBUM_TARGET_MAX = 7


def genre_of(track_id, cache):
    if track_id not in cache:
        with open(os.path.join(ROOT, "downloads", "metadata", f"{track_id}.json"), encoding="utf-8") as f:
            cache[track_id] = json.load(f)["requested"]["category"]
    return cache[track_id]


def group_into_genre_blocks(track_ids, genre_cache):
    blocks = []
    current = []
    current_genre = None
    for tid in track_ids:
        g = genre_of(tid, genre_cache)
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
    import math
    n = len(block)
    num = math.ceil(n / max_size)
    base, rem = divmod(n, num)
    out = []
    idx = 0
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
    arts = json.load(open(os.path.join(ROOT, "artists.json"), encoding="utf-8"))
    old_albums = json.load(open(os.path.join(ROOT, "albums.json"), encoding="utf-8"))
    old_by_track_set = {frozenset(al["track_ids"]): al for al in old_albums}

    genre_cache = {}
    new_albums = []
    unchanged = 0
    changed = 0

    for a in arts:
        blocks = group_into_genre_blocks(a["track_ids"], genre_cache)
        pure_chunks = []
        for b in blocks:
            pure_chunks.extend(split_oversized_block(b))
        groups = pack_pure_chunks(pure_chunks)

        for i, g in enumerate(groups, start=1):
            album_key = f"{a['artist_key']}_album{i:02d}"
            old = old_by_track_set.get(frozenset(g))
            if old is not None:
                title = old["album_title"]
                title_reading = old.get("album_title_reading")
                unchanged += 1
            else:
                title = None
                title_reading = None
                changed += 1
            new_albums.append({
                "album_key": album_key,
                "artist_key": a["artist_key"],
                "track_ids": g,
                "track_count": len(g),
                "album_title": title,
                "album_title_reading": title_reading,
            })

    json.dump(new_albums, open(os.path.join(ROOT, "albums.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)

    print(f"total artists: {len(arts)}")
    print(f"total albums (new): {len(new_albums)} (was {len(old_albums)})")
    print(f"albums unchanged (exact same track set, title kept): {unchanged}")
    print(f"albums changed/new (need new title): {changed}")


if __name__ == "__main__":
    main()
