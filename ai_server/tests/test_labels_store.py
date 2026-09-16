"""라벨 저장소 계약. **ClickHouse 경로를 실제로 태운다.**

나머지 시험은 ClickHouse 주소가 없어 전부 파일 대체 경로로 돈다. 그러면 저장소를
바꾼 의미가 검증되지 않는다 — 파일 경로만 초록인 채로 ClickHouse 판이 깨져 있을 수
있다. 그래서 여기서는 HTTP 계층(`clickhouse.query`)만 가짜로 바꾸고 SQL 생성·응답
해석·대체 판단은 진짜 코드를 지나가게 한다.
"""
from __future__ import annotations

import pandas as pd
import pytest


@pytest.fixture
def store(monkeypatch):
    """ClickHouse 가 설정된 것처럼 만들고, 오간 SQL 을 모은다."""
    from ai_server.app import labels_store

    monkeypatch.setenv("MUPID_CLICKHOUSE_URL", "http://clickhouse:8123")
    monkeypatch.setenv("MUPID_CLICKHOUSE_USER", "default")
    seen: dict = {"sql": [], "body": []}
    replies: list[str] = []

    def fake_query(sql, body=None, timeout=10):
        seen["sql"].append(sql)
        seen["body"].append(body)
        return replies.pop(0) if replies else ""

    monkeypatch.setattr(labels_store.clickhouse, "query", fake_query)
    return labels_store, seen, replies


TSV = "user_ref\ttrack_id\tsection_key\tgrade\tw\tis_merged\tevidence\toccurred_at\n"


def test_reads_only_the_newest_run(store):
    """`labeler` 는 라벨을 통째로 다시 만든다.

    어제 있던 라벨이 오늘 없을 수 있으므로 판을 섞어 읽으면 **사라진 라벨이 남아
    조용히 오래된 취향으로 추천한다.** 조회는 언제나 최신 run_id 하나만 본다.
    """
    labels_store, seen, _ = store

    labels_store.user_labels("u1")

    sql = seen["sql"][0]
    assert "max(run_id)" in sql
    assert "run_id = (SELECT max(run_id)" in sql


def test_is_merged_survives_the_tsv_round_trip(store):
    """TSV 는 전부 문자열로 온다.

    되돌리지 않으면 `~lab.is_merged` 가 문자열 'false' 에 걸려 **전부 참**이 되고,
    재추출 전인 병합 라벨이 학습·DNA 에 섞인다. 오류는 나지 않고 추천만 나빠진다.
    """
    labels_store, _, replies = store
    replies.append(TSV + "u1\tT1\tT1#0\tL1\t1.0\tfalse\t{}\t2026-09-16T00:00:00Z\n"
                         "u1\tT2\tT2#1\tL1\t1.0\ttrue\t{}\t2026-09-16T00:00:00Z\n")

    frame = labels_store.user_labels("u1")

    assert frame.is_merged.tolist() == [False, True]
    assert frame.w.tolist() == [1.0, 1.0]
    assert (~frame.is_merged).sum() == 1


def test_empty_response_is_an_empty_frame_with_columns(store):
    """행이 0건이어도 호출부는 `.grade` 를 건드린다. 열이 없으면 AttributeError 다."""
    labels_store, _, _ = store

    frame = labels_store.user_labels("nobody")

    assert frame.empty
    assert "grade" in frame.columns


def test_user_ref_is_escaped(store):
    """사용자 식별자가 SQL 에 그대로 실린다. 따옴표가 들어오면 질의가 깨진다."""
    labels_store, seen, _ = store

    labels_store.user_labels("o'brien")

    assert "o\\'brien" in seen["sql"][0]


def test_other_positives_excludes_the_user_and_takes_only_l1(store):
    labels_store, seen, _ = store

    labels_store.other_user_positives("u1")

    sql = seen["sql"][0]
    assert "user_ref != 'u1'" in sql
    assert "grade = 'L1'" in sql


def test_write_stamps_one_run_id_on_every_row(store):
    """한 판은 하나의 run_id 여야 한다. 행마다 다르면 최신 판이 한 줄짜리가 된다."""
    labels_store, seen, _ = store
    frame = pd.DataFrame([
        {"label_id": "a", "user_ref": "u1", "track_id": "T1", "section_key": "T1#0",
         "grade": "L1", "w": 1.0, "is_merged": False},
        {"label_id": "b", "user_ref": "u1", "track_id": "T2", "section_key": "T2#1",
         "grade": "L2", "w": 0.4, "is_merged": False},
    ])

    count = labels_store.write(frame, "2026-09-16 03:00:00")

    assert count == 2
    body = seen["body"][0].decode()
    assert body.count('"run_id": "2026-09-16 03:00:00"') == 2


def test_write_does_not_send_nan(store):
    """NaN 은 JSON 에 그대로 실리지 않는다 — ClickHouse 가 파싱에서 거부한다."""
    labels_store, seen, _ = store
    frame = pd.DataFrame([{"label_id": "a", "user_ref": "u1", "section_key": "T1#0",
                           "grade": "L1", "coverage": float("nan"),
                           "evidence": None}])

    labels_store.write(frame, "2026-09-16 03:00:00")

    body = seen["body"][0].decode()
    assert "NaN" not in body
    assert '"coverage": 0' in body
    assert '"evidence": ""' in body


