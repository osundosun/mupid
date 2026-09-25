-- 곡 단위 특징 사본 track_features 와 서빙 조회 객체를 만들고, 구간에 energy 를 더한다 (S15P21A204-455).
--
-- init/01-schema.sql 과 **글자 하나 다르지 않다**(아래 2~4). 새로 만드는 클러스터는 init 이 만들고,
-- 이 파일은 **이미 떠 있는 클러스터**(운영 k3s, 로컬 dev)에 손으로 한 번 적용한다. 전부
-- `IF NOT EXISTS` / `OR REPLACE` 라 두 번 돌려도 안전하다. 기존 표·MV·사전은 지우지 않는다 —
-- `track_sections` 에는 컬럼을 **더하기만** 하고, `v_track_sections_current`·`dict_track_sections`·
-- `mv_play_section_coverage` 는 컬럼을 이름으로 고르므로 그대로 돈다.
--
-- 적용 (한 노드에서 한 번. ON CLUSTER 가 나머지 노드로 퍼뜨린다)
--
--   kubectl -n mupid exec -i clickhouse-0 -- clickhouse-client --user mupid --       --password "$MUPID_CLICKHOUSE_PASSWORD" --multiquery < api/clickhouse/migrations/05-track-features.sql
--
-- **api 를 배포하기 전에** 건다. 새 api 는 track_sections 에 energy 를 싣고 track_features 에 쓴다 —
-- 표가 없으면 쓰기가 실패해 미반영 기록에 쌓이기만 한다(MySQL 은 성공한다). 틀리지는 않지만
-- 대시보드가 WARN 이 된다.
--
-- 확인
--
--   SELECT hostName(), name FROM clusterAllReplicas(mupid_all, system.tables)
--   WHERE database = 'default' AND name IN ('track_features_local', 'track_features',
--         'v_serving_sections', 'dict_serving_sections') ORDER BY 1, 2;   -- 노드마다 넷
--   SELECT hostName(), count() FROM clusterAllReplicas(mupid_all, system.columns)
--   WHERE database = 'default' AND table LIKE 'track_sections%' AND name = 'energy' GROUP BY 1;  -- 노드마다 2
--
-- 채우기(백필)
--
--   새 표는 비어 있고, 기존 구간 행의 energy 는 NULL 이다. 채우는 명령을 따로 두지 않는다 —
--   Spring 대조(구간 04:40, 곡 특징 04:45)가 전 곡을 불일치로 잡아 미반영에 넣고, 재시도가 1분에
--   종류마다 200곡씩 MySQL 값으로 채운다(1,060곡 약 6분). 다음 새벽을 기다리지 않으려면 MySQL 에
--   미반영 행을 넣는다 — `api/knowledge/architecture/analytics-sync.md` "운영에서 손으로 할 일".
--
-- 되돌리기
--
--   DROP DICTIONARY IF EXISTS dict_serving_sections ON CLUSTER mupid_all;
--   DROP VIEW IF EXISTS v_serving_sections ON CLUSTER mupid_all;
--   DROP TABLE IF EXISTS track_features ON CLUSTER mupid_all;
--   DROP TABLE IF EXISTS track_features_local ON CLUSTER mupid_all SYNC;
--   ALTER TABLE track_sections ON CLUSTER mupid_all DROP COLUMN IF EXISTS energy;
--   ALTER TABLE track_sections_local ON CLUSTER mupid_all DROP COLUMN IF EXISTS energy;
--
--   모두 MySQL 에서 다시 만들 수 있는 사본이라 지워도 잃는 것이 없다. 되돌린 뒤에는 옛 api 로 돌린다
--   (새 api 는 energy 컬럼을 싣는다).

-- ---------------------------------------------------------------- 1. 구간에 energy
-- 로컬 표와 Distributed 표 둘 다. Distributed 는 `AS track_sections_local` 로 만든 순간의 컬럼을
-- 복사해 들고 있어 로컬만 고치면 따라오지 않는다.
ALTER TABLE track_sections_local ON CLUSTER mupid_all
    ADD COLUMN IF NOT EXISTS energy Nullable(Float64) AFTER end_ms;

ALTER TABLE track_sections ON CLUSTER mupid_all
    ADD COLUMN IF NOT EXISTS energy Nullable(Float64) AFTER end_ms;


