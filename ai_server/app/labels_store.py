"""선호 라벨 저장소. **서빙이 읽고, 야간 배치가 쓴다.**

라벨은 `labeler` 가 행동 로그에서 뽑은 "이 사용자가 이 구간을 좋아한다" 는 판정이다.
지금까지는 `labels_service.parquet` 파일 한 장이었고, 그래서 추천 파드가 **파일을 들고
있어야만** 개인화가 됐다. 파드는 상태를 갖지 않아야 하고 여러 벌로 늘어날 수 있으므로,
읽는 쪽을 ClickHouse 로 옮긴다.

행동 이벤트가 이미 ClickHouse 에 있다(`app/clickhouse.py`). 라벨은 그 이벤트에서 나온
집계라 같은 곳에 두는 것이 자연스럽고, 조회 모양도 같다 — 사용자 한 명 범위를 훑는다.

    listen_events ──(야간 배치: labeler)──> section_labels ──> 추천 서빙

**표는 이 코드가 만들지 않는다.** DDL 은 `ai_server/sql/section_labels.sql` 에 있고 사람이
실행한다(decision/0011 의 원칙 — 앱이 스키마를 건드리지 않는다). 없는 표에 쓰면
ClickHouse 가 오류를 내고, 그 오류가 곧 "DDL 을 아직 안 돌렸다" 는 뜻이다.

전체 교체를 어떻게 하나
    `labeler` 는 라벨 집합을 **통째로 다시 만든다.** 어제 있던 라벨이 오늘 없어질 수
    있다(행동이 바뀌었으므로). 그래서 행을 갱신하지 않고 `run_id` 를 붙여 통째로 넣고,
    읽을 때 가장 최근 `run_id` 만 본다. 갱신·삭제로 맞추려 하면 사라진 라벨이 남아
    조용히 오래된 취향으로 추천한다.

    오래된 판은 TTL 로 사라진다(DDL 에 7일). 되돌릴 일이 있을 때 어제 판이 남아 있어야
    한다 — 라벨은 다시 만들 수는 있지만 그때의 행동 로그 창이 이미 지나갔다.

ClickHouse 가 없으면
    종전의 parquet 파일로 내려간다. 오프라인 테스트와 개발이 그 경로로 돈다. 이 판단은
    `clickhouse.configured()` 하나로만 한다 — 두 곳에서 따로 판단하면 한쪽만 바뀐 채
    "읽기는 파일, 쓰기는 ClickHouse" 같은 상태가 된다.
"""
from __future__ import annotations

import io
import json

import pandas as pd

from . import clickhouse
from .log import log

TABLE = "section_labels"

# `labeler` 가 내보내는 열. 순서가 곧 INSERT 의 열 순서다.
COLUMNS = [
    "label_id", "user_ref", "track_id", "section_key", "is_merged", "member_idx",
    "grade", "y", "w", "evidence", "coverage", "residual", "reached",
    "listen_ordinal", "section_detector", "delay_correction_sec",
    "labeler_version", "pool", "emb_row", "needs_reextract",
    "session_id", "occurred_at",
]

# 읽는 쪽이 실제로 쓰는 열. 전부 끌어오면 사용자 한 명에 수백 KB 가 오간다.
# `recommender` 의 다섯 지점을 훑어 나온 목록이다 — 여기 없는 열을 쓰기 시작하면
# KeyError 로 바로 드러난다(조용히 NaN 이 되지 않는다).
READ_COLUMNS = [
    "user_ref", "track_id", "section_key", "grade", "w", "is_merged",
    "evidence", "occurred_at",
]

_LATEST = f"(SELECT max(run_id) FROM {TABLE})"


def configured() -> bool:
    """ClickHouse 를 쓰는가. 아니면 호출부가 parquet 경로로 간다."""
    return clickhouse.configured()


def _escape(value: str) -> str:
    return str(value).replace("\\", "\\\\").replace("'", "\\'")


