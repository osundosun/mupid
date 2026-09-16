-- 선호 라벨 표. `app/labels_store.py` 가 읽고, 야간 배치(`tools/run_labeler.py`)가 쓴다.
--
-- **이 파일은 사람이 실행한다.** 앱은 표를 만들지도 바꾸지도 않는다(decision/0011).
-- 실행 전에 `ai_server/docs/SEED-RUNBOOK.md` 의 차례를 본다.
--
--   clickhouse-client --host <host> --user <user> --password < ai_server/sql/section_labels.sql
--
-- 왜 ClickHouse 인가
--   라벨은 `listen_events` 에서 뽑은 집계다. 원본과 같은 곳에 두면 배치가 한 곳만
--   보면 되고, 조회 모양도 같다 — 사용자 한 명 범위를 훑는다. 파일로 두면 추천 파드가
--   그 파일을 들고 있어야 해서 파드를 늘릴 수 없다.
--
-- 왜 run_id 인가
--   `labeler` 는 라벨 집합을 통째로 다시 만든다. 어제 있던 라벨이 오늘 없을 수 있다.
--   행을 갱신해 맞추려 하면 사라진 라벨이 남아 **조용히 오래된 취향으로 추천한다.**
--   그래서 판마다 run_id 를 붙여 통째로 넣고, 읽을 때 max(run_id) 만 본다.

CREATE TABLE IF NOT EXISTS section_labels
(
    -- 판 구분. 배치가 시작 시각으로 정한다. 읽기는 항상 max(run_id) 만 본다.
    run_id                DateTime,

    label_id              String,
    user_ref              String,
    track_id              String,
    section_key           String,

    -- 인접 두 구간에 걸친 MOMENT_LIKE 를 합친 라벨. 학습·DNA 에서 제외한다
    -- (`~lab.is_merged`) — 아직 재추출 전이라 임베딩 행이 없다.
    is_merged             UInt8,
    member_idx            Int32,

    -- L1 > neg_strong > L2 > neg_weak. 등급 정의는 labeler 의 docstring 에 있다.
    grade                 LowCardinality(String),
    y                     Int8,
    w                     Float32,

    -- 왜 이 등급인지의 근거 JSON. 화면의 "선호 구간" 설명이 이걸 읽는다.
    evidence              String,

    coverage              Float32,
    residual              Float32,
    reached               UInt8,
    listen_ordinal        Int32,
    section_detector      LowCardinality(String),
    delay_correction_sec  Float32,
    labeler_version       LowCardinality(String),
    pool                  LowCardinality(String),
    emb_row               Int64,
    needs_reextract       UInt8,
    session_id            String,

    -- 라벨의 근거가 된 행동의 시각. 감쇠 가중(`decay_weights`)이 읽는다.
    occurred_at           String
)
ENGINE = MergeTree
-- 조회는 언제나 (최신 판, 사용자 한 명) 이다. 그 순서로 정렬해야 한 사람 범위가
-- 한 덩어리로 읽힌다.
ORDER BY (run_id, user_ref, section_key)
-- 어제 판을 남긴다. 라벨은 다시 만들 수 있지만 그때의 행동 로그 창은 이미 지나갔다.
TTL run_id + INTERVAL 7 DAY
SETTINGS index_granularity = 8192;
