-- 구간에 악기(instruments)를 더하고 서빙 뷰·사전에 싣는다 (S15P21A204-458, 계약 v3).
--
-- 계약 v3 에서 Spring 이 추천 요청에 `catalog` 를 싣지 않고 조건도 걸지 않는다. 검색 조건
-- (`filters`/`exclude`)은 추천 서버가 ClickHouse 피처 저장소에서 건다 — 장르·언어·보컬은
-- `track_features` 에 있지만 악기는 **구간**에 달린 값(`music_analysis_instruments`)이라 어디에도
-- 없었다. 그래서 `track_sections` 에 열을 더한다. 표를 따로 두지 않는 이유는 energy(05)와 같다 —
-- 같은 요청(`PUT …/section`)이 구간과 함께 바꾸고, 같은 곡 단위 교체·미반영 기록·대조를 그대로 탄다.
--
--   instruments   구간 악기의 **코드 이름**(`codes.code_name`), 코드 번호순. 없으면 빈 배열.
--                 `other`·`그 외 악기` 같은 구체적이지 않은 이름도 그대로 싣는다 — 조건에서 빼는
--                 것은 읽는 쪽(추천 서버) 몫이다. 값은 Spring `TrackSectionSync` 가 쓴다.
--
-- init/01-schema.sql 과 **글자 하나 다르지 않다**(아래 2·3). 새로 만드는 클러스터는 init 이 만들고,
-- 이 파일은 **이미 떠 있는 클러스터**(운영 k3s, 로컬 dev)에 손으로 한 번 적용한다. 05 가 먼저
-- 적용돼 있어야 한다(`v_serving_sections`·`dict_serving_sections` 를 다시 만든다). 전부
-- `IF NOT EXISTS` / `OR REPLACE` 라 두 번 돌려도 안전하다. `v_track_sections_current`·
-- `dict_track_sections`·`mv_play_section_coverage` 는 컬럼을 이름으로 고르므로 그대로 돈다.
--
-- 적용 (한 노드에서 한 번. ON CLUSTER 가 나머지 노드로 퍼뜨린다)
--
--   kubectl -n mupid exec -i clickhouse-0 -- clickhouse-client --user mupid --password "$MUPID_CLICKHOUSE_PASSWORD" --multiquery < api/clickhouse/migrations/06-section-instruments.sql
--
-- **api 를 배포하기 전에** 건다. 새 api 는 track_sections 에 instruments 를 싣는다 — 열이 없으면
-- INSERT 가 실패해 구간 쓰기가 전부 미반영 기록에 쌓인다(MySQL 은 성공한다). 틀리지는 않지만
-- 대시보드가 WARN 이 되고, 그동안 새 구간이 사본에 안 들어가 재생 신호가 버려진다.
-- 추천 서버(악기 조건을 이 열에서 읽는 판)는 **이것과 api 백필이 끝난 뒤에** 올린다 — 먼저 올리면
-- 빈 배열만 보여 악기 조건이 전부 결과 0 이 된다.
--
-- 확인
--
--   SELECT hostName(), table, type FROM clusterAllReplicas(mupid_all, system.columns)
--   WHERE database = 'default' AND table LIKE 'track_sections%' AND name = 'instruments'
--   ORDER BY 1, 2;                                                     -- 노드마다 2줄, Array(String)
--   SELECT hostName(), count() FROM clusterAllReplicas(mupid_all, system.columns)
--   WHERE database = 'default' AND table = 'v_serving_sections' AND name = 'instruments' GROUP BY 1;  -- 노드마다 1
--   SELECT hostName(), status, attribute.names FROM clusterAllReplicas(mupid_all, system.dictionaries)
--   WHERE database = 'default' AND name = 'dict_serving_sections';    -- 노드마다 LOADED, 끝에 instruments
--   SELECT dictGet('dict_serving_sections', 'instruments', tuple(section_uid))
--   FROM v_serving_sections WHERE notEmpty(instruments) LIMIT 3;      -- 백필 뒤에 값이 보인다
--
-- 채우기(백필)
--
--   기존 구간 행의 instruments 는 빈 배열이다. 채우는 명령을 따로 두지 않는다 — Spring 구간 대조
--   (04:40)의 체크섬에 악기가 들어가 있어(`SectionDigestRepository`·`TrackSectionRepository`)
--   악기가 있는 곡이 전부 불일치로 잡혀 미반영에 들어가고, 재시도가 1분에 200곡씩 MySQL 값으로
--   교체한다. 다음 새벽을 기다리지 않으려면 MySQL 에 미반영 행을 넣는다 —
--   `api/knowledge/architecture/analytics-sync.md` "운영에서 손으로 할 일" (kind = 'CLICKHOUSE').
--
-- 되돌리기
--
--   사전 → 뷰를 05 의 정의(instruments 없는 것)로 되돌린 **뒤에** 열을 지운다 — 뷰·사전이 열을
--   가리키는 채로 지우면 뷰 조회와 사전 로딩이 실패한다.
--
--   DROP DICTIONARY IF EXISTS dict_serving_sections ON CLUSTER mupid_all;
--   (05-track-features.sql 의 CREATE OR REPLACE VIEW v_serving_sections … 를 다시 건다)
--   (05-track-features.sql 의 CREATE DICTIONARY IF NOT EXISTS dict_serving_sections … 를 다시 건다)
--   ALTER TABLE track_sections ON CLUSTER mupid_all DROP COLUMN IF EXISTS instruments;
--   ALTER TABLE track_sections_local ON CLUSTER mupid_all DROP COLUMN IF EXISTS instruments;
--
--   MySQL 에서 다시 만들 수 있는 사본이라 잃는 것이 없다. 되돌린 뒤에는 옛 api 로 돌린다
--   (새 api 는 instruments 열을 싣는다).