def test_empty_frame_writes_nothing(store):
    labels_store, seen, _ = store

    assert labels_store.write(pd.DataFrame(), "2026-09-16 03:00:00") == 0
    assert seen["sql"] == []


# ------------------------------------------------------- recommender 쪽 대체 판단
def test_recommender_falls_back_to_the_file_without_clickhouse(monkeypatch, tmp_path):
    """주소가 없으면 종전의 parquet 경로로 간다. 개발·오프라인이 그 경로로 돈다."""
    monkeypatch.delenv("MUPID_CLICKHOUSE_URL", raising=False)
    from ai_server.app import recommender

    labels = tmp_path / "labels_service.parquet"
    pd.DataFrame([{"user_ref": "u1", "section_key": "T1#0", "grade": "L1",
                   "is_merged": False, "track_id": "T1", "w": 1.0}]).to_parquet(labels)
    monkeypatch.setattr(recommender, "LABELS", labels)

    assert recommender.labels_available() is True
    assert len(recommender.labels_for("u1")) == 1
    assert recommender.labels_for("u2").empty


def test_recommender_does_not_die_when_the_store_is_down(monkeypatch):
    """ClickHouse 가 죽어도 추천은 나간다 — 개인화만 빠지고 로그에 남는다.

    여기서 예외가 새어 나가면 저장소 장애가 곧 추천 장애가 된다. 품질 저하로 끝날
    일을 전면 장애로 키우지 않는다.
    """
    monkeypatch.setenv("MUPID_CLICKHOUSE_URL", "http://clickhouse:8123")
    monkeypatch.setenv("MUPID_CLICKHOUSE_USER", "default")
    from ai_server.app import labels_store, recommender

    def boom(*a, **k):
        raise labels_store.clickhouse.ClickHouseError("down")

    monkeypatch.setattr(labels_store.clickhouse, "query", boom)

    assert recommender.labels_available() is False
    assert recommender.labels_for("u1").empty
    assert recommender.other_positives("u1").empty


# 아래 열들은 `recommender` 가 라벨 표에서 **이름으로** 꺼내 쓴다. 파일 경로는 라벨러가
# 내보낸 22개 열을 전부 들고 있어 빠져도 티가 안 나지만, ClickHouse 경로는 여기 적힌
# 것만 가져온다 — 목록이 어긋나면 그쪽에서만 AttributeError 가 난다.
#
#   section_key  다섯 지점 전부            grade       등급 거르기
#   track_id     화면·BPM 선호             is_merged   병합 라벨 제외 (~lab.is_merged)
#   evidence     화면의 근거 표시          occurred_at 감쇠 가중·최근순 정렬
#   w            (labeler 가 준 가중치)    user_ref    질의 조건
REQUIRED_BY_RECOMMENDER = {
    "user_ref", "track_id", "section_key", "grade", "is_merged", "evidence",
    "occurred_at", "w",
}


def test_read_columns_cover_everything_the_recommender_touches():
    from ai_server.app import labels_store

    missing = REQUIRED_BY_RECOMMENDER - set(labels_store.READ_COLUMNS)
    assert not missing, f"ClickHouse 경로에서만 빠지는 열: {sorted(missing)}"


def test_labeler_emits_every_column_the_table_stores():
    """`labels_store.COLUMNS` 는 라벨러 출력과 같아야 한다.

    라벨러가 열을 하나 추가하면 표에는 들어가지 않고, 그 사실이 배치 로그에도
    드러나지 않는다 — 나중에 그 열을 읽는 코드가 생겼을 때 비로소 터진다.
    """
    import re
    from pathlib import Path

    from ai_server.app import labels_store

    src = (Path(labels_store.__file__).parent / "labeler.py").read_text(encoding="utf-8")
    block = re.search(r"keep = \[(.*?)\]", src, re.S).group(1)
    keep = re.findall(r'"([^"]+)"', block)

    assert keep == labels_store.COLUMNS


def test_ddl_declares_exactly_the_columns_we_insert():
    """DDL·`COLUMNS`·라벨러 출력이 한 벌이어야 한다.

    셋이 어긋나면 배치는 성공했다고 찍고 ClickHouse 가 조용히 해당 열만 버린다 —
    읽는 쪽에서 빈 값으로 나타나 원인이 적재라는 것이 드러나지 않는다.
    """
    import re
    from pathlib import Path

    from ai_server.app import labels_store

    sql = (Path(labels_store.__file__).parents[1] / "sql" / "section_labels.sql") \
        .read_text(encoding="utf-8")
    body = sql.split("(", 1)[1].rsplit(")", 1)[0]
    declared = [m.group(1) for line in body.splitlines()
                if (m := re.match(r"\s{4}(\w+)\s{2,}", line))]

    assert declared == ["run_id"] + labels_store.COLUMNS
