"""순간 저장 해제(`SECTION_UNMARK`)가 라벨러 입력에서 저장을 **취소**하는지 본다.

UNMARK 는 부정 선호가 아니다. 기존 저장을 되돌릴 뿐이라 positive 만 사라지고
negative 는 생기지 않아야 한다. 상태 키는 곡이 아니라 **순간**(`moment_id`)이다.

라벨러는 매 배치 누적 로그 전체를 정규화해 새 판으로 적재하므로, 정규화가 로그
전체를 접어 최종 상태를 내면 다음 배치에 들어온 UNMARK 도 지난 배치의 MARK 를 지운다.
"""
from __future__ import annotations

import json

import pytest

from ai_server.app import recommender as rec

U = "internal:1"
M1, M2 = "T000001#1", "T000001#2"          # 같은 곡의 서로 다른 순간
BOUNDS = {M1: (10.0, 20.0), M2: (20.0, 30.0)}
_seq = iter(range(10_000))


def _ev(et, mid=M1, ts="2026-09-20T10:00:00.000Z", user=U, eid=None, with_id=True):
    s, e = BOUNDS[mid]
    ev = {"event_id": eid or f"ch_{next(_seq):06d}", "user_ref": user,
          "track_id": mid.split("#")[0], "event_type": et,
          "start_sec": s, "end_sec": e, "occurred_at": ts, "source": "clickhouse"}
    if with_id:
        ev["moment_id"] = mid
    return ev


@pytest.fixture()
def log(tmp_path, monkeypatch):
    p = tmp_path / "logs" / "events_service.jsonl"
    p.parent.mkdir(parents=True)
    monkeypatch.setattr(rec.paths, "events_path", lambda: p)

    def write(*events):
        p.write_text("".join(json.dumps(e) + "\n" for e in events), encoding="utf-8")
        out = rec.normalized_events()
        return [json.loads(ln) for ln in out.read_text(encoding="utf-8").splitlines()]
    return write


def _likes(rows, mid=None):
    return [r for r in rows if r["event_type"] == "MOMENT_LIKE"
            and (mid is None or r.get("moment_id") == mid)]


def _negatives(rows):
    return [r for r in rows if r["event_type"] in ("DISLIKE", "SEEK", "SECTION_UNMARK")
            or r.get("reason") == "skip_next"]


def test_mark_alone_is_one_positive(log):
    assert len(_likes(log(_ev("SECTION_MARK")))) == 1


def test_mark_then_unmark_leaves_no_positive(log):
    rows = log(_ev("SECTION_MARK", ts="2026-09-20T10:00:00.000Z"),
               _ev("SECTION_UNMARK", ts="2026-09-20T10:05:00.000Z"))
    assert _likes(rows) == []
    assert _negatives(rows) == []


def test_mark_unmark_mark_is_one_positive(log):
    rows = log(_ev("SECTION_MARK", ts="2026-09-20T10:00:00.000Z"),
               _ev("SECTION_UNMARK", ts="2026-09-20T10:05:00.000Z"),
               _ev("SECTION_MARK", ts="2026-09-20T10:09:00.000Z", eid="ch_last"))
    likes = _likes(rows)
    assert [r["event_id"] for r in likes] == ["ch_last"]


def test_unmark_then_mark_is_one_positive(log):
    rows = log(_ev("SECTION_UNMARK", ts="2026-09-20T10:00:00.000Z"),
               _ev("SECTION_MARK", ts="2026-09-20T10:05:00.000Z"))
    assert len(_likes(rows)) == 1


def test_order_is_by_time_not_by_line(log):
    """로그 줄 순서가 아니라 `occurred_at` 순으로 접는다(배치 경계의 재조회 등)."""
    rows = log(_ev("SECTION_UNMARK", ts="2026-09-20T10:05:00.000Z"),
               _ev("SECTION_MARK", ts="2026-09-20T10:00:00.000Z"))
    assert _likes(rows) == []


def test_duplicate_mark_is_one_positive(log):
    """라벨러는 `moment_like` 를 더한다 — 중복 MARK 가 가중치를 키우면 안 된다."""
    a = _ev("SECTION_MARK", ts="2026-09-20T10:00:00.000Z", eid="ch_dup")
    rows = log(a, dict(a), _ev("SECTION_MARK", ts="2026-09-20T10:01:00.000Z"))
    assert len(_likes(rows)) == 1


