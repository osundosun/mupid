# 분석 파이프라인 (ClickHouse · Kafka)

청취 이벤트를 적재·파생하는 분석 스택의 구조와 제약을 정리한다. 스키마(`api/clickhouse/init/01-schema.sql`)나 ClickHouse/Kafka 설정 XML 을 고칠 때, 로컬에서 분석 스택을 처음 띄울 때, Spring 에서 ClickHouse 를 조회하는 코드를 붙일 때 읽는다. 이 영역은 CI 가 전혀 검증하지 않으므로 고친 뒤 `verify.sh` 를 직접 돌려야 한다.

## 파일 위치

```
api/clickhouse/
  README.md                  구성·실행·함정 가이드 (원본 1차 문서)
  init/01-schema.sql         전체 DDL. ON CLUSTER 로 한 번만 적용한다
  init/apply-ddl.sh          5단계: 노드 응답 → Keeper → 클러스터 등록 → DDL → 검증
  kafka/create-topics.sh     mupid.listen.events 토픽 생성 (partitions=6, rf=1)
  keeper/keeper_config.xml   Keeper 서버 자체 설정 (단일 노드, server_id=1)
  server/00-cluster.xml      remote_servers: mupid / mupid_all
  server/01-keeper.xml       <zookeeper> 노드 + distributed_ddl path
  server/02-server.xml       logger / timezone / listen_host / max_server_memory_usage
  server/10-macros.xml       macros {shard} {replica} + interserver_http_host
  users/00-profile.xml       profiles.default (쿼리 레벨 설정)
  users/10-mupid-user.xml    앱 계정 mupid
  verify.sh                  28개 항목 스모크 테스트
```

기동 정의는 세 벌로 나뉜다.

| 파일 | compose name | 분석 스택 |
| --- | --- | --- |
| `api/compose.yml` | `mupid-dev` | 전체 포함. `clickhouse-keeper` / `clickhouse-01` / `clickhouse-02` / `kafka` / `kafka-ui`(profile `ui`) / `kafka-init` / `clickhouse-init` |
| `api/compose.prod.yml` | `mupid` | 없음. 주석에 "ClickHouse 클러스터…는 이 compose 가 띄우지 않는다", `MUPID_CLICKHOUSE_URL` 을 운영 `.env` 에서 덮어쓰라고 적혀 있다 |
| `infra/deploy/compose.yml` | `mupid` | 포함하되 profile 로 격리. 실제 배포 엔드포인트(`infra/deploy/receive.sh`)가 쓰는 파일이다 |

Spring 쪽은 `api/src/main/java/com/osundosun/mupid/api/analytics/` 아래 두 파일뿐이다.

## 클러스터 토폴로지

ClickHouse 서버 2노드 + Keeper 1대 + Kafka 1대(KRaft, broker+controller 겸용).

`api/clickhouse/server/00-cluster.xml` 은 **같은 두 노드를 두 개의 클러스터로 이중 정의**한다.

| 클러스터 | 토폴로지 | 쓰는 테이블 |
| --- | --- | --- |
| `mupid` | 2 샤드 × 1 레플리카. `cityHash64(user_id)` 분산 | `listen_events`, `user_section_signals`, `play_section_coverage`, `rec_impressions`, `kafka_listen_events`, `v_track_skips` |
| `mupid_all` | 1 샤드 × 2 레플리카. 전 노드 복제 | `track_sections`, `v_track_sections_current`, `dict_track_sections` |

이 구분은 Keeper 경로와 1:1 대응한다.

- 이벤트 테이블: `'/clickhouse/tables/{shard}/listen_events'` — `{shard}` 가 들어가 샤드별로 데이터가 갈린다.
- `track_sections_local` 만: `'/clickhouse/tables/track_sections'` — `{shard}` 가 없어 두 노드가 서로의 레플리카가 된다.

이유는 `00-cluster.xml` 주석에 있다. `mv_user_section_signals` 가 **각 노드에서** `dictGet` 을 호출하므로 모든 노드가 전체 구간 데이터를 갖고 있어야 한다. 사전(dictionary) 소스만 전 노드 복제하고 나머지는 샤딩하는, 목적에 맞춘 분리다.

### 노드 정체성은 전부 환경변수

`server/*.xml` 과 `users/*.xml` 은 노드별 차이를 `from_env` 로만 받는다. 그래서 로컬 컨테이너 2개와 운영 서버 2대가 **같은 파일을 그대로** 쓴다.

| 변수 | 읽는 파일 | 로컬 ch-01 | 로컬 ch-02 | 운영 서버 A / B |
| --- | --- | --- | --- | --- |
| `CH_SHARD` | `10-macros.xml` | `01` | `02` | `01` / `02` |
| `CH_REPLICA` | `10-macros.xml` | `ch1` | `ch2` | `ch1` / `ch2` |
| `CH_INTERSERVER_HOST` | `10-macros.xml` | `mupid-dev-ch-01` | `mupid-dev-ch-02` | 각 서버 주소 |
| `CH_HOST_1` / `CH_HOST_2` | `00-cluster.xml` | 컨테이너 이름 | 동일 | 서버 A / B 주소 |
| `CH_KEEPER_HOST` | `01-keeper.xml` | `mupid-dev-ch-keeper` | 동일 | Keeper 주소 |
| `CH_MAX_SERVER_MEMORY` | `02-server.xml` | `4294967296` (4GiB) | 동일 | `0` = 자동 |
| `MUPID_CLICKHOUSE_PASSWORD` | `00-cluster.xml`, `10-mupid-user.xml` | `.env` | 동일 | 시크릿 |

