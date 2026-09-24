"""곡·순간 메타데이터를 Spring 내부 API 에서 읽는 경로.

여기서 지키는 것은 **실패했을 때의 모양**이다. 카탈로그가 비거나 낡으면 추천은
오류를 내지 않는다 — "조건에 맞는 곡이 없다" 로 나오고, 응답만 봐서는 정상과
구분되지 않는다. 그래서 각 실패가 어떤 모양으로 드러나야 하는지를 못박는다.

  · 최초 적재 실패    → **실패다.** 카탈로그 없이 뜨면 안 된다
  · 갱신 실패        → 실패가 아니다. 들고 있던 카탈로그를 그대로 쓴다
  · 빈 카탈로그      → 실패다. 갈아 끼우면 모든 조건이 아무 곡도 못 고른다
  · 옛 판 + 새 코드  → 실패다. 장르·무드 축이 **오류 없이** 통째로 빈다
  · 판과 카탈로그 불일치 → 실패다. 서로 다른 세계의 두 벌이다

망은 타지 않는다. Spring 호출부만 바꿔 끼우고 담기는 길은 운영과 같게 둔다.
"""
from __future__ import annotations

import json

import pytest

from ai_server.app import catalog_api, index_contract, spring_api


def _track(tid, music_id, *, sections=1, genres=("indie_rock",), moods=("calm",),
           instruments=("piano",), has_vocal=True, gender="F"):
    return {
        "music_id": music_id,
        "title": f"곡 {tid}",
        "has_vocal": has_vocal,
        "vocal_gender": gender,
        "genres": list(genres),
        "moods": list(moods),
        "sections": [{
            "external_id": f"{tid}#{s}",
            "music_section_analysis_id": 7000 + music_id * 10 + s,
            "section_label": "CHORUS",
            "index": s,
            "start_time_ms": s * 10_000,
            "end_time_ms": s * 10_000 + 9_000,
            "instruments": list(instruments),
            "moods": ["dreamy"],
        } for s in range(sections)],
    }


def _page(tracks, page, total_pages, size=200):
    return {"data": {"page": page, "size": size, "total_pages": total_pages,
                     "total_elements": len(tracks), "generated_at": "2026-09-22T00:00:00",
                     "tracks": tracks}}


@pytest.fixture
def api(monkeypatch):
    """Spring 호출부를 바꿔 끼운다. 응답 목록을 쥐여 주면 그 순서대로 답한다."""
    monkeypatch.setenv("MUPID_API_BASE_URL", "http://spring.test")
    monkeypatch.setenv("MUPID_API_REFRESH_TOKEN", "refresh-token")
    monkeypatch.delenv("MUPID_AI_CATALOG_TTL_SEC", raising=False)
    monkeypatch.setattr(spring_api, "access_token", lambda timeout=15: "access-token")
    state = {"pages": [], "calls": 0, "raise_on": None}

    def fake_request_json(url, **kw):
        state["calls"] += 1
        if state["raise_on"] is not None:
            raise state["raise_on"]
        index = int(url.split("page=")[1].split("&")[0])
        return state["pages"][index]

    monkeypatch.setattr(spring_api, "request_json", fake_request_json)
    catalog_api.reset()
    yield state
    catalog_api.reset()


# ------------------------------------------------------------------ 받아오기

def test_a_catalog_is_read_whole_across_pages(api):
    """`total_pages` 를 믿고 돈다. **개수로 끝을 판정하지 않는다.**

    중간 페이지가 비었을 때 거기서 멈추면 카탈로그가 조용히 잘린다.
    """
    api["pages"] = [
        _page([_track("T000001", 1)], 0, 3),
        _page([], 1, 3),                                   # 중간이 비어 있다
        _page([_track("T000003", 3)], 2, 3),
    ]
    snap = catalog_api.load()

    assert set(snap.tracks) == {"T000001", "T000003"}
    assert snap.music_id("T000003") == 3
    assert api["calls"] == 3, "빈 페이지에서 멈췄다 — 카탈로그가 잘린다"