-- ---------------------------------------------------------------- 2~4. 곡 특징·서빙 뷰·사전
-- 곡 단위 특징 사본 (S15P21A204-455, 저장소 재설계 §6.2). MySQL 이 정본이고 Spring 이
-- 곡마다 통째로 갈아 끼운다(`analytics/repository/TrackFeatureRepository`). 값의 정의는
-- 추천 요청 본문 `catalog.tracks` 열과 **같다** — 서빙이 본문 대신 여기를 읽어도 순위가
-- 바뀌지 않아야 하기 때문이다 (`RecommenderCatalogServiceImpl.build`).
--
--   track_id          ← music.music_id
--   track             문자열 곡 ID. 곡의 첫 구간(시작 시각 순) `external_id` 의 `#` 앞.
--                     구간이 없는 곡은 빈 문자열이다
--   duration_ms       재생 가능한(`DONE`) 스트림 길이의 최댓값. 없으면 NULL
--   bpm … vocal_gender  `music_analysis` 그대로. 분석 전이면 NULL
--   genres · moods    `music_genres`·`music_moods` 의 코드 이름, 코드 번호순
--   is_deleted        `music.is_deleted`. 곡 삭제·복구가 여기로 온다
--   playable          `DONE` 스트림이 하나라도 있는가
--   analysis_version  곡의 구간 분석 버전(첫 구간의 것). 구간이 없으면 빈 문자열
--   version           Spring 이 쓴 시각(epoch ms). ReplacingMergeTree 의 버전 컬럼
--
-- 행을 지우지 않는다. 삭제된 곡도 `is_deleted = true` 로 남는다 — 구간 사본
-- (`track_sections`)이 삭제를 모르므로, 서빙은 이 표와 맞춰 봐야 삭제된 곡을 뺀다.
--
-- 병합 전에는 옛 행이 남는다. **읽을 때 FINAL 을 붙인다**(설계 §8.2-6). 곡 수만큼의
-- 행이라 비용은 작다.
CREATE TABLE IF NOT EXISTS track_features_local ON CLUSTER mupid_all
(
    track_id           UInt32,
    track              String,
    duration_ms        Nullable(UInt32),
    bpm                Nullable(Float64),
    bpm_requested_min  Nullable(Int32),
    bpm_requested_max  Nullable(Int32),
    language           Nullable(String),
    category           Nullable(String),
    genres             Array(String),
    moods              Array(String),
    has_vocal          Nullable(Bool),
    vocal_gender       Nullable(String),
    is_deleted         Bool,
    playable           Bool,
    analysis_version   LowCardinality(String),
    version            UInt64
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/track_features', '{replica}', version)
ORDER BY track_id;


CREATE TABLE IF NOT EXISTS track_features ON CLUSTER mupid_all
AS track_features_local
ENGINE = Distributed(mupid_all, currentDatabase(), track_features_local);


-- 서빙이 보는 구간: 곡의 **현재 분석 버전**만, 병합 전 중복을 누른 채로.
--
-- `track_sections` 는 재분석 뒤 옛 버전 행을 남긴다(교체가 같은 버전 안에서만 옛 순번을
-- 지운다). `v_track_sections_current` 는 버전을 'v1' 로 박아 두었지만 서빙은 곡마다
-- `track_features.analysis_version` 에 맞춘다 — 그 버전이 Qdrant 구간 지문과 대조하는
-- 값이다(설계 §9.3-3). 특징 행이 없는 곡의 구간은 나오지 않는다.
CREATE OR REPLACE VIEW v_serving_sections ON CLUSTER mupid_all AS
SELECT s.section_uid              AS section_uid,
       s.track_id                 AS track_id,
       s.music_section_analysis_id AS music_section_analysis_id,
       s.section_index            AS section_index,
       toString(s.fn)             AS section_label,
       s.start_ms                 AS start_ms,
       s.end_ms                   AS end_ms,
       s.energy                   AS energy,
       toString(s.analysis_version) AS analysis_version
FROM track_sections_local AS s FINAL
INNER JOIN
(
    SELECT track_id, analysis_version FROM track_features_local FINAL
) AS f ON f.track_id = s.track_id AND f.analysis_version = s.analysis_version;


-- 후보 수백 개의 구간을 `section_uid` 로 바로 찾는 서빙용 사전 (설계 §6.2 "읽는 법").
--
-- 구간 표의 정렬 키 `(analysis_version, track_id, section_index)` 로는 `section_uid IN (…)`
-- 가 표 전체를 훑는다. 정렬 키가 다른 표를 하나 더 두면 Spring 이 두 표를 교체하고 옛
-- 행도 두 번 지워야 하는데, 사전은 원본에서 **통째로 다시 읽어** 만들어지므로 지운 구간이
-- 남지 않는다. 노드마다 자기 로컬 표에서 읽어 메모리에 든다 — 서빙 조회가 다른 노드로
-- 나가지 않는다.
--
-- 늦어도 LIFETIME MAX(30초) 안에 원본을 따라온다. INVALIDATE_QUERY 가 두 표의 활성
-- 파트가 바뀌었을 때만 다시 읽게 한다(INSERT·병합·mutation 모두 파트를 바꾼다). 데이터베이스를
-- `currentDatabase()` 가 아니라 'default' 로 적는다 — 사전의 소스 연결이 어느 DB 로 붙는지에
-- 기대면, 어긋났을 때 이 질의가 늘 같은 값(빈 합계)을 내 사전이 **다시는 안 읽힌다.** 소스
-- `TABLE` 도 DB 를 적지 않아 'default' 를 본다(`dict_track_sections` 와 같다, README). 그 사이
-- 삭제된 곡이 보이는 틈은 `track_features` 를 FINAL 로 함께 읽어 메우고, 끝의 틈은 Spring
-- 최종 필터가 막는다(설계 §7.4).
CREATE DICTIONARY IF NOT EXISTS dict_serving_sections ON CLUSTER mupid_all
(
    section_uid                String,
    track_id                   UInt32,
    music_section_analysis_id  UInt32,
    section_index              UInt32,
    section_label              String,
    start_ms                   UInt32,
    end_ms                     UInt32,
    energy                     Nullable(Float64),
    analysis_version           String
)
PRIMARY KEY section_uid
SOURCE(CLICKHOUSE(TABLE 'v_serving_sections' INVALIDATE_QUERY
    'SELECT sum(rows), max(modification_time) FROM system.parts WHERE database = ''default'' AND table IN (''track_sections_local'', ''track_features_local'') AND active'))
LAYOUT(COMPLEX_KEY_HASHED_ARRAY())
LIFETIME(MIN 10 MAX 30);
