-- AI 선호도 시간별 집계 표를 만든다 (S15P21A204-427).
--
-- init/01-schema.sql 끝에 추가한 두 표와 **글자 하나 다르지 않다.** 새로 만드는
-- 클러스터는 init 이 알아서 만들고, 이 파일은 **이미 떠 있는 클러스터**(운영 k3s,
-- 로컬 dev)에 손으로 한 번 적용하기 위한 것이다. 전부 `IF NOT EXISTS` 라 두 번
-- 돌려도 안전하고, 기존 표에는 손대지 않는다 — DROP 도 ALTER 도 없다.
--
-- 적용 (한 노드에서 한 번. ON CLUSTER 가 나머지 노드로 퍼뜨린다)
--
--   clickhouse-client --user mupid --password "$MUPID_CLICKHOUSE_PASSWORD" \
--       --multiquery < api/clickhouse/migrations/04-ai-preference-hourly.sql
--
-- 확인
--
--   SELECT hostName(), count() FROM clusterAllReplicas(mupid_all, system.tables)
--   WHERE database = 'default' AND name LIKE 'ai_preference_hourly%' GROUP BY 1;
--   -- 노드마다 2 가 나와야 한다
--
-- 적용 뒤 첫 배치(매시 5분)가 직전 6시간만 채운다. 그 전 기간은 관리자 API
-- `POST /api/v1/admin/ai-preference/aggregate?from=YYYY-MM-DD&to=YYYY-MM-DD` 로
-- 채운다 (한 번에 31일까지, 몇 번을 불러도 같은 값이다).
--
-- 되돌리기
--
--   DROP TABLE IF EXISTS ai_preference_hourly ON CLUSTER mupid_all;
--   DROP TABLE IF EXISTS ai_preference_hourly_local ON CLUSTER mupid_all SYNC;
--
--   원본(listen_events)에서 언제든 다시 계산할 수 있는 파생 표라 지워도 잃는 것이 없다.
CREATE TABLE IF NOT EXISTS ai_preference_hourly_local ON CLUSTER mupid_all
(
    hour              DateTime('Asia/Seoul'),
    segment           LowCardinality(String),
    plays             UInt32,
    users             UInt32,
    engaged_plays     UInt32,
    completed_plays   UInt32,
    skipped_plays     UInt32,
    early_skip_plays  UInt32,
    liked_plays       UInt32,
    listened_ms       UInt64,
    computed_at       DateTime64(3, 'Asia/Seoul')
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/ai_preference_hourly', '{replica}', computed_at)
PARTITION BY toYYYYMM(hour)
ORDER BY (hour, segment)
TTL hour + INTERVAL 18 MONTH;


CREATE TABLE IF NOT EXISTS ai_preference_hourly ON CLUSTER mupid_all
AS ai_preference_hourly_local
ENGINE = Distributed(mupid_all, currentDatabase(), ai_preference_hourly_local);