예외는 Keeper 노드 목록이다. 노드 개수가 가변이라 환경변수 하나로는 표현할 수 없어서, 운영에서는 `server/01-keeper.xml` 의 `<zookeeper>` 블록을 Keeper 노드 수만큼 직접 늘려 쓴다(같은 파일 주석). Keeper 서버 자체 설정인 `keeper/keeper_config.xml` 은 `from_env` 를 하나도 쓰지 않지만 저장소에 한 벌뿐이고, `api/compose.yml` 111행과 `infra/deploy/compose.yml` 176행이 같은 파일을 마운트한다(운영은 `/srv/mupid/config/clickhouse/keeper/keeper_config.xml` 로 복사된 것).

**Keeper 는 단일 노드다.** `raft_configuration` 에 `server_id=1` 하나뿐이라 쿼럼이 없고, 죽으면 Replicated 테이블이 읽기 전용이 된다. 운영도 같은 파일을 쓰므로 동일하다. 늘리려면 `keeper_config.xml` 의 `raft_configuration` 과 `server/01-keeper.xml` 의 `<zookeeper>` 블록을 **함께** 3 노드 이상(홀수)으로 늘려야 한다.

## 스키마 설계

`api/clickhouse/init/01-schema.sql` 한 파일이 전부다. 명명 규칙은 로컬 테이블 `_local`, Distributed 는 접미사 없는 같은 이름, 뷰 `v_`, 머티리얼라이즈드 뷰 `mv_`, 사전 `dict_`, Kafka 엔진 `kafka_`.

| 오브젝트 | 클러스터 | 엔진 | 정렬키 |
| --- | --- | --- | --- |
| `listen_events_local` | `mupid` | `ReplicatedReplacingMergeTree(…, ingested_at)` | `(user_id, occurred_at, event_id)` |
| `listen_events` | `mupid` | `Distributed(…, cityHash64(user_id))` | — |
| `track_sections_local` | `mupid_all` | `ReplicatedReplacingMergeTree(…, created_at)` | `(analysis_version, track_id, section_index)` |
| `track_sections` | `mupid_all` | `Distributed(…)` — 샤딩 키 없음 | — |
| `v_track_sections_current` | `mupid_all` | `VIEW` (`analysis_version = 'v1'` 필터) | — |
| `dict_track_sections` | `mupid_all` | `DICTIONARY` / `RANGE_HASHED` / `RANGE(MIN start_ms MAX end_ms)` / `LIFETIME(MIN 300 MAX 600)` | `PRIMARY KEY track_id` |
| `user_section_signals_local` | `mupid` | `ReplicatedMergeTree` | `(user_id, section_uid, occurred_at)` |
| `user_section_signals` | `mupid` | `Distributed(…, cityHash64(user_id))` | — |
| `mv_user_section_signals` | `mupid` | `MATERIALIZED VIEW TO user_section_signals_local` | — |
| `play_section_coverage_local` | `mupid` | `ReplicatedSummingMergeTree(…, (listened_ms))` | `(user_id, play_id, section_uid)` |
| `play_section_coverage` | `mupid` | `Distributed(…, cityHash64(user_id))` | — |
| `mv_play_section_coverage` | `mupid` | `MATERIALIZED VIEW TO play_section_coverage_local` | — |
| `rec_impressions_local` | `mupid` | `ReplicatedMergeTree` | `(user_id, served_at, rec_id)` |
| `rec_impressions` | `mupid` | `Distributed(…, cityHash64(user_id))` | — |
| `v_track_skips` | `mupid` | `VIEW` — `listen_events` 에 `v_track_sections_current` 와 Distributed 서브쿼리 1개를 JOIN | — |
| `kafka_listen_events` | `mupid` | `Kafka` | — |
| `mv_kafka_to_events` | `mupid` | `MATERIALIZED VIEW TO listen_events_local` | — |

### 엔진 선택의 의도

- **`listen_events_local` = ReplacingMergeTree(`ingested_at`).** 정렬키 `(user_id, occurred_at, event_id)` 가 곧 중복 판정 키다. Kafka 재소비로 같은 이벤트가 다시 들어와도 병합 시 정리된다. 로컬 `api/compose.yml` 이 Kafka 로그에 볼륨을 일부러 안 붙인 근거가 이 성질이다.
- **`track_sections_local` = ReplacingMergeTree(`created_at`).** 같은 `(analysis_version, track_id, section_index)` 재등록을 최신본으로 덮어쓴다. 마스터성 데이터라 파티션·TTL 이 없는 유일한 테이블이다.
- **`user_section_signals_local` / `rec_impressions_local` = ReplicatedMergeTree.** 중복 정리 장치가 없다.
- **`play_section_coverage_local` = SummingMergeTree(`listened_ms`).** `mv_play_section_coverage` 가 하트비트마다 부분합 행을 넣고, 병합될 때 `listened_ms` 만 합쳐진다. 병합 시점을 보장할 수 없는 데다 Kafka 적재가 `user_id` 샤딩을 타지 않아 한 재생의 부분합이 두 샤드에 흩어질 수 있으므로, **읽을 때 Distributed 테이블에서 재생 단위로 GROUP BY 해야 한다.**

