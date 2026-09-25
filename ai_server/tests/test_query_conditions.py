"""검색 조건 풀기(`app/query_conditions.py`) — 계약 v3 (S15P21A204-458).

규칙은 Spring `QueryPlanFilter` 를 그대로 옮겼다. 그쪽 시험 `QueryPlanFilterTest` 의 사례를
**전부** 여기로 옮겼고(같은 합성 카탈로그: 곡 6개 × 구간 4개, 짝수 곡은 여성·피아노, 홀수 곡은
남성·기타), Spring 쪽 규칙과 시험은 걷어냈다 — 규칙이 두 곳에 있으면 한쪽만 고쳐진다.

입력은 요청 본문 `catalog` 와 같은 모양의 기준 풀 스냅샷이다. 악기만 본문에 없던 값이라
스냅샷 옆에 구간 → 악기로 따로 준다(피처 저장소 `track_sections.instruments`, 06 마이그레이션).
"""
from __future__ import annotations

from ai_server.app import catalog_body, pool_snapshot, query_conditions as qc

GENRES = ["contemporary_pop", "pop_ballad", "indie_rock"]
LABELS = ["INTRO", "VERSE", "CHORUS", "BRIDGE"]


def _track(tid, *, genres, category, language, moods, has_vocal, gender, bpm,
           lo=None, hi=None):
    return {"track": tid, "duration_ms": 120_000, "bpm": bpm, "bpm_requested_min": lo,
            "bpm_requested_max": hi, "language": language, "category": category,
            "genres": genres, "moods": moods, "has_vocal": has_vocal, "vocal_gender": gender}


def _section(uid, label, start, end, energy):
    return {"external_id": uid, "section_label": label, "start_ms": start, "end_ms": end,
            "energy": energy}


def _facts(tracks, sections, instruments):
    body = {"tracks": {c: [t[c] for t in tracks] for c in catalog_body.TRACK_COLS},
            "sections": {c: [s[c] for s in sections] for c in catalog_body.SECTION_COLS}}
    snap, inst, _ = pool_snapshot.static(body, instruments)
    ps = pool_snapshot.PoolSnapshot(snap, inst, pool_snapshot.SOURCE_STATIC, 1, 0.0)
    return qc.Facts(ps.snap, ps.instruments)


def _catalog(instruments=None, vocal=True):
    """`QueryPlanFilterTest.catalog()` 그대로. `instruments(uid, t)` 로 구간 악기를 바꾼다."""
    tracks, sections, inst = [], [], {}
    for t in range(6):
        tid = f"T{t:06d}"
        tracks.append(_track(tid, genres=[GENRES[t % 3]], category=GENRES[t % 3],
                             language="ko" if t % 2 == 0 else "en",
                             moods=["calm" if t % 2 == 0 else "bright"],
                             has_vocal=True if vocal else None,
                             gender=("F" if t % 2 == 0 else "M") if vocal else None,
                             bpm=70.0 + t * 15, lo=80, hi=120))
        for s in range(4):
            uid = f"{tid}#{s}"
            sections.append(_section(uid, LABELS[s], s * 30_000, s * 30_000 + 28_000,
                                     0.05 + 0.01 * s))
            default = ["piano" if t % 2 == 0 else "guitar"]
            inst[uid] = default if instruments is None else instruments(uid, t, default)
    return tracks, sections, inst


FULL = 24


def _apply(plan, facts=None, track=None, section=None):
    return qc.apply(facts or _facts(*_catalog()), plan, track, section)


def _filters(name, value):
    return {"filters": {name: value}}


def _track_no(uid: str) -> int:
    return int(uid.split("#")[0][1:])


def test_every_axis_actually_gates():
    """장르·보컬·악기 조건이 실제로 후보를 거른다 — 한 축이 안 실리면 아무것도 안 걸러진다."""
    for axis, value in (("genre", GENRES[0]), ("vocal", "female"), ("instrument", "piano")):
        out = _apply(_filters(axis, [value]))
        assert f"filters.{axis}" in out.applied
        assert 1 <= len(out.kept) <= FULL - 1, axis
    # 무드는 거르지 않는다(가점) — 빼는 쪽으로만 게이트다.
    mood = _apply({"exclude": {"mood": ["calm"]}})
    assert mood.applied == ["exclude.mood"]
    assert 1 <= len(mood.kept) <= FULL - 1
    assert len(_apply(_filters("mood", ["calm"])).kept) == FULL


