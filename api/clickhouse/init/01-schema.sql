CREATE TABLE IF NOT EXISTS listen_events_local ON CLUSTER mupid
(
    event_id      UUID,
    event_type    LowCardinality(String),
    user_id       UInt64,
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
    playlist_id   UInt64,
    comment_id    UInt64,
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
    user_id      UInt64,
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


CREATE TABLE IF NOT EXISTS play_section_coverage_local ON CLUSTER mupid
(
    user_id      UInt64,
    play_id      UUID,
    track_id     UInt32,
    section_uid  String,
    entry_path   LowCardinality(String),
    listened_ms  UInt32,
    section_ms   UInt32,
    reached      UInt8,
    started_at   DateTime64(3, 'Asia/Seoul')
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/play_section_coverage', '{replica}')
PARTITION BY toYYYYMM(started_at)
ORDER BY (user_id, play_id, section_uid)
TTL toDateTime(started_at) + INTERVAL 18 MONTH;


CREATE TABLE IF NOT EXISTS play_section_coverage ON CLUSTER mupid
AS play_section_coverage_local
ENGINE = Distributed(mupid, currentDatabase(), play_section_coverage_local, cityHash64(user_id));


CREATE TABLE IF NOT EXISTS rec_impressions_local ON CLUSTER mupid
(
    rec_id             String,
    user_id            UInt64,
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
    st.entry_path,
    (s.position_ms - sec.start_ms) AS into_ms,
    (sec.end_ms - sec.start_ms) AS sec_len,
    multiIf(
        r.n > 0,                                     'SKIP_AFTER_HOOK',
        into_ms <= 5000 OR into_ms <= sec_len * 0.2, 'EARLY_SKIP',
        into_ms >= sec_len * 0.8,                    'LATE_SKIP',
        'MID_SKIP'
    ) AS skip_class,
    multiIf(
        skip_class = 'SKIP_AFTER_HOOK', 0.0,
        skip_class = 'EARLY_SKIP' AND st.entry_path = 'RECOMMEND',
            if(s.skip_trigger = 'NEXT_BUTTON', -1.0, -0.4),
        skip_class = 'LATE_SKIP', 0.3,
        0.0
    ) AS weight
FROM listen_events AS s
INNER JOIN v_track_sections_current AS sec
        ON sec.track_id = s.track_id
       AND s.position_ms >= sec.start_ms
       AND s.position_ms < sec.end_ms
LEFT JOIN
(
    SELECT play_id, any(entry_path) AS entry_path
    FROM listen_events
    WHERE event_type = 'PLAY_START'
    GROUP BY play_id
) AS st ON st.play_id = s.play_id
LEFT JOIN
(
    SELECT user_id, section_uid, count() AS n
    FROM play_section_coverage
    WHERE listened_ms > section_ms * 0.5
    GROUP BY user_id, section_uid
) AS r ON r.user_id = s.user_id AND r.section_uid = sec.section_uid
WHERE s.event_type = 'TRACK_SKIP'
  AND s.track_id > 0;


CREATE TABLE IF NOT EXISTS kafka_listen_events ON CLUSTER mupid
(
    event_id     String,
    event_type   String,
    user_id      UInt64,
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
    playlist_id  UInt64,
    comment_id   UInt64,
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