### 파티션 · TTL · 시간대

이벤트 테이블 4종은 전부 동일하다.

```sql
PARTITION BY toYYYYMM(<시각 컬럼>)
TTL toDateTime(<시각 컬럼>) + INTERVAL 18 MONTH
```

시각 컬럼(`occurred_at` / `started_at` / `served_at`)은 모두 `DateTime64(3, 'Asia/Seoul')` 이고, `server/02-server.xml` 이 `<timezone>Asia/Seoul</timezone>` 로 서버 기본 시간대를 컬럼 타입과 맞춰 둔다(주석에 이유 명시). TTL 이 `toDateTime()` 으로 감싸인 것은 `DateTime64` 를 초 단위로 낮춰 비교하기 위한 것이다.

정렬키는 모두 `user_id` 로 시작한다. Distributed 샤딩 키 `cityHash64(user_id)` 와 짝을 이뤄 "사용자 단위 조회"를 1차 접근 경로로 잡은 설계다.

## 데이터 흐름

```
프로듀서 --JSONEachRow--> Kafka topic mupid.listen.events (6 파티션)
  -> kafka_listen_events        Kafka 엔진. 노드당 kafka_num_consumers=3, 총 6 컨슈머
  -> mv_kafka_to_events         toUUIDOrZero / parseDateTime64BestEffort 로 타입 변환
  -> listen_events_local        자기 노드의 로컬 테이블
       |-> mv_user_section_signals   dictGet(dict_track_sections, section_uid, track_id, position_ms)
       |     -> user_section_signals_local
       +-> mv_play_section_coverage  PLAY_PROGRESS 의 [from_ms, position_ms) 를 구간 경계로 분할
             -> play_section_coverage_local
```

`rec_impressions` 만 이 연쇄에 없고 직접 INSERT 를 받는다. 프로듀서는 `event` 도메인이다 — `POST /api/v1/events` → `KafkaTemplate` → 토픽.

### `mv_user_section_signals` 의 가중치

`event_type` 이 아래 9종일 때만 시그널이 되고, `track_id > 0` · `position_ms > 0` · `dictGet(...) != ''` 를 모두 만족해야 한다.

| `event_type` | `weight` | | `event_type` | `weight` |
| --- | --- | --- | --- | --- |
| `SECTION_MARK` | `1.5` | | `DISLIKE` | `-1.0` |
| `LIKE` | `1.0` | | `PLAYLIST_REMOVE` | `-0.8` |
| `PLAYLIST_ADD` | `0.8` | | `UNLIKE` | `-0.5` |
| `COMMENT_ADD` | `0.7` | | `SECTION_UNMARK` | `-1.5` |
| `SEEK_BACK` | `0.6` | | | |

### `v_track_skips`

`TRACK_SKIP` 이벤트를 구간에 매핑해 `skip_type` 와 `weight` 를 계산한다.

| `skip_type` | 조건 | `weight` |
| --- | --- | --- |
| `SKIP_AFTER_HOOK` | `play_section_coverage` 를 재생 단위로 합산했을 때 그 `(user_id, section_uid)` 를 `listened_ms > section_ms * 0.5` 로 들은 재생이 있음 | `0.0` |
| `EARLY_SKIP` | `into_ms <= 5000` 또는 `into_ms <= sec_len * 0.2` | `entry_path='RECOMMEND'` 일 때 `NEXT_BUTTON` 이면 `-1.0`, 아니면 `-0.4`. 그 외 `0.0` |
| `LATE_SKIP` | `into_ms >= sec_len * 0.8` | `0.3` |
| `MID_SKIP` | 나머지 | `0.0` |

이 뷰는 `users/00-profile.xml` 의 `distributed_product_mode = global` 에 의존한다. 기본값 `deny` 로는 `DISTRIBUTED_IN_JOIN_SUBQUERY_DENIED` 가 난다. **서브쿼리를 `_local` 로 바꾸는 우회는 금지다** — Kafka 적재 경로가 `user_id` 샤딩을 타지 않아 한 사용자의 커버리지가 두 샤드에 흩어지고, 샤드 하나만 보면 `SKIP_AFTER_HOOK` 판정이 틀리게 나온다. 대신 broadcast 비용이 데이터 증가에 비례해 커지는 한계가 남아 있다(같은 주석에 명시). 컬럼 이름에 별칭이 남는 문제도 있다 → [함정과 실패 사례](../failure/clickhouse-and-kafka.md).

## Kafka 연동

토픽은 `mupid.listen.events` 하나, 파티션 6, replication factor 1 (`api/clickhouse/kafka/create-topics.sh`).

- **파티션 6의 근거**: `kafka_listen_events` 가 `ON CLUSTER mupid` 로 두 노드에 생기고 노드마다 `kafka_num_consumers = 3` 이라 그룹 `ch_listen_events` 의 컨슈머는 6개다. 파티션이 더 적으면 남는 컨슈머가 유휴가 된다. 같은 이유로 `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"` — 자동 생성에 맡기면 파티션이 1개가 되어 6개 중 5개가 논다.
- **브로커는 1대**다. compose 주석: 2대는 KRaft 컨트롤러 쿼럼이 2가 되어 장애를 한 대도 못 견딘다, HA 는 3대부터. 브로커 개수는 프로듀서 대수나 ClickHouse 노드 수와 무관하다.
- **`kafka_broker_list = 'kafka1:9092,kafka2:9092'`** 는 브로커 대수 요구가 아니라 부트스트랩 주소 목록이다. **DDL 을 수정하지 않기 위해** 로컬은 한 컨테이너에 `kafka1`/`kafka2` 두 네트워크 alias 를 붙이고(`api/compose.yml` 의 `networks.backend.aliases`), 운영은 `extra_hosts` 로 두 이름을 Kafka 서버 주소에 매핑한다(`infra/deploy/compose.yml`).