def test_duplicate_event_id_does_not_replay_a_mark(log):
    """같은 MARK 줄이 UNMARK 뒤에 다시 쌓여도(재적재) 취소가 뒤집히지 않는다."""
    a = _ev("SECTION_MARK", ts="2026-09-20T10:00:00.000Z", eid="ch_a")
    rows = log(a, _ev("SECTION_UNMARK", ts="2026-09-20T10:05:00.000Z"), dict(a))
    assert _likes(rows) == []


def test_unmark_alone_makes_nothing(log):
    rows = log(_ev("SECTION_UNMARK"))
    assert rows == []


def test_moments_of_one_track_are_independent(log):
    rows = log(_ev("SECTION_MARK", M1, ts="2026-09-20T10:00:00.000Z"),
               _ev("SECTION_MARK", M2, ts="2026-09-20T10:01:00.000Z"),
               _ev("SECTION_UNMARK", M1, ts="2026-09-20T10:02:00.000Z"))
    assert _likes(rows, M1) == []
    assert len(_likes(rows, M2)) == 1


def test_other_users_are_independent(log):
    rows = log(_ev("SECTION_MARK", user="internal:1"),
               _ev("SECTION_MARK", user="internal:2"),
               _ev("SECTION_UNMARK", user="internal:2", ts="2026-09-20T10:09:00.000Z"))
    assert [r["user_ref"] for r in _likes(rows)] == ["internal:1"]


def test_same_instant_is_deterministic(log):
    """같은 시각이면 event_id 로 순서를 정한다. 줄 순서를 바꿔도 결과가 같아야 한다."""
    ts = "2026-09-20T10:00:00.000Z"
    mark = _ev("SECTION_MARK", ts=ts, eid="ch_b")
    unmark = _ev("SECTION_UNMARK", ts=ts, eid="ch_a")        # ch_a < ch_b → MARK 가 나중
    assert len(_likes(log(mark, unmark))) == 1
    assert len(_likes(log(unmark, mark))) == 1


def test_mixed_precision_timestamps_sort_by_time(log):
    """`...:05Z` 와 `...:05.500Z` 를 문자열로 비교하면 순서가 뒤집힌다."""
    rows = log(_ev("SECTION_MARK", ts="2026-09-20T10:00:05Z"),
               _ev("SECTION_UNMARK", ts="2026-09-20T10:00:05.500Z"))
    assert _likes(rows) == []


def test_old_lines_without_moment_id_fold_with_new_ones(log):
    """`moment_id` 를 싣기 전에 쌓인 MARK 도 같은 순간의 새 UNMARK 로 취소된다."""
    rows = log(_ev("SECTION_MARK", ts="2026-09-20T10:00:00.000Z", with_id=False),
               _ev("SECTION_UNMARK", ts="2026-09-21T10:00:00.000Z"))
    assert _likes(rows) == []


def test_other_toggles_are_unchanged(log):
    """LIKE·DISLIKE·PLAYLIST 는 예전처럼 최종 상태가 활성일 때만 넘어간다."""
    base = {"user_ref": U, "track_id": "T000009", "occurred_at": "2026-09-20T10:00:00Z"}
    rows = log({**base, "event_id": "e1", "event_type": "LIKE"},
               {**base, "event_id": "e2", "event_type": "UNLIKE"},
               {**base, "event_id": "e3", "event_type": "DISLIKE", "track_id": "T000008"},
               {**base, "event_id": "e4", "event_type": "UNDISLIKE", "track_id": "T000008"},
               {**base, "event_id": "e5", "event_type": "DISLIKE", "track_id": "T000007"},
               {**base, "event_id": "e6", "event_type": "PLAYLIST_ADD", "playlist_id": "p1"},
               {**base, "event_id": "e7", "event_type": "PLAYLIST_ADD", "playlist_id": "p2",
                "track_id": "T000006"},
               {**base, "event_id": "e8", "event_type": "PLAYLIST_REMOVE", "playlist_id": "p2",
                "track_id": "T000006"})
    got = {(r["event_type"], r["track_id"]) for r in rows}
    assert got == {("DISLIKE", "T000007"), ("PLAYLIST_ADD", "T000009")}


def test_pull_signals_carries_the_moment_id():
    """순간 상태 키는 ClickHouse `section_uid`(= external_id) 다. 경계로 추측하지 않는다."""
    from ai_server.tools import pull_signals as pl

    class _IM:
        def source_track_id(self, n):
            return "T000001"

    r = {"user_id": 1, "section_uid": M1, "track_id": 1, "signal": "SECTION_UNMARK",
         "entry_path": "", "ts": "2026-09-20 19:00:00.000", "play_id": "0-0"}
    ev = pl.to_event(r, BOUNDS, _IM())
    assert ev["moment_id"] == M1
    assert ev["event_type"] == "SECTION_UNMARK"
