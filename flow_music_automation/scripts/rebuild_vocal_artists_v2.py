# -*- coding: utf-8 -*-
"""
보컬 아티스트 재그룹핑 v2: indie_rock을 rock_edge에서 분리.

배경(사용자가 실제로 들어보고 발견): "언더락"(alt_rock+hard_rock+indie_rock+punk_power_pop)
안에 "느린 걸음"(indie_rock) 앨범이 나머지(폭발=alt_rock+hard_rock, 내리막=punk_power_pop)와
색깔이 전혀 안 맞음. indie_rock은 애초에 hard_rock/alt_rock/punk_power_pop과 같은 "공격적인
록" 계열이 아니라 인디/멜로딕 계열에 더 가까움 -- 클러스터 정의 자체가 잘못됐던 것.

수정 2가지:
  1) indie_rock: rock_edge -> indie_soft 로 클러스터 재배정.
  2) rock_edge <-> rnb_soul 는 절대 서로 합치지 않음(기존엔 알파벳 순서상 우연히 붙어있어서
     인원 부족시 자동으로 섞여버렸음 -- 록과 알앤비/소울은 성격이 너무 다름).

인스트루멘탈은 건드리지 않음(이번 지적은 보컬 한정). 아티스트 이름은 이번엔 새로 안 지음 --
기존 track_ids 구성이 정확히 동일한 아티스트는 그대로 이름 보존, 구성이 바뀐 것만 골라서
따로 보고하고 다음 단계에서 재명명.
"""
import json
import math
from pathlib import Path
from collections import defaultdict

ROOT = Path(__file__).resolve().parent.parent
METADATA_DIR = ROOT / "downloads" / "metadata"

TARGET_MIN = 6
TARGET_MAX = 15

VOCAL_GENRE_CLUSTER = {
    "contemporary_pop": "pop_mainstream",
    "dance_pop": "pop_mainstream",
    "synthpop": "pop_mainstream",
    "city_pop": "pop_mainstream",
    "pop_ballad": "pop_mainstream",
    "poprock": "pop_mainstream",
    "alternative_rock": "rock_edge",
    "hard_rock": "rock_edge",
    "punk_power_pop": "rock_edge",
    "indie_rock": "indie_soft",   # <-- 변경 (기존: rock_edge)
    "indie_pop": "indie_soft",
    "acoustic_guitar_folk": "indie_soft",
    "bossa_smooth_jazz": "indie_soft",
    "boom_bap_hiphop": "hiphop_urban",
    "modern_trap_hiphop": "hiphop_urban",
    "singing_rap_melodic_hiphop": "hiphop_urban",
    "alternative_rnb": "rnb_soul",
    "rnb": "rnb_soul",
    "neo_soul": "rnb_soul",
    "house": "dance_electronic",
    "future_bass": "dance_electronic",
}

INCOMPATIBLE_MERGE_PAIRS = {frozenset({"rock_edge", "rnb_soul"})}


def balanced_split(items, max_size=TARGET_MAX):
    n = len(items)
    if n <= max_size:
        return [items]
    num_groups = math.ceil(n / max_size)
    base, rem = divmod(n, num_groups)
    out, idx = [], 0
    for i in range(num_groups):
        take = base + (1 if i < rem else 0)
        out.append(items[idx:idx + take])
        idx += take
    return out


def _is_incompatible(cluster_a, cluster_b):
    return frozenset({cluster_a, cluster_b}) in INCOMPATIBLE_MERGE_PAIRS


def bin_pack(sorted_tracks, key_fn):
    groups = []
    current = []
    current_clusters = set()  # 현재 그룹 안에 실제로 들어있는 클러스터 집합
    for t in sorted_tracks:
        c = key_fn(t)
        would_be_incompatible = current and any(_is_incompatible(pc, c) for pc in current_clusters)
        if current and would_be_incompatible:
            # 호환 안 되는 클러스터가 들어오면 MIN 미달이어도 무조건 지금까지 걸로 끊음
            groups.append(current)
            current = []
            current_clusters = set()
        elif current and c not in current_clusters and len(current) >= TARGET_MIN:
            groups.append(current)
            current = []
            current_clusters = set()
        if current and len(current) >= TARGET_MAX:
            groups.append(current)
            current = []
            current_clusters = set()
        current.append(t)
        current_clusters.add(c)
    if current:
        if groups:
            prev_clusters = {key_fn(x) for x in groups[-1]}
            incompatible = any(_is_incompatible(pc, cc) for pc in prev_clusters for cc in current_clusters)
        else:
            incompatible = False

        if len(current) < TARGET_MIN and groups and not incompatible:
            combined = groups.pop() + current
            groups.extend(balanced_split(combined))
        else:
            groups.append(current)
    return groups