`kafka_listen_events` 는 전 컬럼을 **관대한 타입**으로 받는다. `event_id` / `play_id` / `occurred_at` 이 UUID·DateTime64 가 아니라 `String` 이고, 변환은 `mv_kafka_to_events` 가 `toUUIDOrZero()` / `parseDateTime64BestEffort(…, 3, 'Asia/Seoul')` 로 처리한다. `kafka_skip_broken_messages = 100` 과 `input_format_skip_unknown_fields = 1` 을 합쳐, 스키마가 조금 어긋난 메시지 때문에 컨슈머가 멈추지 않게 한 구성이다.

### 소비 단위 튜닝

```
kafka_max_block_size      = 65536    한 번에 쓸 행 수
kafka_poll_max_batch_size = 8192     한 번의 poll 로 가져올 메시지 수
kafka_flush_interval_ms   = 15000    행 수가 안 차도 이때까지는 기다린다
```

둘 중 먼저 닿는 쪽이 파트를 만든다. **기본값을 그대로 두면 사실상 7.5초마다 파트가 하나씩 생긴다** — 기본 행 수가 `max_insert_block_size`(1,048,576) 라 이 워크로드에서는 절대 차지 않고 항상 시간 쪽이 이기기 때문이다. 그 작은 파트가 계속 쌓이면 병합 압력이 올라가고, `play_section_coverage` 의 SummingMergeTree 부분합도 오래 흩어져 남는다(그래서 읽을 때 GROUP BY 로 합산해야 한다).

행 수를 현실적인 크기로 낮추고 대기를 늘려, 트래픽이 많을 때는 크기로 끊고 한산할 때는 시간으로 끊게 했다. 하트비트 간격을 바꾸면(`frontend/lib/analytics/player.ts` 의 `HEARTBEAT_MS`) 이 값도 함께 본다.

### 프로듀서

Spring 의 `event` 도메인이 유일한 프로듀서다(`EventKafkaConfig`). `spring.kafka.*` 자동설정을 쓰지 않고 빈을 직접 만든다 — 환경변수 `MUPID_` 접두사 강제(decision/0002) 때문이고, 컨슈머는 ClickHouse Kafka 엔진이 맡아 애플리케이션에는 프로듀서 하나만 필요해서다.

| 설정 | 값 | 이유 |
|---|---|---|
| `enable.idempotence` · `acks` | `true` · `all` | 프로듀서 내부 재시도가 같은 메시지를 두 번 넣지 않게 한다. MV 는 중복을 걸러주지 않는다 |
| `linger.ms` · `batch.size` | `20` · `64KB` | 하트비트가 몰리므로 조금 모아 보낸다. 기본 16KB 는 금방 차서 linger 를 채우기 전에 끊긴다 |
| `compression.type` | `lz4` | 대부분이 같은 모양의 하트비트 JSON 이라 잘 먹는다. CPU 를 거의 쓰지 않고 네트워크와 ClickHouse 쪽 디스크 I/O 를 함께 줄인다 |
| `max.block.ms` | `3s` | 브로커가 죽어 있을 때 `send` 가 요청 스레드를 오래 잡지 않게 한다 |

## 사용자 · 권한 · 설정 파일 분리

계정은 `mupid` 하나다(`users/10-mupid-user.xml`). Spring, 노드 간 클러스터 통신(`00-cluster.xml` 의 각 `<replica>` 에 `<user>mupid</user>`), 스키마 초기화(`apply-ddl.sh` 의 `CH_USER="${MUPID_CLICKHOUSE_USER:-mupid}"`) 가 전부 공유한다. 분리는 계정 단위가 아니라 **파일 단위**다.

| 레벨 | 마운트 위치 | 파일 |
| --- | --- | --- |
| 서버 레벨 | `/etc/clickhouse-server/config.d/` | `server/00-cluster.xml`, `01-keeper.xml`, `02-server.xml`, `10-macros.xml` |
| 쿼리(user) 레벨 | `/etc/clickhouse-server/users.d/` | `users/00-profile.xml` (`<profiles><default>`), `users/10-mupid-user.xml` |

파일명의 두 자리 숫자 접두사는 ClickHouse 의 병합 순서를 명시한 것이다. **이 경계를 넘으면 서버가 기동조차 하지 않는다** (`distributed_ddl_task_timeout`, `distributed_product_mode` 를 `config.d` 에 두면 `UNKNOWN_ELEMENT_IN_CONFIG`). 새 설정을 추가할 때 어느 쪽인지부터 확인할 것.

현재 `users/00-profile.xml` 이 잡은 값은 두 개뿐이다.