-- ---------------------------------------------------------------- 1. 구간에 instruments
-- 로컬 표와 Distributed 표 둘 다. Distributed 는 `AS track_sections_local` 로 만든 순간의 컬럼을
-- 복사해 들고 있어 로컬만 고치면 따라오지 않는다. 기본값은 빈 배열이다(Array 의 기본값).
ALTER TABLE track_sections_local ON CLUSTER mupid_all
    ADD COLUMN IF NOT EXISTS instruments Array(String) AFTER energy;

ALTER TABLE track_sections ON CLUSTER mupid_all
    ADD COLUMN IF NOT EXISTS instruments Array(String) AFTER energy;


-- ---------------------------------------------------------------- 2. 서빙 뷰
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
       s.instruments              AS instruments,
       toString(s.analysis_version) AS analysis_version
FROM track_sections_local AS s FINAL
INNER JOIN
(
    SELECT track_id, analysis_version FROM track_features_local FINAL
) AS f ON f.track_id = s.track_id AND f.analysis_version = s.analysis_version;


-- ---------------------------------------------------------------- 3. 서빙 사전
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
--
-- `instruments` 는 Array 속성이다(25.3 의 COMPLEX_KEY_HASHED_ARRAY 가 받는다, 2026-09-25 dev 확인).
-- 없는 키의 기본값은 빈 배열이다. 속성을 더하려고 `OR REPLACE` 로 만든다 — `IF NOT EXISTS` 로는
-- 이미 있는 사전이 그대로 남는다.
CREATE OR REPLACE DICTIONARY dict_serving_sections ON CLUSTER mupid_all
(
    section_uid                String,
    track_id                   UInt32,
    music_section_analysis_id  UInt32,
    section_index              UInt32,
    section_label              String,
    start_ms                   UInt32,
    end_ms                     UInt32,
    energy                     Nullable(Float64),
    instruments                Array(String),
    analysis_version           String
)
PRIMARY KEY section_uid
SOURCE(CLICKHOUSE(TABLE 'v_serving_sections' INVALIDATE_QUERY
    'SELECT sum(rows), max(modification_time) FROM system.parts WHERE database = ''default'' AND table IN (''track_sections_local'', ''track_features_local'') AND active'))
LAYOUT(COMPLEX_KEY_HASHED_ARRAY())
LIFETIME(MIN 10 MAX 30);
