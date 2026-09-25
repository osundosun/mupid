"""피처 저장소 합성 데이터와 **Spring 규칙의 파이썬 사본** (S15P21A204-455).

측정 도구(`bench_feature_store.py`)와 시험(`tests/test_feature_store.py`)이 함께 쓴다.

같은 합성 MySQL 표 한 벌에서 두 가지를 만든다.

    spring_catalog_body(db, keep)   Spring 이 추천 요청 본문에 싣는 `catalog`
                                    (`RecommenderCatalogServiceImpl.build` + `RecommenderCatalog.body`)
    clickhouse_rows(db)             Spring 이 ClickHouse 에 쓰는 사본 행
                                    (`TrackFeatureSource` → `track_features_local`,
                                     `TrackSectionSync.toRows` → `track_sections_local`)

서빙 읽기 모듈이 뒤의 것을 읽어 앞의 것과 **같은 값**을 내야 한다 — 458 에서 본문을 읽기
모듈로 바꿔 끼워도 순위가 바뀌지 않는다는 근거다. 두 함수는 자바 쪽 규칙을 옮긴 것이라
자바를 고치면 여기도 고친다.

**값은 무작위다.** 분포(구간 수·장르 수·삭제 비율)만 운영과 비슷하게 잡았다.
"""
from __future__ import annotations

import random
from dataclasses import dataclass, field
from typing import Callable, Optional

LABELS = ["INTRO", "VERSE", "PRE_CHORUS", "CHORUS", "BRIDGE", "OUTRO", "INST"]
GENRES = ["contemporary_pop", "pop_ballad", "indie_rock", "modern_jazz", "hip_hop",
          "rnb_soul", "edm", "lofi", "acoustic_folk", "classical_crossover", "city_pop",
          "trot", "metal", "punk", "reggae", "latin", "country", "ambient", "house",
          "techno", "drum_and_bass", "k_indie", "k_ballad", "ost", "musical"]
MOODS = ["serene", "uplifting", "ethereal", "energetic", "focused", "emotional", "intense",
         "romantic", "melancholic", "playful", "dreamy", "dark", "hopeful", "nostalgic",
         "groovy", "calm", "epic"]
LANGUAGES = ["ko", "en", "ja", None]
CATEGORIES = ["pop", "ballad", "dance", "inst", None]
VERSION = "v1"


@dataclass
class Music:
    music_id: int
    is_deleted: bool = False


@dataclass
class Stream:
    music_id: int
    status: str            # PENDING · IN_PROGRESS · DONE
    duration_ms: int


@dataclass
class Analysis:
    music_id: int
    has_vocal: Optional[bool]
    vocal_gender: Optional[str]
    bpm: Optional[float]
    bpm_requested_min: Optional[int]
    bpm_requested_max: Optional[int]
    language: Optional[str]
    category: Optional[str]


@dataclass
class Section:
    music_section_analysis_id: int
    music_id: int
    index: int
    external_id: str
    section_label: str
    start_ms: int
    end_ms: int
    energy: Optional[float]
    analysis_version: str = VERSION


@dataclass
class Db:
    """합성 MySQL. 장르·무드는 (곡 번호, 코드 번호, 코드 이름)."""

    music: list = field(default_factory=list)
    streams: list = field(default_factory=list)
    analysis: list = field(default_factory=list)
    genres: list = field(default_factory=list)
    moods: list = field(default_factory=list)
    sections: list = field(default_factory=list)


def synth(n_tracks: int, seed: int = 455, per_track: int = 9) -> Db:
    """곡 `n_tracks` 개. 곡마다 구간 약 `per_track` 개(±3).

    일부러 섞는 모서리: 삭제된 곡(약 2.5%), 재생 가능한 스트림이 없는 곡(약 3%), 분석 행이 없는
    곡(약 2%), 에너지가 빈 구간, 장르·무드가 없는 곡.
    """
    rng = random.Random(seed)
    db = Db()
    sid = 1
    for i in range(n_tracks):
        mid = 1000 + i
        db.music.append(Music(mid, is_deleted=rng.random() < 0.025))
        dur = rng.randint(90_000, 300_000)
        r = rng.random()
        if r < 0.03:
            db.streams.append(Stream(mid, "IN_PROGRESS", dur))
        else:
            db.streams.append(Stream(mid, "DONE", dur))
            if rng.random() < 0.1:       # 코덱이 둘인 곡 — 길이는 큰 쪽이다
                db.streams.append(Stream(mid, "DONE", dur + rng.randint(1, 900)))
        if rng.random() >= 0.02:
            vocal = rng.random() < 0.7
            lo = rng.choice([None, 80, 90, 100, 110])
            db.analysis.append(Analysis(
                mid, has_vocal=vocal if rng.random() > 0.05 else None,
                vocal_gender=rng.choice(["M", "F", "MIX", None]) if vocal else None,
                bpm=round(rng.uniform(60, 180), 3) if rng.random() > 0.05 else None,
                bpm_requested_min=lo, bpm_requested_max=None if lo is None else lo + 20,
                language=rng.choice(LANGUAGES), category=rng.choice(CATEGORIES)))
        for code in sorted(rng.sample(range(len(GENRES)), rng.choice([0, 1, 2, 2, 3]))):
            db.genres.append((mid, 100 + code, GENRES[code]))
        for code in sorted(rng.sample(range(len(MOODS)), rng.choice([0, 1, 2, 3]))):
            db.moods.append((mid, 32 + code, MOODS[code]))
        n = max(1, per_track + rng.randint(-3, 3))
        edges = sorted(rng.sample(range(1_000, dur - 1_000), n - 1))
        bounds = [0, *edges, dur]
        track = f"T{mid:06d}"
        for k in range(n):
            energy = None if rng.random() < 0.03 else rng.random()
            db.sections.append(Section(sid, mid, k, f"{track}#{k}", rng.choice(LABELS),
                                       bounds[k], bounds[k + 1], energy))
            sid += 1
    return db