def test_unsatisfiable_condition_returns_nothing():
    """어휘에 없는 필수 조건은 조용히 풀지 않는다 — 빈 결과와 그 이유."""
    out = _apply(_filters("genre", ["존재하지않는장르"]))
    assert not out.satisfiable
    assert out.kept == []
    assert [u.field for u in out.unsatisfiable] == ["genre"]
    assert out.summary()["unsatisfiable"] == [{"field": "genre", "requested": ["존재하지않는장르"]}]


def test_missing_data_is_ignored_not_emptying():
    """카탈로그에 보컬·악기 데이터가 아예 없으면 그 조건은 무시한다 — 후보를 비우지 않는다."""
    bare = _facts(*_catalog(instruments=lambda uid, t, d: [], vocal=False))
    out = _apply({"filters": {"vocal": ["female"], "instrument": ["piano"]}}, bare)
    assert out.satisfiable
    assert len(out.kept) == FULL
    assert "vocal(카탈로그 데이터 없음)" in out.ignored
    assert "instrument(카탈로그 데이터 없음)" in out.ignored


def test_instrument_uses_each_section_then_the_track_union():
    """악기는 구간마다 본다 — 구간에 없으면 곡의 구간 합집합으로 본다."""
    def inst(uid, t, default):
        if uid == "T000000#0":
            return ["drums"]                  # #0 에만 드럼
        if uid == "T000000#1":
            return []                         # #1 은 비운다 → 곡 합집합으로 떨어진다
        return default

    out = _apply(_filters("instrument", ["drums"]), _facts(*_catalog(instruments=inst)))
    assert sorted(out.kept) == ["T000000#0", "T000000#1"]


def test_nonspecific_instrument_never_matches():
    """'그 외 악기'·'other' 는 검색 근거가 아니다."""
    facts = _facts(*_catalog(instruments=lambda uid, t, d: ["그 외 악기", "other"]))
    out = _apply(_filters("instrument", ["other"]), facts)
    # 구체적인 악기가 하나도 없으니 악기 조건 자체가 무시된다.
    assert "instrument(카탈로그 데이터 없음)" in out.ignored


def test_a_value_in_the_vocabulary_is_kept():
    """어휘에 있는 값은 별칭으로 바꾸지 않는다 — 'calm' 은 그대로 걸린다."""
    out = _apply({"exclude": {"mood": ["calm"]}})
    assert "exclude.mood" in out.applied
    assert out.kept and all(_track_no(u) % 2 == 1 for u in out.kept)


def test_same_condition_same_result():
    """같은 조건을 두 번 걸면 같은 결과다 (두 번째는 캐시에서 나온다 — 값이 같아야 한다)."""
    facts = _facts(*_catalog())
    plan = {"filters": {"genre": ["contemporary_pop"], "language": ["ko"]},
            "exclude": {"mood": ["bright"]}}
    first = _apply(plan, facts)
    assert first.kept
    assert _apply(plan, facts) == first
    assert qc._apply(facts, plan, None, None) == first


def test_requested_values_must_be_exact_vocabulary():
    """요청 값은 어휘와 **정확히** 맞아야 남는다 — rock 이 indie_rock 으로 넓혀지지 않는다.

    예전 추천 서버(`_canonicalize_query_tags` 의 `value in vocab`)와 같다. 넓히면 "록" 을
    물었는데 인디 록만 나오는 이유를 설명할 수 없다.
    """
    assert not _apply(_filters("genre", ["rock"])).satisfiable
    kept = _apply(_filters("genre", ["indie_rock"])).kept
    assert kept and all(_track_no(u) % 3 == 2 for u in kept)


def test_genre_matches_the_style_too():
    """장르 조건은 스타일에도 맞춰 본다 — '_' 경계 부분 일치다."""
    # `TestCatalogs.track("S1", ["pop"], "female_korean_ballad", true, "F", 90.0)` 그대로.
    facts = _facts([_track("S1", genres=["pop"], category="female_korean_ballad",
                           language="korean", moods=["calm"], has_vocal=True, gender="F",
                           bpm=90.0)],
                   [_section("S1#0", "CHORUS", 0, 20_000, 0.08)], {"S1#0": []})
    # category 에서 보컬·언어 접두어를 뗀 스타일(ballad)로도 장르 조건이 걸린다.
    assert len(_apply(_filters("genre", ["ballad"]), facts).kept) == 1