| 설정 | 값 | 이유 |
| --- | --- | --- |
| `distributed_ddl_task_timeout` | `300` | 기본 180초. 기동 직후 `ON CLUSTER` DDL 에 여유를 준다 |
| `distributed_product_mode` | `global` | `v_track_skips` 용. 위 "데이터 흐름 › v_track_skips" 참고 |

`default` 계정은 내부 통신에 쓰지 않는다. 공식 이미지 엔트리포인트가 `users.d/default-user.xml` 을 만들어 `default` 를 `127.0.0.1`/`::1` 로 묶고, 그 파일이 이름 순서상 `10-mupid-user.xml` 보다 늦게 병합되어 덮어쓸 수도 없다. 그래서 컨테이너 헬스체크만 `clickhouse-client --host 127.0.0.1 --query "SELECT 1"` 로 `default` 를 쓰고 나머지는 전부 `mupid` 다. 이미지의 `CLICKHOUSE_USER`/`CLICKHOUSE_PASSWORD` 환경변수도 쓰지 않는다 — 그 경로는 엔트리포인트가 `default` 를 `remove="remove"` 로 삭제해 복제가 깨진다.

권한 관련 현재 상태(운영 전 손봐야 할 것으로 파일 주석에 남아 있다):

- `<password from_env="MUPID_CLICKHOUSE_PASSWORD"/>` — 평문. 운영은 `<password_sha256_hex>` 로 바꿀 것.
- `<networks><ip>::/0</ip></networks>` — 전체 허용. API 서버 대역으로 좁힐 것.
- `<access_management>0</access_management>` — 잠겨 있다.
- 계정 이름 `mupid` 는 XML 엘리먼트 이름이라 환경변수로 못 받는다. 바꾸려면 `10-mupid-user.xml` 과 `00-cluster.xml` 의 `<user>` 를 함께 고친다.

저장소에 평문 비밀번호를 두지 않는다는 규칙은 compose 에도 이어진다: `${MUPID_CLICKHOUSE_PASSWORD:?.env 에 MUPID_CLICKHOUSE_PASSWORD 설정 필요}`.

## 로컬 기동과 검증

`api/.env` 에 `MUPID_CLICKHOUSE_PASSWORD` 가 있어야 한다(없으면 compose 가 즉시 실패).

```bash
cd api
docker compose up -d --wait        # --wait 없으면 clickhouse-init 완료를 안 기다린다
./clickhouse/verify.sh             # 28개 항목 스모크 테스트
```

| 컨테이너 | 역할 | 호스트 포트 |
| --- | --- | --- |
| `mupid-dev-ch-keeper` | Keeper. 복제 메타데이터 + `ON CLUSTER` DDL 큐 | — |
| `mupid-dev-ch-01` | `shard=01`, `replica=ch1` | 18123(HTTP), 19000(native) |
| `mupid-dev-ch-02` | `shard=02`, `replica=ch2` | 18124(HTTP), 19001(native) |
| `mupid-dev-kafka` | KRaft broker+controller. alias `kafka1`, `kafka2` | 19092 |
| `mupid-dev-kafka-init` | 1회성. 토픽 생성 후 종료 | — |
| `mupid-dev-ch-init` | 1회성. `apply-ddl.sh` 실행 후 종료 | — |

초기화 작업은 1회성 컨테이너로 분리하고 `restart: "no"` + `Exited (0)` 을 정상으로 본다. `clickhouse-init` 은 `clickhouse-01`/`clickhouse-02` 의 `service_healthy` 와 `kafka-init` 의 `service_completed_successfully` 를 기다린 뒤 **리더 노드(`CH_NODE_1`)에서 한 번만** DDL 을 적용한다.

눈으로 볼 때는 ClickHouse 내장 UI 를 쓴다. `http://localhost:18123/play` (계정 `mupid`), `http://localhost:18123/dashboard`. Kafka UI 는 `ui` profile 이라 `docker compose up -d kafka-ui` 로 따로 띄운다(`http://localhost:18080`, 인증 없음).

전부 초기화: `docker compose down -v --remove-orphans`. **`-v` 는 MySQL·Redis·스토리지 볼륨까지 지운다** — 분석 스키마만 다시 적용할 거면 분석 스택 컨테이너와 `ch1/ch2/ch-keeper` 볼륨만 지운다. 이 함정과 `--remove-orphans` 를 빠뜨리는 함정 모두 [함정과 실패 사례](../failure/clickhouse-and-kafka.md) 에 있다.

### `verify.sh` 가 검사하는 것

`cd api && ./clickhouse/verify.sh`. 비밀번호는 `MUPID_CLICKHOUSE_PASSWORD` 환경변수 또는 `api/.env` 에서 읽는다. `PASS`/`FAIL` 을 집계해 실패가 있으면 exit 1.

