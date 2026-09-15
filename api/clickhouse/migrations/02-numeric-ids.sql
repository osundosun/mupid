-- 숫자 ID 계약 정렬 — music_id / music_section_id 를 BIGINT 계열로 맞춘다.
--
-- 배경
--   확정 규칙은 곡 `music_id BIGINT`, 구간 `music_section_id BIGINT` 다.
--   현재 스키마는 `track_id UInt32`(상한 4,294,967,295)와 `section_uid String` 을 쓴다.
--   MySQL BIGINT 가 UInt32 를 넘으면 조용히 잘리고, 구간은 문자열이라 조인 자체가 안 된다.
--
-- 이 파일은 **실행되지 않는다.** 01-schema.sql 과 달리 init 디렉터리 밖에 두었고,
-- 운영 데이터에 직접 적용하지 않는다. 적용 시점과 절차는 팀이 정한다.
--
-- 적용 순서 (제안)
--   1) 아래 _v2 테이블을 만든다 (기존 테이블 그대로 둔 채).
--   2) AI 서비스가 새 테이블에 쓰기 시작한다.
--   3) 과거 데이터를 옮긴다 (§3, section_uid → music_section_id 매핑 필요).
--   4) 읽는 쪽을 전부 옮긴 뒤 구 테이블을 정리한다.
--
-- 되돌리기: _v2 테이블만 DROP 하면 된다. 기존 테이블을 건드리지 않으므로 원복이 간단하다.

-- ---------------------------------------------------------------- 1. 구간 사전
CREATE TABLE IF NOT EXISTS track_sections_v2_local ON CLUSTER mupid_all
(
    music_id          Int64,                       -- 이전 track_id UInt32
    music_section_id  Int64,                       -- 이전 section_uid String
    analysis_version  LowCardinality(String),
    section_index     UInt8,
    source_section_uid String,                     -- 호환용. 신규 조인에는 쓰지 않는다
    fn                LowCardinality(String),
    start_time_ms     UInt32,                      -- 이전 start_ms
    end_time_ms       UInt32,                      -- 이전 end_ms
    peak_ms           UInt32,
    created_at        DateTime DEFAULT now()
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/track_sections_v2', '{replica}', created_at)
ORDER BY (analysis_version, music_id, section_index);

CREATE TABLE IF NOT EXISTS track_sections_v2 ON CLUSTER mupid_all
AS track_sections_v2_local
ENGINE = Distributed(mupid_all, currentDatabase(), track_sections_v2_local);

-- ---------------------------------------------------------------- 2. 청취 이벤트
-- listen_events 는 행이 가장 많다. 컬럼 타입을 바꾸는 ALTER 는 전체 재기록을 부르므로
-- 새 테이블로 받고 과거는 그대로 두는 편이 안전하다.
CREATE TABLE IF NOT EXISTS listen_events_v2_local ON CLUSTER mupid_all
(
    user_id        Int64,
    music_id       Int64,
    music_section_id Nullable(Int64),
    event_id       String,
    event_type     LowCardinality(String),
    position_ms    UInt32,
    occurred_at    DateTime64(3),
    created_at     DateTime DEFAULT now()
)
ENGINE = ReplicatedMergeTree('/clickhouse/tables/listen_events_v2', '{replica}')
ORDER BY (user_id, occurred_at, event_id);

-- ---------------------------------------------------------------- 3. 과거 데이터 이관
-- section_uid ↔ music_section_id 매핑이 있어야 옮길 수 있다. 매핑 없이 실행하지 말 것.
-- AI 쪽 `data/proc/mupid/id_map.parquet` 이 그 매핑의 근거다.
--
-- INSERT INTO track_sections_v2_local
-- SELECT
--     toInt64(t.track_id)      AS music_id,
--     m.music_section_id       AS music_section_id,
--     t.analysis_version, t.section_index,
--     t.section_uid            AS source_section_uid,
--     t.fn, t.start_ms, t.end_ms, t.peak_ms, t.created_at
-- FROM track_sections_local AS t
-- INNER JOIN id_map AS m ON m.source_section_uid = t.section_uid;
--
-- INNER JOIN 이라 매핑이 없는 행은 옮겨지지 않는다. 의도한 동작이다 —
-- 매핑이 없는 구간을 임의 번호로 채우면 엉뚱한 곡에 신호가 붙는다.
-- 이관 후 원본과 대상의 행 수를 반드시 비교할 것.

-- ---------------------------------------------------------------- 4. 확인 쿼리
-- SELECT count() FROM track_sections_local;
-- SELECT count() FROM track_sections_v2_local;
-- SELECT count() FROM track_sections_v2_local WHERE music_section_id = 0;   -- 0 이어야 한다
-- SELECT max(music_id) FROM track_sections_v2_local;                        -- UInt32 상한 초과 확인