def test_flow_matches_the_section_label():
    """구간형(flow)은 구간 라벨을 소문자로 본다."""
    kept = _apply(_filters("flow", "chorus")).kept
    assert kept and all(u.endswith("#2") for u in kept)            # CHORUS 는 #2
    assert not any(u.endswith("#2") for u in _apply({"exclude": {"flow": ["chorus"]}}).kept)


def test_bpm_range_uses_the_requested_midpoint():
    """BPM 범위는 요청 BPM 의 중간값으로 본다 — 측정값은 옥타브 오류가 잦다."""
    # 모든 곡의 요청 범위가 80~120 이라 중간값 100 이다(측정값은 70~145).
    assert len(_apply(_filters("bpm_min", 100)).kept) == FULL
    assert _apply(_filters("bpm_min", 101)).kept == []


def test_directions_need_a_reference():
    """템포·에너지 방향은 기준이 있어야 건다 — 없으면 무시하고 진단에 남긴다."""
    no_ref = _apply(_filters("tempo_direction", "up"))
    assert "filters.tempo_direction(기준곡 없음)" in no_ref.ignored
    assert len(no_ref.kept) == FULL

    higher = _apply(_filters("energy_direction", "up"), track="T000000", section="T000000#1")
    # 기준 구간 에너지 0.06 보다 높은 구간(#2·#3)만 남는다.
    assert higher.kept and all(u.endswith(("#2", "#3")) for u in higher.kept)


# ------------------------------------------------------------------ 옮기면서 더한 것
def test_rules_equal_the_java_value_helpers():
    """`CatalogFacts` 의 값 규칙(정규화·경계 일치·보컬 투영·스타일·BPM)을 그대로 옮겼다."""
    assert qc.qvals(["Hip-Hop", " lo fi ", None, ""]) == ["hip_hop", "lo_fi"]
    assert qc.qmatch(["alternative_rock"], ["rock"]) and not qc.qmatch(["rockabilly"], ["rock"])
    assert qc.vocal_values(None, None) == []
    assert qc.vocal_values(False, "F") == ["instrumental"]
    assert qc.vocal_values(True, "MIX") == ["voice", "mixed"]
    assert qc.vocal_values(True, None) == ["voice"]
    assert qc.parse_style("male_english_pop", ["voice", "male"], "english") == "pop"
    assert qc.facet_bpm(128.0, 80, 120) == 100.0
    assert qc.facet_bpm(128.0, 0, 120) == 128.0 and qc.facet_bpm(None, None, None) == 0.0
    assert qc.specific_instruments(["Piano", "other", "그 외 악기", "piano", " "]) == ["Piano", "piano"]


def test_tempo_direction_with_a_reference_track():
    """기준 곡의 조건 BPM(요청 중간값) 보다 빠른 곡만. 요청 범위가 없는 곡은 측정값으로 본다."""
    tracks, sections, inst = _catalog()
    tracks[1] = {**tracks[1], "bpm_requested_min": None, "bpm_requested_max": None}  # 측정 85
    tracks[4] = {**tracks[4], "bpm_requested_min": None, "bpm_requested_max": None}  # 측정 130
    facts = _facts(tracks, sections, inst)
    up = _apply(_filters("tempo_direction", "faster"), facts, track="T000000")      # 기준 100
    assert {_track_no(u) for u in up.kept} == {4}
    down = _apply(_filters("tempo_direction", "slow"), facts, track="T000000")
    assert {_track_no(u) for u in down.kept} == {1}


def test_activity_and_goal_are_reported_not_applied():
    out = _apply({"filters": {"activity": ["study"]}, "exclude": {"goal": ["sleep"]}})
    assert len(out.kept) == FULL
    assert out.ignored == ["activity(카탈로그 필드 없음)", "goal(카탈로그 필드 없음)"]


def test_facts_are_built_once_per_snapshot():
    tracks, sections, inst = _catalog()
    body = {"tracks": {c: [t[c] for t in tracks] for c in catalog_body.TRACK_COLS},
            "sections": {c: [s[c] for s in sections] for c in catalog_body.SECTION_COLS}}
    snap, instruments, _ = pool_snapshot.static(body, inst)
    ps = pool_snapshot.PoolSnapshot(snap, instruments, pool_snapshot.SOURCE_STATIC, 1, 0.0)
    assert qc.facts_of(ps) is qc.facts_of(ps)