def _read(where: str, columns: list[str] | None = None) -> pd.DataFrame:
    cols = columns or READ_COLUMNS
    sql = (f"SELECT {', '.join(cols)} FROM {TABLE} "
           f"WHERE run_id = {_LATEST} AND {where} FORMAT TSVWithNames")
    text = clickhouse.query(sql)
    if not text.strip():
        return pd.DataFrame(columns=cols)
    frame = pd.read_csv(io.StringIO(text), sep="\t", dtype=str, keep_default_na=False)
    if frame.empty:
        return pd.DataFrame(columns=cols)
    # TSV 는 전부 문자열로 온다. 숫자·불리언을 되돌리지 않으면 `~lab.is_merged` 가
    # 문자열 'false' 에 걸려 **전부 참**이 된다 — 병합 라벨이 조용히 섞인다.
    if "w" in frame.columns:
        frame["w"] = pd.to_numeric(frame["w"], errors="coerce").fillna(0.0)
    if "is_merged" in frame.columns:
        frame["is_merged"] = frame["is_merged"].isin(("1", "true", "True"))
    return frame


def user_labels(user_ref: str) -> pd.DataFrame:
    """사용자 한 명의 라벨 전부."""
    return _read(f"user_ref = '{_escape(user_ref)}'")


def other_user_positives(user_ref: str) -> pd.DataFrame:
    """**남들의** L1 라벨. 질의 측 개인화 보정에 쓴다(`build_dna`).

    전체를 끌어오지 않는다 — 사용자가 늘면 이 한 줄이 가장 먼저 커진다.
    """
    return _read(f"user_ref != '{_escape(user_ref)}' AND grade = 'L1'",
                 columns=["user_ref", "section_key", "grade"])


def all_labels() -> pd.DataFrame:
    """전체. 배치(`learn`)만 쓴다 — 요청 경로에서 부르지 않는다."""
    return _read("1 = 1")


def users() -> list[str]:
    sql = (f"SELECT DISTINCT user_ref FROM {TABLE} "
           f"WHERE run_id = {_LATEST} ORDER BY user_ref FORMAT TSV")
    text = clickhouse.query(sql)
    return [line for line in text.splitlines() if line]


def has_any() -> bool:
    """라벨이 한 건이라도 있는가. 파일 경로의 `LABELS.exists()` 자리를 대신한다."""
    sql = f"SELECT count() FROM {TABLE} WHERE run_id = {_LATEST} FORMAT TSV"
    return int(clickhouse.query(sql).strip() or 0) > 0


def write(frame: pd.DataFrame, run_id: str) -> int:
    """라벨 한 판을 통째로 넣는다. 기존 판은 지우지 않는다 — TTL 이 걷어 간다.

    `run_id` 는 호출자가 정한다(배치 시각). 이 함수가 지어내면 재시도가 새 판을
    만들어, 반쯤 들어간 판이 최신으로 잡힌다.
    """
    if frame.empty:
        log("라벨이 0건이다 — 넣지 않는다")
        return 0
    rows = []
    for record in frame.to_dict("records"):
        row = {name: record.get(name) for name in COLUMNS}
        row["run_id"] = run_id
        # NaN 은 JSON 에 그대로 실리지 않는다. 열 의미를 지켜 0/빈 문자열로 내린다.
        for key, value in list(row.items()):
            if value is None or (isinstance(value, float) and value != value):
                row[key] = "" if key in _TEXT_COLUMNS else 0
        rows.append(json.dumps(row, ensure_ascii=False, default=str))
    body = ("\n".join(rows) + "\n").encode()
    clickhouse.query(f"INSERT INTO {TABLE} FORMAT JSONEachRow", body=body, timeout=60)
    log(f"라벨 {len(rows):,}건 적재 (run_id={run_id})")
    return len(rows)


_TEXT_COLUMNS = {
    "label_id", "user_ref", "track_id", "section_key", "grade", "evidence",
    "section_detector", "labeler_version", "pool", "session_id", "occurred_at",
}