def parse_genre(category, vocal_type, language):
    prefix = f"{vocal_type}_{language}_"
    assert category.startswith(prefix), category
    return category[len(prefix):]


def main():
    old_arts = json.load(open(ROOT / "artists.json", encoding="utf-8"))
    old_albums = json.load(open(ROOT / "albums.json", encoding="utf-8"))

    old_vocal_keys = {a["artist_key"] for a in old_arts if a["vocal_type"] != "instrumental"}
    old_by_trackset = {frozenset(a["track_ids"]): a for a in old_arts}

    # 1) 전체 보컬 트랙 다시 로드 (metadata 원본에서, 최신 male_japanese 보강분 포함)
    tracks = []
    for f in sorted(METADATA_DIR.glob("*.json")):
        d = json.loads(f.read_text(encoding="utf-8"))
        r = d.get("requested", {})
        vt, lang = r.get("vocal_type"), r.get("language")
        if vt == "instrumental" or vt is None:
            continue
        genre = parse_genre(r["category"], vt, lang)
        cluster = VOCAL_GENRE_CLUSTER[genre]
        tracks.append({"track_id": d["track_id"], "vocal_type": vt, "language": lang,
                        "genre": genre, "cluster": cluster})

    buckets = defaultdict(list)
    for t in tracks:
        buckets[(t["vocal_type"], t["language"])].append(t)

    new_arts = []
    artist_seq = defaultdict(int)
    for (vt, lang), bucket in sorted(buckets.items(), key=lambda x: (x[0][0] or "", x[0][1] or "")):
        bucket_sorted = sorted(bucket, key=lambda t: (t["cluster"], t["genre"], t["track_id"]))
        groups = bin_pack(bucket_sorted, key_fn=lambda t: t["cluster"])
        for group in groups:
            genres_in_group = sorted(set(t["genre"] for t in group))
            clusters_in_group = sorted(set(t["cluster"] for t in group))
            primary_cluster = clusters_in_group[0] if len(clusters_in_group) == 1 else "+".join(clusters_in_group)
            artist_seq[(vt, lang, primary_cluster)] += 1
            seq = artist_seq[(vt, lang, primary_cluster)]
            artist_key = f"{vt}_{lang}_{primary_cluster}_{seq:02d}"
            new_arts.append({
                "artist_key": artist_key,
                "vocal_type": vt,
                "language": lang,
                "genre_cluster": primary_cluster,
                "genres": genres_in_group,
                "track_count": len(group),
                "track_ids": [t["track_id"] for t in group],
            })

    # 2) 기존과 대조: track_ids 집합이 정확히 동일하면 "안 바뀜"으로 이름 보존
    unchanged, changed = [], []
    for a in new_arts:
        old = old_by_trackset.get(frozenset(a["track_ids"]))
        if old is not None:
            a["artist_name"] = old["artist_name"]
            unchanged.append(a["artist_key"])
        else:
            a["artist_name"] = None
            changed.append(a)

    print(f"보컬 트랙 총: {len(tracks)}")
    print(f"새 보컬 아티스트 총: {len(new_arts)} (기존 보컬 아티스트: {len(old_vocal_keys)})")
    print(f"이름 보존(구성 동일): {len(unchanged)}")
    print(f"이름 새로 지어야 함(구성 바뀜/신규): {len(changed)}")
    print()
    for a in changed:
        print(f"  [NEW/CHANGED] {a['artist_key']} | {a['genre_cluster']} | {a['genres']} | {a['track_count']}곡")
        print(f"      tracks={a['track_ids']}")

    # 결과를 임시 파일로 저장 (아직 artists.json/albums.json 덮어쓰지 않음 -- 검토 먼저)
    json.dump(new_arts, open(ROOT / "scripts" / "_tmp_new_vocal_artists.json", "w", encoding="utf-8"),
               ensure_ascii=False, indent=2)
    print()
    print("검토용 임시 파일: scripts/_tmp_new_vocal_artists.json (아직 실제 반영 안 됨)")


if __name__ == "__main__":
    main()