def test_every_recommendation_axis_survives_the_trip(api):
    """장르·무드·보컬·악기·순간 무드가 추천이 읽는 모양으로 담긴다."""
    api["pages"] = [_page([_track("T000001", 1, sections=2)], 0, 1)]
    snap = catalog_api.load()

    track = snap.track_frame().loc["T000001"]
    assert track.genre_code == "indie_rock"
    assert track.mood_codes == "calm"
    # pandas 가 `np.bool_` 로 바꾼다. `_catalog_tags` 가 그 타입을 함께 받으므로
    # 여기서도 값으로만 본다.
    assert bool(track.has_vocal) is True and track.vocal_gender == "F"

    sections = snap.section_frame().set_index("moment_id")
    row = sections.loc["T000001#1"]
    assert row.section_label == "CHORUS"      # 소문자 변환은 recommender 경계에서 한다
    assert row.instruments == "piano"
    assert row.moment_moods == "dreamy"
    assert row.start_ms == 10_000 and row.end_ms == 19_000
    assert row.music_section_analysis_id == 7011


def test_a_track_whose_sections_disagree_is_skipped(api):
    """한 곡의 구간이 두 문자열 곡을 가리키면 섞어 담지 않는다."""
    bad = _track("T000001", 1, sections=2)
    bad["sections"][1]["external_id"] = "T000999#1"
    api["pages"] = [_page([bad, _track("T000002", 2)], 0, 1)]

    snap = catalog_api.load()
    assert set(snap.tracks) == {"T000002"}


# ------------------------------------------------------------------ 실패 모양

def test_first_load_failure_is_a_failure(api):
    """카탈로그 없이 뜨면 장르·무드·보컬 조건이 통째로 빈 채 추천이 나간다."""
    api["raise_on"] = RuntimeError("연결 거부")

    with pytest.raises(catalog_api.CatalogUnavailable):
        catalog_api.load()
    assert catalog_api.snapshot() is None
    assert catalog_api.status()["loaded"] is False


def test_an_empty_catalog_is_refused(api):
    """빈 카탈로그로 갈아 끼우면 모든 조건이 아무 곡도 못 고른다."""
    api["pages"] = [_page([], 0, 1)]

    with pytest.raises(catalog_api.CatalogUnavailable) as e:
        catalog_api.load()
    assert "0곡" in str(e.value)


def test_refresh_failure_keeps_the_previous_catalog(api):
    """조금 낡은 카탈로그가 **카탈로그가 없는 것보다 낫다.**"""
    api["pages"] = [_page([_track("T000001", 1)], 0, 1)]
    good = catalog_api.load()

    api["raise_on"] = RuntimeError("연결 거부")
    result = catalog_api.refresh()

    assert result["ok"] is False and result["kept_previous"] is True
    assert catalog_api.snapshot() is good, "갱신 실패인데 카탈로그가 바뀌었다"
    assert catalog_api.status()["last_error"]


def test_a_half_read_catalog_never_replaces_a_good_one(api):
    """중간에 끊긴 페이지까지만 담고 바꾸면 카탈로그가 조용히 잘린 채 굳는다."""
    api["pages"] = [_page([_track("T000001", 1), _track("T000002", 2)], 0, 1)]
    catalog_api.load()

    calls = {"n": 0}
    original = spring_api.request_json

    def flaky(url, **kw):
        calls["n"] += 1
        if calls["n"] > 1:
            raise RuntimeError("두 번째 쪽에서 끊겼다")
        return _page([_track("T000001", 1)], 0, 2)

    spring_api.request_json = flaky
    try:
        result = catalog_api.refresh()
    finally:
        spring_api.request_json = original

    assert result["ok"] is False
    assert set(catalog_api.snapshot().tracks) == {"T000001", "T000002"}


def test_refresh_without_configuration_does_not_wipe_the_catalog(api, monkeypatch):
    """설정이 빠져도 들고 있던 것을 버리지 않는다."""
    api["pages"] = [_page([_track("T000001", 1)], 0, 1)]
    catalog_api.load()

    monkeypatch.delenv("MUPID_API_REFRESH_TOKEN")
    result = catalog_api.refresh()

    assert result["ok"] is False and result["kept_previous"] is True
    assert catalog_api.snapshot() is not None


def test_status_never_carries_the_secret(api):
    """상태는 로그와 /health 로 나간다. 주소와 토큰 값을 싣지 않는다."""
    api["pages"] = [_page([_track("T000001", 1)], 0, 1)]
    catalog_api.load()

    raw = json.dumps(catalog_api.status(), ensure_ascii=False, default=str)
    assert "refresh-token" not in raw and "access-token" not in raw
    assert "spring.test" not in raw
    assert catalog_api.status()["base_url_set"] is True


# --------------------------------------------------- 판 계약 · 카탈로그 대조

