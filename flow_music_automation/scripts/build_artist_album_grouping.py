# -*- coding: utf-8 -*-
"""
가상 아티스트/앨범 그룹핑(분류) 빌더.

목적: downloads/metadata의 1040곡을 (vocal_type=성별, language, genre_cluster) 기준으로
아티스트 단위로 묶고, 각 아티스트 안에서 다시 앨범 단위로 나눈다.
이름/소개 등 페르소나 디테일은 이번 단계 범위 밖 (다음 단계에서 별도 진행).

출력:
  - artists.json : 아티스트 그룹 목록 (임시 key, track_ids, 장르 구성)
  - albums.json  : 각 아티스트 내부의 앨범 그룹 목록
  - logs/artist_album_grouping_report.txt : 검토용 요약 리포트 (보컬 음색 편차 경고 포함)
"""
import json
import glob
import re
from pathlib import Path
from collections import defaultdict, Counter

ROOT = Path(__file__).resolve().parent.parent
METADATA_DIR = ROOT / "downloads" / "metadata"

TARGET_MIN = 6
TARGET_MAX = 15
ALBUM_TARGET_MIN = 4
ALBUM_TARGET_MAX = 7

# ---------------------------------------------------------------------------
# 장르 -> 슈퍼클러스터 매핑 (보컬용, 프로덕션 스타일이 겹치는 장르끼리 묶음)
# ---------------------------------------------------------------------------
VOCAL_GENRE_CLUSTER = {
    "contemporary_pop": "pop_mainstream",
    "dance_pop": "pop_mainstream",
    "synthpop": "pop_mainstream",
    "city_pop": "pop_mainstream",
    "pop_ballad": "pop_mainstream",
    "poprock": "pop_mainstream",
    "alternative_rock": "rock_edge",
    "indie_rock": "rock_edge",
    "hard_rock": "rock_edge",
    "punk_power_pop": "rock_edge",
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

# 기악용 슈퍼클러스터
INSTRUMENTAL_GENRE_CLUSTER = {
    "acoustic_chill": "chill_lofi",
    "chillhop": "chill_lofi",
    "lofi_hiphop": "chill_lofi",
    "jazzhop": "chill_lofi",
    "downtempo": "chill_lofi",
    "ambient": "ambient_atmos",
    "ambient_electronic": "ambient_atmos",
    "atmospheric_post_rock": "ambient_atmos",
    "chillwave_dreamy_electronica": "ambient_atmos",
    "cinematic_orchestral": "orchestral_cinematic",
    "emotional_drama": "orchestral_cinematic",
    "neo_classical": "orchestral_cinematic",
    "chamber_classical": "orchestral_cinematic",
    "solo_piano": "orchestral_cinematic",
    "adventure_fantasy": "game_bgm",
    "casual_light_game_bgm": "game_bgm",
    "game_battle_bgm": "game_bgm",
    "mystery_suspense": "game_bgm",
    "jazz_fusion": "jazz_funk",
    "modern_jazz": "jazz_funk",
    "funk": "jazz_funk",
    "neo_soul": "jazz_funk",
    "alternative_rnb": "jazz_funk",
    "drum_and_bass": "electronic_beats",
    "breakbeat_uk_garage": "electronic_beats",
    "synthwave": "electronic_beats",
    "synthpop": "electronic_beats",
    "house": "electronic_beats",
    "future_bass": "electronic_beats",
    "boom_bap_hiphop": "hiphop_beats",
    "modern_trap_hiphop": "hiphop_beats",
    "acoustic_guitar_folk": "acoustic_folk",
    "hard_rock": "acoustic_folk",  # 소규모라 어쿠스틱 계열에 편입 (성격은 다르지만 곡 수 10개라 별도 클러스터 불필요)
}

VOCAL_TONE_KEYWORDS = [
    "soprano", "alto", "tenor", "baritone", "bass-baritone",
    "breathy", "raspy", "powerful", "airy", "warm", "bright",
    "husky", "smoky", "silky", "belting", "falsetto", "gritty",
    "sweet", "youthful", "mature", "soft", "clear",
]

# 성별과 반대되는 음역 용어가 섞여 있으면 진짜 데이터 이슈일 가능성이 높음 (텍스처 형용사와 달리 성부 용어는 원래 성별과 어긋나면 안 됨)
FEMALE_REGISTER = {"soprano", "alto"}
MALE_REGISTER = {"tenor", "baritone", "bass-baritone"}

# 그룹 내 음색 일관성 판단 임계값: 어떤 한 단어가 그룹의 이 비율 이상을 커버하면 "일관성 있음"으로 봄
TONE_COVERAGE_OK_RATIO = 0.4


def load_all_metadata():
    records = []
    for f in sorted(glob.glob(str(METADATA_DIR / "*.json"))):
        d = json.loads(Path(f).read_text(encoding="utf-8"))
        records.append(d)
    return records


def parse_genre(category: str, vocal_type: str, language: str) -> str:
    if vocal_type == "instrumental":
        prefix = "instrumental_"
        assert category.startswith(prefix), category
        return category[len(prefix):]
    prefix = f"{vocal_type}_{language}_"
    assert category.startswith(prefix), category
    return category[len(prefix):]


def extract_vocal_tone(sound_raw: str) -> list:
    if not sound_raw:
        return []
    lower = sound_raw.lower()
    found = []
    for kw in VOCAL_TONE_KEYWORDS:
        for m in re.finditer(r"\b" + re.escape(kw) + r"\b", lower):
            # "alto saxophone" / "soprano sax" 같은 악기 표현은 성부 용어가 아니므로 제외
            tail = lower[m.end():m.end() + 15]
            if re.match(r"\s+sax", tail):
                continue
            found.append(kw)
            break
    return found


def balanced_split(items, max_size=TARGET_MAX):
    """items를 max_size 이하 청크로 최대한 균등하게 나눈다 (순서 유지)."""
    import math
    n = len(items)
    if n <= max_size:
        return [items]
    num_groups = math.ceil(n / max_size)
    base = n // num_groups
    rem = n % num_groups
    out = []
    idx = 0
    for i in range(num_groups):
        take = base + (1 if i < rem else 0)
        out.append(items[idx:idx + take])
        idx += take
    return out


def bin_pack(sorted_tracks, key_fn):
    """sorted_tracks: track dict 리스트, 이미 (cluster, genre, track_id) 순 정렬됨.
    key_fn(track) -> cluster 식별자. cluster 경계에서 우선 끊되, TARGET_MIN 미만이면 다음 cluster까지 이어붙인다.
    마지막에 TARGET_MIN 미만 잔여분이 남으면, 이전 그룹과 합쳐서 균등 재분배한다
    (무조건 이전 그룹에 그대로 붙이면 TARGET_MAX를 크게 초과하는 대형 그룹이 생기기 때문).
    """
    groups = []
    current = []
    current_cluster = None
    for t in sorted_tracks:
        c = key_fn(t)
        if current and c != current_cluster and len(current) >= TARGET_MIN:
            groups.append(current)
            current = []
        if current and len(current) >= TARGET_MAX:
            groups.append(current)
            current = []
        current.append(t)
        current_cluster = c
    if current:
        if groups and len(current) < TARGET_MIN:
            combined = groups.pop() + current
            groups.extend(balanced_split(combined))
        else:
            groups.append(current)
    return groups


def chunk_album(track_ids, size_min=ALBUM_TARGET_MIN, size_max=ALBUM_TARGET_MAX):
    n = len(track_ids)
    if n <= size_max:
        return [track_ids]
    import math
    num_albums = math.ceil(n / size_max)
    base = n // num_albums
    rem = n % num_albums
    albums = []
    idx = 0
    for i in range(num_albums):
        take = base + (1 if i < rem else 0)
        albums.append(track_ids[idx:idx + take])
        idx += take
    return albums


def main():
    records = load_all_metadata()
    print(f"loaded {len(records)} metadata files")

    tracks = []
    for d in records:
        r = d.get("requested", {})
        vocal_type = r.get("vocal_type")
        language = r.get("language")
        category = r.get("category")
        genre = parse_genre(category, vocal_type, language)
        cluster = (
            INSTRUMENTAL_GENRE_CLUSTER.get(genre, f"other_{genre}")
            if vocal_type == "instrumental"
            else VOCAL_GENRE_CLUSTER.get(genre, f"other_{genre}")
        )
        tone = extract_vocal_tone(d.get("flow_generated", {}).get("sound_raw", "")) if vocal_type != "instrumental" else []
        tracks.append({
            "track_id": d["track_id"],
            "vocal_type": vocal_type,
            "language": language,
            "genre": genre,
            "cluster": cluster,
            "vocal_tone": tone,
            "flow_title": d.get("flow_title"),
        })

    # bucket by (vocal_type, language) -- instrumental language=None
    buckets = defaultdict(list)
    for t in tracks:
        buckets[(t["vocal_type"], t["language"])].append(t)

    artists = []
    warnings = []
    artist_seq = defaultdict(int)

    for (vocal_type, language), bucket_tracks in sorted(buckets.items(), key=lambda x: (x[0][0] or "", x[0][1] or "")):
        bucket_tracks_sorted = sorted(bucket_tracks, key=lambda t: (t["cluster"], t["genre"], t["track_id"]))
        groups = bin_pack(bucket_tracks_sorted, key_fn=lambda t: t["cluster"])
        for group in groups:
            genres_in_group = sorted(set(t["genre"] for t in group))
            clusters_in_group = sorted(set(t["cluster"] for t in group))
            primary_cluster = clusters_in_group[0] if len(clusters_in_group) == 1 else "+".join(clusters_in_group)
            artist_seq[(vocal_type, language, primary_cluster)] += 1
            seq = artist_seq[(vocal_type, language, primary_cluster)]
            lang_part = language if language else "inst"
            artist_key = f"{vocal_type}_{lang_part}_{primary_cluster}_{seq:02d}"

            # 보컬 음색 일관성 체크 (경고용, 분리는 하지 않음 -- 리포트로만 남김)
            tone_counter = Counter()
            for t in group:
                for kw in t["vocal_tone"]:
                    tone_counter[kw] += 1
            dominant_tone_coverage = None
            if vocal_type != "instrumental" and group:
                # (1) 텍스처 일관성: 그룹 내 최다 커버리지 단어의 비율이 임계값 미만이면 음색이 흩어진 것으로 보고 경고
                all_tone_lists = [t["vocal_tone"] for t in group]
                word_track_coverage = Counter()
                for tones in all_tone_lists:
                    for w in set(tones):
                        word_track_coverage[w] += 1
                max_coverage = max(word_track_coverage.values()) / len(group) if word_track_coverage else 0.0
                dominant_tone_coverage = round(max_coverage, 2)
                if max_coverage < TONE_COVERAGE_OK_RATIO:
                    warnings.append(
                        f"[TONE-SPREAD] {artist_key}: {len(group)}곡, 최다 음색어 커버리지 {max_coverage:.0%} "
                        f"(임계값 {TONE_COVERAGE_OK_RATIO:.0%} 미만 -> 리뷰 권장) tracks={[t['track_id'] for t in group]}"
                    )
                # (2) 성부 오염: female 그룹에 남성 성부 용어, male 그룹에 여성 성부 용어가 섞이면 실제 데이터 이슈 가능성
                bad_register = MALE_REGISTER if vocal_type == "female" else FEMALE_REGISTER if vocal_type == "male" else set()
                if bad_register:
                    offenders = [t["track_id"] for t in group if bad_register & set(t["vocal_tone"])]
                    if offenders:
                        warnings.append(
                            f"[REGISTER-MISMATCH] {artist_key}: {vocal_type} 그룹인데 반대 성부 용어 검출 -> tracks={offenders}"
                        )

            artists.append({
                "artist_key": artist_key,
                "vocal_type": vocal_type,
                "language": language,
                "genre_cluster": primary_cluster,
                "genres": genres_in_group,
                "track_count": len(group),
                "track_ids": [t["track_id"] for t in group],
                "vocal_tone_summary": dict(tone_counter) if vocal_type != "instrumental" else None,
                "vocal_tone_max_coverage": dominant_tone_coverage,
            })

    # 앨범: 아티스트 내부를 발매 단위(4~7곡)로 청크
    albums = []
    for artist in artists:
        album_chunks = chunk_album(artist["track_ids"])
        for i, chunk in enumerate(album_chunks, start=1):
            albums.append({
                "album_key": f"{artist['artist_key']}_album{i:02d}",
                "artist_key": artist["artist_key"],
                "track_ids": chunk,
                "track_count": len(chunk),
            })

    out_artists = ROOT / "artists.json"
    out_albums = ROOT / "albums.json"
    out_artists.write_text(json.dumps(artists, ensure_ascii=False, indent=2), encoding="utf-8")
    out_albums.write_text(json.dumps(albums, ensure_ascii=False, indent=2), encoding="utf-8")

    # 리포트
    report_lines = []
    report_lines.append(f"총 트랙: {len(tracks)}")
    report_lines.append(f"총 아티스트: {len(artists)}")
    report_lines.append(f"총 앨범: {len(albums)}")
    report_lines.append("")
    size_counter = Counter(a["track_count"] for a in artists)
    report_lines.append("아티스트당 곡 수 분포:")
    for size in sorted(size_counter):
        report_lines.append(f"  {size}곡: {size_counter[size]}명")
    too_small = [a for a in artists if a["track_count"] < TARGET_MIN]
    too_big = [a for a in artists if a["track_count"] > TARGET_MAX]
    report_lines.append("")
    report_lines.append(f"목표 범위({TARGET_MIN}~{TARGET_MAX}) 벗어난 아티스트: 작음 {len(too_small)}명 / 큼 {len(too_big)}명")
    for a in too_small:
        report_lines.append(f"  [SMALL] {a['artist_key']} ({a['track_count']}곡) genres={a['genres']}")
    for a in too_big:
        report_lines.append(f"  [BIG]   {a['artist_key']} ({a['track_count']}곡) genres={a['genres']}")
    report_lines.append("")
    report_lines.append(f"보컬 음색 경고: {len(warnings)}건")
    report_lines.extend(f"  {w}" for w in warnings)

    log_dir = ROOT / "logs"
    log_dir.mkdir(exist_ok=True)
    (log_dir / "artist_album_grouping_report.txt").write_text("\n".join(report_lines), encoding="utf-8")

    print("\n".join(report_lines))
    print()
    print(f"artists.json 저장: {out_artists}")
    print(f"albums.json 저장: {out_albums}")


if __name__ == "__main__":
    main()