| 단계 | 검사 (개수) |
| --- | --- |
| 0 | `kafka-init`/`ch-init` 이 `exited` 가 될 때까지 최대 240초 대기 후 ExitCode 0 (2) |
| 1 | `system.clusters` 에서 `mupid`=2행, `mupid_all` 의 `uniqExact(shard_num)/count()` = `1/2` (2) |
| 2 | 두 노드 각각 Replicated/Distributed/View/Kafka/Dictionary = `5/5/5/1/1` (2) |
| 3 | `system.distributed_ddl_queue` 에 `status != 'Finished' OR exception_code != 0` 0건 (1) |
| 4 | `track_sections` 3건 INSERT 후 두 노드의 `track_sections_local` 에 각각 3건 — `mupid_all` 복제 확인 (2) |
| 5 | 두 노드에서 `SYSTEM RELOAD DICTIONARY` 후 `dictGet(…,1001,15000)` = `s1001-intro`, 구간 밖은 빈 문자열 (3) |
| 6 | 커버리지 초기화 후 이벤트 7건 발행 → 최대 60초 폴링, `uniqExact(event_id)` = 7, `occurred_at` = `2026-01-01 10:00:15.000` (3) |
| 7 | 시그널 4건(PLAY_START/TRACK_SKIP 제외), `LIKE`=1, `SECTION_MARK`→`s1001-hook`, `DISLIKE`=-1 (4) |
| 8 | `PLAY_PROGRESS` 하나가 `s1001-intro` 3000ms + `s1001-hook` 2000ms 로 쪼개지는지, 수동 INSERT 를 더한 부분합 22000ms, `rec_impressions` 의 `Array(String)`/`Array(Float32)`/`Map(String,Float32)`/`Map(String,String)` 왕복 (8) |
| 9 | `v_track_skips` 에서 EARLY_SKIP + RECOMMEND + NEXT_BUTTON → `EARLY_SKIP/-1` (1) |

멱등성을 위해 고정 ID(`track_id` 1001/2002, `user_id` 42/77, `event_id` `1111…`~`7777…`, `rec_id` `rec-0001`)만 쓰고 집계도 그 ID 로 한정한다. 수동 테스트 데이터가 남아 있어도 통과한다. 다만 `play_section_coverage` 는 SummingMergeTree 이고 MV 가 재실행마다 다시 발화해 누적되므로, 6단계 앞에서 테스트 사용자(42/77)의 커버리지를 `ALTER TABLE … DELETE` 로 먼저 지운다. 8단계가 커버리지 구간으로 `s1001-hook` 을 고른 것도 9단계와의 충돌 회피 목적이다(주석에 명시) — `s1001-intro` 에 커버리지가 있으면 `skip_type` 가 `SKIP_AFTER_HOOK` 으로 바뀐다.

**CI 는 이 영역을 전혀 검증하지 않는다.** `infra/ci/backend.yml` 첫 줄이 "Build-only CI by team decision; application tests are not executed." 이고 `build -x test` 만 돈다. `01-schema.sql` 이나 XML 을 깨뜨려도 파이프라인은 통과하므로, 고쳤으면 위 절차를 직접 돌릴 것.

## 운영 배포

`infra/deploy/compose.yml` 에 정의는 있지만 **전부 profile 뒤에 있다.** 배포 엔드포인트 `infra/deploy/receive.sh` 는 `up -d … api` 와 `nginx` 만 재기동하고 profile 을 켜지 않으므로, 기존 배포는 분석 스택의 영향을 받지 않는다.

| 서비스 | profile | 컨테이너 | 어느 서버 |
| --- | --- | --- | --- |
| `clickhouse` | `analytics` | `mupid-clickhouse` | A, B 양쪽 |
| `clickhouse-keeper` | `keeper` | `mupid-ch-keeper` | A |
| `kafka` | `kafka` | `mupid-kafka` | B |
| `kafka-init` / `clickhouse-init` | `init` | `mupid-kafka-init` / `mupid-ch-init` | 최초 1회 수동 |

```bash
# 서버 A(Spring):  CH_SHARD=01 CH_REPLICA=ch1
docker compose up -d clickhouse clickhouse-keeper
# 서버 B(FastAPI): CH_SHARD=02 CH_REPLICA=ch2
docker compose up -d clickhouse kafka

# 최초 1회 (둘 다 여러 번 실행해도 안전하다)
docker compose --profile init run --rm kafka-init        # 서버 B
docker compose --profile init run --rm clickhouse-init   # 서버 A
```

로컬은 컨테이너 이름과 네트워크 alias 로 서로를 찾지만, **Docker bridge 네트워크는 호스트를 넘지 못한다.** 그래서 운영은 아래 포트를 호스트에 게시하고 보안그룹에서 상대 서버 IP 에만 연다(파일 주석: "인터넷에 노출하면 안 된다").

| 포트 | 용도 | 환경변수 |
| --- | --- | --- |
| 9000 | ClickHouse native, 분산 쿼리 | `MUPID_CLICKHOUSE_NATIVE_BIND` |
| 9009 | ClickHouse interserver, 파트 복제 | `MUPID_CLICKHOUSE_INTERSERVER_BIND` |
| 9181 | Keeper | `MUPID_KEEPER_BIND` |
| 9092 | Kafka | `MUPID_KAFKA_BIND` |

운영 compose 는 분석 변수를 `${VAR:-}` (빈 기본값)로 받는다. compose 가 profile 로 거르기 **전에** 파일 전체를 치환하므로, `${VAR:?}` 로 필수 표시를 하면 analytics 를 안 쓰는 배포의 `up -d api` 까지 interpolation 오류로 실패하기 때문이다. 대가로 값을 빠뜨려도 compose 는 통과한다 → 올린 뒤 반드시 확인할 것.

```bash
docker exec mupid-clickhouse clickhouse-client -q "SELECT * FROM system.macros"
```

설정 파일은 `/srv/mupid/config/clickhouse/{server,users,keeper,kafka,init}` 에서 마운트한다. `api/clickhouse/` 를 서버로 복사하는 자동화는 저장소에 없다(`infra/ci/*`, `infra/bootstrap-docker.sh` 어디에도 없음) — **수동 복사 후 컨테이너 재생성이 필요하다.**