def _plate(tmp_path, *, contract=index_contract.CONTRACT, meta=True, features=True):
    data = tmp_path / "data" / "proc" / "mupid"
    data.mkdir(parents=True, exist_ok=True)
    if features:
        import pandas as pd

        pd.DataFrame({"track_id": ["T000001"], "bpm": [120.0], "dur_sec": [200.0]}
                     ).to_parquet(data / "track_features.parquet", index=False)
    if meta:
        (data / "index_meta.json").write_text(json.dumps({
            "index_contract": contract, "catalog_source": index_contract.SOURCE,
            "built_at": "2026-09-22T00:00:00Z", "rows": 1, "tracks": 1}),
            encoding="utf-8")
    return data


def test_an_old_plate_is_refused(tmp_path, monkeypatch):
    """옛 판은 장르·무드를 flow_meta 안에 들고 있다. 새 코드는 그 파일을 읽지 않는다.

    섞이면 **오류 없이** 그 축이 통째로 빈다. 그래서 표식을 보고 거절한다.
    """
    monkeypatch.setenv("MUPID_AI_ARTIFACT_DIR", str(tmp_path))
    _plate(tmp_path, meta=False)

    report = index_contract.check_plate()
    assert report["ok"] is False
    assert any("index_meta.json" in e for e in report["errors"])


def test_a_plate_from_a_newer_contract_is_refused(tmp_path, monkeypatch):
    """판이 코드보다 앞서면 AI 이미지가 낡은 것이다. 조용히 읽지 않는다."""
    monkeypatch.setenv("MUPID_AI_ARTIFACT_DIR", str(tmp_path))
    _plate(tmp_path, contract=index_contract.CONTRACT + 1)

    assert index_contract.check_plate()["ok"] is False


def test_a_plate_without_track_features_is_refused(tmp_path, monkeypatch):
    """BPM·언어·스타일 축이 통째로 비는 판은 받지 않는다."""
    monkeypatch.setenv("MUPID_AI_ARTIFACT_DIR", str(tmp_path))
    _plate(tmp_path, features=False)

    assert index_contract.check_plate()["ok"] is False


def test_a_good_plate_passes_and_notices_leftovers(tmp_path, monkeypatch):
    """걷어낸 파일이 남아 있으면 기동은 시키되 **말은 한다.**"""
    monkeypatch.setenv("MUPID_AI_ARTIFACT_DIR", str(tmp_path))
    data = _plate(tmp_path)
    (data / "flow_meta.parquet").write_bytes(b"")

    report = index_contract.check_plate()
    assert report["ok"] is True
    assert any("flow_meta.parquet" in w for w in report["warnings"])


def test_an_index_from_another_world_is_caught(api):
    """판과 카탈로그가 겹치지 않으면 기동을 막는다."""
    api["pages"] = [_page([_track("T000001", 1)], 0, 1)]
    snap = catalog_api.load()

    match = index_contract.check_catalog(["T999999#0", "T999998#0"], snap)
    assert match["ok"] is False
    assert match["moments_matched"] == 0


def test_a_few_deleted_songs_are_normal(api, monkeypatch):
    """지워진 곡이 색인에 남는 것은 정상이다. 세어서 말하되 막지는 않는다."""
    monkeypatch.setenv("MUPID_AI_CATALOG_MIN_COVERAGE", "0.5")
    api["pages"] = [_page([_track("T000001", 1, sections=4)], 0, 1)]
    snap = catalog_api.load()

    match = index_contract.check_catalog(
        ["T000001#0", "T000001#1", "T000001#2", "T000999#0"], snap)
    assert match["ok"] is True
    assert match["moments_unmatched"] == 1
    assert match["sample_unmatched"] == ["T000999#0"]


def test_too_few_matches_is_treated_as_the_wrong_plate(api, monkeypatch):
    """합법적인 삭제가 한 번에 절반을 넘는 일은 없다."""
    monkeypatch.setenv("MUPID_AI_CATALOG_MIN_COVERAGE", "0.5")
    api["pages"] = [_page([_track("T000001", 1)], 0, 1)]
    snap = catalog_api.load()

    match = index_contract.check_catalog(
        ["T000001#0", "T999997#0", "T999998#0", "T999999#0"], snap)
    assert match["ok"] is False
    assert "0.5" in match["reason"] or "50%" in match["reason"]


def test_an_unread_catalog_is_not_reported_as_coverage(api):
    """아직 못 읽은 것과 겹치지 않는 것은 다르다. 비율을 지어내지 않는다."""
    match = index_contract.check_catalog(["T000001#0"], None)
    assert match["ok"] is False and match["coverage"] == 0.0
    assert "아직" in match["reason"]
