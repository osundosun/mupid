-- Mupid 분석 스키마. ON CLUSTER 로 한 번만 적용한다 (init/apply-ddl.sh).
--
-- ID 타입 규칙 — MySQL 이 기준이다.
--   JPA 엔티티의 PK 는 전부 `Integer`(INT AUTO_INCREMENT) 다. ClickHouse 는 여기에
--   맞춰 UInt32 를 쓴다 (user_id / track_id / playlist_id / comment_id).
--   tracks·playlists·comments 엔티티는 아직 MySQL 에 없다. 만들 때 `Integer` PK 를
--   쓰지 않으면 이 스키마와 어긋난다.
--
-- from_ms 는 두 가지 의미로 쓴다.
--   SEEK_BACK / SEEK_FORWARD  seek 하기 직전의 재생 위치
--   PLAY_PROGRESS             직전 하트비트의 재생 위치
--   즉 PLAY_PROGRESS 한 건은 "[from_ms, position_ms) 를 연속으로 들었다" 는 뜻이고,
--   mv_play_section_coverage 가 이 구간을 곡 구간 경계로 쪼개 커버리지로 만든다.
--
-- entry_path 는 PLAY_START 뿐 아니라 그 재생에 속한 모든 이벤트에 실린다. 스킵 해석과
-- 커버리지가 진입 경로를 필요로 하는데, 이벤트마다 PLAY_START 로 되짚는 JOIN 을 두면
-- 뜨거운 경로가 느려지기 때문이다. LowCardinality 라 저장 비용은 사실상 없다.
--
-- 이벤트 22종과 필드 의미는 knowledge/domain/listening-events.md 에 있다.
-- 스키마를 고치면 verify.sh 를 직접 돌린다. CI 는 이 영역을 검증하지 않는다.
CREATE TABLE IF NOT EXISTS listen_events_local ON CLUSTER mupid
(
    event_id      UUID,
    event_type    LowCardinality(String),
    user_id       UInt32,
    track_id      UInt32,
    occurred_at   DateTime64(3, 'Asia/Seoul'),
    ingested_at   DateTime64(3, 'Asia/Seoul') DEFAULT now64(3),
    play_id       UUID,
    entry_path    LowCardinality(String),
    rec_id        String,
    rec_type      LowCardinality(String),
    rec_rank      UInt8,
    position_ms   UInt32,
    from_ms       UInt32,
    start_ms      UInt32,
    end_ms        UInt32,
    target        LowCardinality(String),
    skip_trigger  LowCardinality(String),
    reason        LowCardinality(String),
    playlist_id   UInt32,
    comment_id    UInt32,
    content       String,
    search_query  String,
    ext           Map(String, String)
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/listen_events', '{replica}', ingested_at)
PARTITION BY toYYYYMM(occurred_at)
ORDER BY (user_id, occurred_at, event_id)
TTL toDateTime(occurred_at) + INTERVAL 18 MONTH;


CREATE TABLE IF NOT EXISTS listen_events ON CLUSTER mupid
AS listen_events_local
ENGINE = Distributed(mupid, currentDatabase(), listen_events_local, cityHash64(user_id));


CREATE TABLE IF NOT EXISTS track_sections_local ON CLUSTER mupid_all
(
    track_id          UInt32,
    analysis_version  LowCardinality(String),
    section_index     UInt8,
    section_uid       String,
    fn                LowCardinality(String),
    start_ms          UInt32,
    end_ms            UInt32,
    peak_ms           UInt32,
    created_at        DateTime DEFAULT now()
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/track_sections', '{replica}', created_at)
ORDER BY (analysis_version, track_id, section_index);


CREATE TABLE IF NOT EXISTS track_sections ON CLUSTER mupid_all
AS track_sections_local
ENGINE = Distributed(mupid_all, currentDatabase(), track_sections_local);


CREATE OR REPLACE VIEW v_track_sections_current ON CLUSTER mupid_all AS
SELECT track_id, start_ms, end_ms, section_uid, fn, peak_ms
FROM track_sections_local
WHERE analysis_version = 'v1';


CREATE DICTIONARY IF NOT EXISTS dict_track_sections ON CLUSTER mupid_all
(
    track_id    UInt32,
    start_ms    UInt32,
    end_ms      UInt32,
    section_uid String,
    fn          String,
    peak_ms     UInt32
)
PRIMARY KEY track_id
SOURCE(CLICKHOUSE(TABLE 'v_track_sections_current'))
LAYOUT(RANGE_HASHED())
RANGE(MIN start_ms MAX end_ms)
LIFETIME(MIN 300 MAX 600);


CREATE TABLE IF NOT EXISTS user_section_signals_local ON CLUSTER mupid
(
    user_id      UInt32,
    section_uid  String,
    track_id     UInt32,
    signal       LowCardinality(String),
    entry_path   LowCardinality(String),
    occurred_at  DateTime64(3, 'Asia/Seoul'),
    play_id      UUID,
    weight       Float32
)
ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/user_section_signals', '{replica}')
PARTITION BY toYYYYMM(occurred_at)
ORDER BY (user_id, section_uid, occurred_at)
TTL toDateTime(occurred_at) + INTERVAL 18 MONTH;


CREATE TABLE IF NOT EXISTS user_section_signals ON CLUSTER mupid
AS user_section_signals_local
ENGINE = Distributed(mupid, currentDatabase(), user_section_signals_local, cityHash64(user_id));


CREATE MATERIALIZED VIEW IF NOT EXISTS mv_user_section_signals ON CLUSTER mupid
TO user_section_signals_local AS
SELECT
    user_id,
    dictGet('dict_track_sections', 'section_uid', toUInt64(track_id), position_ms) AS section_uid,
    track_id,
    event_type AS signal,
    entry_path,
    occurred_at,
    play_id,
    multiIf(
        event_type = 'SECTION_MARK',     1.5,
        event_type = 'LIKE',             1.0,
        event_type = 'PLAYLIST_ADD',     0.8,
        event_type = 'COMMENT_ADD',      0.7,
        event_type = 'SEEK_BACK',        0.6,
        event_type = 'DISLIKE',         -1.0,
        event_type = 'PLAYLIST_REMOVE', -0.8,
        event_type = 'UNLIKE',          -0.5,
        event_type = 'SECTION_UNMARK',  -1.5,
        0.0
    ) AS weight
FROM listen_events_local
WHERE event_type IN ('SECTION_MARK','SECTION_UNMARK','LIKE','UNLIKE','DISLIKE',
                     'PLAYLIST_ADD','PLAYLIST_REMOVE','COMMENT_ADD','SEEK_BACK')
  AND track_id > 0
  AND position_ms > 0
  AND dictGet('dict_track_sections', 'section_uid', toUInt64(track_id), position_ms) != '';


-- 재생 1회(play_id) 동안 각 구간을 얼마나 들었는가. mv_play_section_coverage 가
-- PLAY_PROGRESS 하트비트에서 만든다.
--
-- SummingMergeTree 라 하트비트마다 같은 키에 부분합 행이 쌓이고, 병합될 때
-- listened_ms 만 합쳐진다. **병합 시점을 보장할 수 없으므로 읽을 때 반드시
-- GROUP BY 로 합산한다** (v_track_skips 의 r 서브쿼리가 그 예다). 합산 대상을
-- 괄호로 명시했으므로 section_ms 는 더해지지 않는다 — 키마다 상수라 안전하다.
--
-- started_at 은 파티션·TTL 용이고, 병합 뒤에는 그 재생의 하트비트 중 하나가 남는다.
-- 달을 걸친 재생은 파티션이 갈려 부분합이 남지만, 읽을 때 GROUP BY 하므로 결과는
-- 정확하다.
--
-- reached 컬럼은 두지 않는다. 행이 존재한다는 것 자체가 "재생 헤드가 이 구간에
-- 닿았다" 는 뜻이고, SummingMergeTree 에서 플래그 컬럼은 의미를 잃는다.
CREATE TABLE IF NOT EXISTS play_section_coverage_local ON CLUSTER mupid
(
    user_id      UInt32,
    play_id      UUID,
    track_id     UInt32,
    section_uid  String,
    entry_path   LowCardinality(String),
    section_ms   UInt32,
    listened_ms  UInt64,
    started_at   DateTime64(3, 'Asia/Seoul')
)
ENGINE = ReplicatedSummingMergeTree('/clickhouse/tables/{shard}/play_section_coverage', '{replica}', (listened_ms))
PARTITION BY toYYYYMM(started_at)
ORDER BY (user_id, play_id, section_uid)
TTL toDateTime(started_at) + INTERVAL 18 MONTH;


CREATE TABLE IF NOT EXISTS play_section_coverage ON CLUSTER mupid
AS play_section_coverage_local
ENGINE = Distributed(mupid, currentDatabase(), play_section_coverage_local, cityHash64(user_id));


-- PLAY_PROGRESS 하트비트가 커버하는 오디오 구간 [from_ms, position_ms) 를 곡 구간
-- 경계로 쪼갠다. 하트비트 하나가 두 구간에 걸치면 두 행이 나온다.
--
-- dictGet 이 아니라 JOIN 을 쓰는 이유: RANGE_HASHED 딕셔너리는 "한 지점이 속한 구간"
-- 만 돌려주므로 구간을 걸친 재생을 나눌 수 없다. 끝점 기준으로 몰아주면 경계 뒤 구간이
-- 과대 집계되는데, 하필 그 편향이 INTRO 노출 보정과 정면으로 부딪힌다.
-- track_sections 는 mupid_all 로 전 노드에 복제돼 있어 이 JOIN 은 노드 안에서 끝난다.
--
-- WHERE 의 세 조건이 겹침 구간을 보장하므로 아래 뺄셈은 UInt32 언더플로가 나지 않는다.
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_play_section_coverage ON CLUSTER mupid
TO play_section_coverage_local AS
SELECT
    e.user_id                   AS user_id,
    e.play_id                   AS play_id,
    e.track_id                  AS track_id,
    sec.section_uid             AS section_uid,
    e.entry_path                AS entry_path,
    (sec.end_ms - sec.start_ms) AS section_ms,
    toUInt64(least(e.position_ms, sec.end_ms) - greatest(e.from_ms, sec.start_ms)) AS listened_ms,
    e.occurred_at               AS started_at
FROM listen_events_local AS e
INNER JOIN v_track_sections_current AS sec ON sec.track_id = e.track_id
WHERE e.event_type = 'PLAY_PROGRESS'
  AND e.track_id > 0
  AND e.position_ms > e.from_ms
  AND sec.start_ms < e.position_ms
  AND sec.end_ms > e.from_ms;


CREATE TABLE IF NOT EXISTS rec_impressions_local ON CLUSTER mupid
(
    rec_id             String,
    user_id            UInt32,
    rec_type           LowCardinality(String),
    served_at          DateTime64(3, 'Asia/Seoul'),
    section_uids       Array(String),
    scores             Array(Float32),
    explore_flags      Array(UInt8),
    seed_section_uid   String,
    pref_section_uids  Array(String),
    signal_weights     Map(String, Float32),
    model_version      LowCardinality(String),
    embedding_version  LowCardinality(String),
    analysis_version   LowCardinality(String),
    ext                Map(String, String)
)
ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/rec_impressions', '{replica}')
PARTITION BY toYYYYMM(served_at)
ORDER BY (user_id, served_at, rec_id)
TTL toDateTime(served_at) + INTERVAL 18 MONTH;


CREATE TABLE IF NOT EXISTS rec_impressions ON CLUSTER mupid
AS rec_impressions_local
ENGINE = Distributed(mupid, currentDatabase(), rec_impressions_local, cityHash64(user_id));


-- 스킵을 "부정" 하나로 뭉뚱그리지 않고 얼마나 듣고 넘겼는지로 나눈다.
--
-- MATERIALIZED VIEW 가 아니라 일반 VIEW 인 것이 핵심이다. 분류(skip_type)와 가중치는
-- 저장하지 않고 쿼리할 때마다 계산하므로, 기준(5초 / 20% / 80%)을 고쳐 이 DDL 을 다시
-- 적용하면 과거 이벤트까지 새 기준으로 다시 해석된다. 수집 쪽은 skip_trigger 와
-- position_ms 만 보낸다 — 프론트는 "이 구간을 전에 들었는지" 를 알 수 없다.
-- 배경: knowledge/decision/0015.
--
-- SKIP_AFTER_HOOK 판정은 play_section_coverage 를 본다. 그 테이블은 SummingMergeTree
-- 라 병합 전 부분합이 섞여 있으므로, 재생 단위로 **먼저 GROUP BY 해서 합친 뒤에**
-- "절반 넘게 들었나" 를 판정해야 한다. 부분합에 바로 조건을 걸면 60% 들은 재생이
-- 30% + 30% 두 행으로 남아 있는 동안 조건을 통과하지 못한다.
--
-- entry_path 는 이벤트에 직접 실려 오므로 PLAY_START 로 되짚지 않는다.
CREATE OR REPLACE VIEW v_track_skips ON CLUSTER mupid AS
SELECT
    s.event_id,
    s.user_id,
    s.track_id,
    s.play_id,
    s.occurred_at,
    s.position_ms,
    s.skip_trigger,
    sec.section_uid,
    s.entry_path AS entry_path,
    (s.position_ms - sec.start_ms) AS into_ms,
    (sec.end_ms - sec.start_ms) AS sec_len,
    multiIf(
        r.n > 0,                                     'SKIP_AFTER_HOOK',
        into_ms <= 5000 OR into_ms <= sec_len * 0.2, 'EARLY_SKIP',
        into_ms >= sec_len * 0.8,                    'LATE_SKIP',
        'MID_SKIP'
    ) AS skip_type,
    multiIf(
        skip_type = 'SKIP_AFTER_HOOK', 0.0,
        skip_type = 'EARLY_SKIP' AND entry_path = 'RECOMMEND',
            if(s.skip_trigger = 'NEXT_BUTTON', -1.0, -0.4),
        skip_type = 'LATE_SKIP', 0.3,
        0.0
    ) AS weight
FROM listen_events AS s
INNER JOIN v_track_sections_current AS sec
        ON sec.track_id = s.track_id
       AND s.position_ms >= sec.start_ms
       AND s.position_ms < sec.end_ms
LEFT JOIN
(
    SELECT user_id, section_uid, count() AS n
    FROM
    (
        SELECT
            user_id,
            play_id,
            section_uid,
            sum(listened_ms) AS listened_ms,
            any(section_ms)  AS section_ms
        FROM play_section_coverage
        GROUP BY user_id, play_id, section_uid
    )
    WHERE listened_ms > section_ms * 0.5
    GROUP BY user_id, section_uid
) AS r ON r.user_id = s.user_id AND r.section_uid = sec.section_uid
WHERE s.event_type = 'TRACK_SKIP'
  AND s.track_id > 0;


CREATE TABLE IF NOT EXISTS kafka_listen_events ON CLUSTER mupid
(
    event_id     String,
    event_type   String,
    user_id      UInt32,
    track_id     UInt32,
    occurred_at  String,
    play_id      String,
    entry_path   String,
    rec_id       String,
    rec_type     String,
    rec_rank     UInt8,
    position_ms  UInt32,
    from_ms      UInt32,
    start_ms     UInt32,
    end_ms       UInt32,
    target       String,
    skip_trigger String,
    reason       String,
    playlist_id  UInt32,
    comment_id   UInt32,
    content      String,
    search_query String,
    ext          Map(String, String)
)
ENGINE = Kafka
SETTINGS
    kafka_broker_list = 'kafka1:9092,kafka2:9092',
    kafka_topic_list = 'mupid.listen.events',
    kafka_group_name = 'ch_listen_events',
    kafka_format = 'JSONEachRow',
    kafka_num_consumers = 3,
    kafka_skip_broken_messages = 100,
    input_format_skip_unknown_fields = 1;


CREATE MATERIALIZED VIEW IF NOT EXISTS mv_kafka_to_events ON CLUSTER mupid
TO listen_events_local AS
SELECT
    toUUIDOrZero(event_id) AS event_id,
    event_type,
    user_id,
    track_id,
    parseDateTime64BestEffort(occurred_at, 3, 'Asia/Seoul') AS occurred_at,
    toUUIDOrZero(play_id) AS play_id,
    entry_path,
    rec_id,
    rec_type,
    rec_rank,
    position_ms,
    from_ms,
    start_ms,
    end_ms,
    target,
    skip_trigger,
    reason,
    playlist_id,
    comment_id,
    content,
    search_query,
    ext
FROM kafka_listen_events;