## Spring 연동

| 파일 | 내용 |
| --- | --- |
| `api/build.gradle` (41~43행) | `runtimeOnly 'com.clickhouse:clickhouse-jdbc:0.9.0'` |
| `api/src/main/resources/application.yaml` (51~60행) | `clickhouse.*` — url / username / password / 풀 설정 |
| `analytics/properties/ClickHouseProperties.java` | `@ConfigurationProperties(prefix="clickhouse")` record |
| `analytics/config/ClickHouseConfig.java` | `clickHouseJdbcTemplate` 빈 + `DisposableBean` |
| `common/config/JdbcTemplateConfig.java` | `@Primary` MySQL `JdbcTemplate` 복구 |
| `api/lombok.config` | `lombok.copyableAnnotations += @Qualifier` |

이벤트를 **넣는** 쪽은 `analytics` 가 아니라 `event` 도메인이다. ClickHouse 를 직접 쓰지 않고 Kafka 로만 보낸다.

| 파일 | 내용 |
| --- | --- |
| `api/build.gradle` | `implementation 'org.springframework.kafka:spring-kafka'` |
| `application.yaml` 의 `event.*` | 부트스트랩 주소 · 토픽 · 배치 상한 · 멱등 TTL · `occurred_at` 허용 창 |
| `event/config/EventKafkaConfig.java` | `ProducerFactory` / `KafkaTemplate` 를 직접 등록. `spring.kafka.*` 자동설정은 쓰지 않는다 |
| `event/service/EventServiceImpl.java` | 검증·정규화·멱등 처리 후 발행 |
| `event/dto/ListenEventMessage.java` | `kafka_listen_events` 컬럼과 1:1 인 JSON |

`ApiApplication` 에 `@ConfigurationPropertiesScan` 이 있어 `ClickHouseProperties` 는 자동 등록된다. 값은 `application.yaml` 규칙대로 `${MUPID_...}` 로만 참조하며, `clickhouse.url` 은 **기본값이 없다** — `MUPID_CLICKHOUSE_URL` 이 없으면 기동 자체가 실패한다.

ClickHouse 는 JPA 로 다루지 않는다. 엔티티/트랜잭션 모델이 맞지 않고 쓰는 쿼리가 집계와 대량 INSERT 뿐이라 `JdbcTemplate` 으로 충분하다는 판단이다. 드라이버는 문자열(`"com.clickhouse.jdbc.ClickHouseDriver"`)로만 참조하므로 mysql 과 같이 `runtimeOnly` 다.

### 커넥션 풀을 빈으로 등록하지 않는다

`ClickHouseConfig` 는 `HikariDataSource` 를 **필드로만** 들고 `JdbcTemplate` 하나만 `@Bean` 으로 내보낸다. 클래스 주석이 든 이유 두 가지:

1. `DataSource` 빈이 둘이 되면 `DataSourceAutoConfiguration` 의 `@ConditionalOnMissingBean(DataSource.class)` 가 물러나 JPA/Batch 용 MySQL DataSource 를 직접 재현해야 한다.
2. `DataSource` 빈마다 헬스 컨트리뷰터가 붙어 `/api/actuator/health` 가 ClickHouse 까지 집계한다. 그러면 분석 DB 가 죽었을 때 API 컨테이너가 `unhealthy` → nginx 의 `depends_on: service_healthy` → 서비스 전체 다운이다.

`infra/deploy/compose.yml` 이 `api` 에 분석 스택 `depends_on` 을 걸지 않은 것도 주석에서 같은 논리로 연결된다.

풀 생성 방식에도 같은 의도가 이어진다. `createDataSource()` 는 **기본 생성자 + setter** 로 만든다 — `HikariConfig` 를 넘기는 생성자는 풀을 즉시 띄워 ClickHouse 가 없으면 애플리케이션 기동이 실패하지만, 이 방식은 첫 `getConnection()` 때 지연 초기화된다. 정리는 `DisposableBean.destroy()`. `setAutoCommit(true)` 는 "ClickHouse 에는 우리가 쓰는 형태의 트랜잭션이 없다"는 이유다.

### 주입 방법

```java
@Service
@RequiredArgsConstructor
public class SectionStatsService {

    @Qualifier("clickHouseJdbcTemplate")
    private final JdbcTemplate clickHouse;
}
```

MySQL `JdbcTemplate` 이 `@Primary` 이므로 **`@Qualifier` 를 빠뜨리면 조용히 MySQL 이 주입된다.** 컴파일도 기동도 된다. `@RequiredArgsConstructor` 생성자로 `@Qualifier` 가 전달되려면 `api/lombok.config` 가 필요하다.

이 템플릿을 쓰는 애플리케이션 코드는 아직 없다. `analytics` 패키지에는 위 두 파일뿐이다.

## 스키마·설정을 바꿀 때

**DDL 원본을 손대지 않는다**가 이 영역 전반의 제약이다. `kafka_broker_list` 를 위한 alias/`extra_hosts` 도, `apply-ddl.sh` 가 `default` 데이터베이스를 쓰는 것도(`dict_track_sections` 의 `SOURCE(CLICKHOUSE(TABLE 'v_track_sections_current'))` 에 DB 절이 없어 사전 로딩 시 `default` 에서 뷰를 찾는다) 전부 그 결과다.