def _grouped(rows) -> dict:
    out: dict = {}
    for mid, code_id, name in sorted(rows, key=lambda r: (r[0], r[1])):
        out.setdefault(mid, []).append(name)
    return out


def spring_catalog_body(db: Db, keep: Optional[Callable[[str], bool]] = None) -> dict:
    """`RecommenderCatalogServiceImpl.build()` 뒤 `RecommenderCatalog.body(keep)`.

    후보 = 지워지지 않았고 · `DONE` 스트림이 있고 · 구간이 있는 곡. 곡 길이는 `DONE` 스트림의
    최댓값. 곡 ID 는 구간 `external_id` 의 `#` 앞(곡 번호·시작 시각순 첫 구간). 곡은 남은
    구간이 있는 것만 싣는다.
    """
    alive = {m.music_id for m in db.music if not m.is_deleted}
    duration: dict = {}
    for s in db.streams:
        if s.status == "DONE" and s.music_id in alive:
            duration[s.music_id] = max(duration.get(s.music_id, 0), s.duration_ms)
    has_sections = {s.music_id for s in db.sections}
    candidates = {m for m in duration if m in has_sections}
    analysis = {a.music_id: a for a in db.analysis}
    genres, moods = _grouped(db.genres), _grouped(db.moods)

    track_of: dict = {}
    kept = []
    for s in sorted(db.sections, key=lambda s: (s.music_id, s.start_ms)):
        if s.music_id not in alive or s.music_id not in candidates:
            continue
        uid = s.external_id.strip()
        hash_at = uid.find("#")
        if hash_at <= 0:
            continue
        track = uid[:hash_at]
        seen = track_of.setdefault(s.music_id, track)
        if seen != track:
            continue
        kept.append((s, uid, track))

    sections = [(s, uid, track) for s, uid, track in kept if keep is None or keep(uid)]
    used = {track for _, _, track in sections}
    tracks = []
    for mid, track in track_of.items():
        if track not in used:
            continue
        a = analysis.get(mid)
        tracks.append({
            "track": track, "duration_ms": duration[mid],
            "bpm": a.bpm if a else None,
            "bpm_requested_min": a.bpm_requested_min if a else None,
            "bpm_requested_max": a.bpm_requested_max if a else None,
            "language": a.language if a else None,
            "category": a.category if a else None,
            "genres": genres.get(mid, []), "moods": moods.get(mid, []),
            "has_vocal": a.has_vocal if a else None,
            "vocal_gender": a.vocal_gender if a else None,
        })
    return {
        "tracks": {c: [t[c] for t in tracks] for c in (
            "track", "duration_ms", "bpm", "bpm_requested_min", "bpm_requested_max",
            "language", "category", "genres", "moods", "has_vocal", "vocal_gender")},
        "sections": {
            "external_id": [uid for _, uid, _ in sections],
            "section_label": [s.section_label for s, _, _ in sections],
            "start_ms": [s.start_ms for s, _, _ in sections],
            "end_ms": [s.end_ms for s, _, _ in sections],
            "energy": [s.energy for s, _, _ in sections],
        },
    }


def feature_rows(db: Db, version: int = 1) -> list:
    """Spring `TrackFeatureSource` 가 만드는 `track_features_local` 행. **모든 곡**이다 —
    삭제된 곡·재생할 수 없는 곡도 `is_deleted`·`playable` 로 남는다."""
    duration: dict = {}
    for s in db.streams:
        if s.status == "DONE":
            duration[s.music_id] = max(duration.get(s.music_id, 0), s.duration_ms)
    first: dict = {}
    for s in sorted(db.sections, key=lambda s: (s.music_id, s.start_ms)):
        first.setdefault(s.music_id, s)
    analysis = {a.music_id: a for a in db.analysis}
    genres, moods = _grouped(db.genres), _grouped(db.moods)
    rows = []
    for m in db.music:
        a = analysis.get(m.music_id)
        f = first.get(m.music_id)
        uid = f.external_id.strip() if f else ""
        rows.append({
            "track_id": m.music_id,
            "track": uid[:uid.find("#")] if uid.find("#") > 0 else "",
            "duration_ms": duration.get(m.music_id),
            "bpm": a.bpm if a else None,
            "bpm_requested_min": a.bpm_requested_min if a else None,
            "bpm_requested_max": a.bpm_requested_max if a else None,
            "language": a.language if a else None,
            "category": a.category if a else None,
            "genres": genres.get(m.music_id, []),
            "moods": moods.get(m.music_id, []),
            "has_vocal": a.has_vocal if a else None,
            "vocal_gender": a.vocal_gender if a else None,
            "is_deleted": m.is_deleted,
            "playable": m.music_id in duration,
            "analysis_version": f.analysis_version if f else "",
            "version": version,
        })
    return rows


def section_rows(db: Db) -> list:
    """Spring `TrackSectionSync.toRows` 가 만드는 `track_sections_local` 행."""
    return [{
        "track_id": s.music_id, "music_section_analysis_id": s.music_section_analysis_id,
        "analysis_version": s.analysis_version, "section_index": s.index,
        "section_uid": s.external_id, "fn": s.section_label,
        "start_ms": s.start_ms, "end_ms": s.end_ms, "energy": s.energy,
    } for s in db.sections]