1. **DDL 은 `IF NOT EXISTS` / `OR REPLACE` 만 쓴다.** 여러 번 실행해도 안전해야 한다.
2. 그래서 **재적용의 효과가 오브젝트마다 다르다.**

   | DDL 형태 | 대상 | 재적용하면 |
   | --- | --- | --- |
   | `CREATE OR REPLACE VIEW` | `v_track_sections_current`, `v_track_skips` | 새 정의로 교체된다 |
   | `CREATE TABLE IF NOT EXISTS` | 모든 테이블 (Replicated / Distributed / Kafka) | **이미 있으면 아무 일도 안 일어난다** |
   | `CREATE MATERIALIZED VIEW IF NOT EXISTS` | `mv_kafka_to_events`, `mv_user_section_signals`, `mv_play_section_coverage` | 동일 |
   | `CREATE DICTIONARY IF NOT EXISTS` | `dict_track_sections` | 동일 |

3. 테이블·MV·사전의 정의를 실제로 바꾸려면 재적용만으로 부족하다. 로컬은 분석 스택 컨테이너와 `ch1/ch2/ch-keeper` 볼륨만 지우고 다시 올린다 — `down -v` 는 MySQL·Redis 까지 날린다. 지우지 않고 DDL 만 검증하려면 Keeper 경로를 바꾼 사본을 임시 데이터베이스에 적용해 본다.
4. 오브젝트를 추가/삭제했으면 **`verify.sh` 2단계의 기대값 `5/5/5/1/1` 을 함께 고친다.**
5. 새 이벤트 테이블을 만들면 `PARTITION BY toYYYYMM(...)` + `TTL toDateTime(...) + INTERVAL 18 MONTH` + `DateTime64(3, 'Asia/Seoul')` 규칙과 `_local`/Distributed 짝 규칙을 따른다.
6. 클러스터를 고를 때: `dictGet` 소스가 되거나 전 노드가 전체를 알아야 하면 `mupid_all` + `{shard}` 없는 Keeper 경로, 그 외 이벤트성이면 `mupid` + `{shard}` 있는 경로.
7. 설정 XML 을 추가하면 **서버 레벨(`config.d`)인지 쿼리 레벨(`users.d`)인지부터** 확인하고, compose 의 `volumes` 에 **파일 단위로** 한 줄 추가한다(디렉터리째 마운트 금지). 로컬 `api/compose.yml` 노드 2곳과 운영 `infra/deploy/compose.yml` 을 함께 고친다.
8. 새 환경변수는 ClickHouse 노드 정체성만 `CH_` 접두사를, 나머지는 `MUPID_` 접두사를 쓴다. `api/.env.example` 과 `infra/deploy/.env.example` 을 함께 갱신한다.
9. 셸 스크립트는 `#!/usr/bin/env bash` + `git ls-files -s` 기준 `100755` 로 커밋한다.
10. 마지막에 `docker compose up -d --wait` → `cd api && ./clickhouse/verify.sh` 로 28개 항목이 전부 PASS 인지 확인한다.

## 이 영역의 함정

상세는 [함정과 실패 사례](../failure/clickhouse-and-kafka.md) 에 있다. 여기서는 목록만 둔다.

- `api/clickhouse/README.md` 의 "운영 배포 파일에는 ClickHouse / Kafka 서비스가 아직 없다" 는 서술이 `infra/deploy/compose.yml` 의 현재 내용과 어긋난다.
- 쿼리 레벨 설정을 `config.d` 에 두면 서버가 기동하지 않는다.
- 설정 디렉터리를 통째로 마운트하면 이미지 기본 설정이 가려진다.
- `default` 계정으로는 노드 간 통신이 안 되고, `CLICKHOUSE_USER` 환경변수를 쓰면 복제가 깨진다.
- `v_track_skips` 는 단일 샤드에서 테스트하면 통과하고, 컬럼 이름에 테이블 별칭이 남는다.
- 운영 compose 는 `CH_SHARD`/`CH_REPLICA` 가 비어도 통과하고 복제가 조용히 깨진다. `extra_hosts` 의 `kafka2` 에는 기본값이 없다.
- Kafka 적재 경로는 `cityHash64(user_id)` 샤딩을 타지 않는다.
- 사전에 반영되기 전(`LIFETIME(MIN 300 MAX 600)`)에 도착한 이벤트는 조용히 버려진다.
- Kafka 재소비 시 `listen_events` 는 중복이 정리되지만 `user_section_signals` / `play_section_coverage` 는 아니다. 중복 제거는 Kafka 에 넣기 전에 끝내야 한다 → [decision/0014](../decision/0014-event-collector-in-api.md).
- `@Qualifier` 를 빠뜨리면 MySQL `JdbcTemplate` 이 주입된다. `HikariDataSource` 를 빈으로 노출하면 ClickHouse 장애가 서비스 전체 장애가 된다.
- Git Bash 에서 `docker exec` 에 컨테이너 절대경로를 넘기면 Windows 경로로 변환된다. `docker exec -i` + `clickhouse-client` INSERT 는 멈춘다.
- `docker compose up -d` 는 초기화 컨테이너 종료를 기다리지 않는다. 서비스 이름을 바꾸면 orphan 이 포트를 점유한다.
