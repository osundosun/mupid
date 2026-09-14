# ClickHouse 로컬 클러스터

`clickhouse/init/01-schema.sql` 의 DDL 을 **수정 없이** 그대로 올릴 수 있는 로컬 환경이다.
DDL 이 `ON CLUSTER`, `Replicated*MergeTree`, `Distributed`, `Kafka` 엔진, `Dictionary` 를
모두 쓰기 때문에 단일 노드로는 재현되지 않아 실제 클러스터를 구성했다.

## 구성

| 컨테이너 | 역할 | 호스트 포트 |
| --- | --- | --- |
| `mupid-dev-ch-keeper` | ClickHouse Keeper. 복제 메타데이터 + `ON CLUSTER` DDL 큐 | — |
| `mupid-dev-ch-01` | 서버. `shard=01`, `replica=ch1` | 18123(HTTP), 19000(native) |
| `mupid-dev-ch-02` | 서버. `shard=02`, `replica=ch2` | 18124(HTTP), 19001(native) |
| `mupid-dev-kafka` | KRaft broker+controller. alias `kafka1`, `kafka2` | 19092 |
| `mupid-dev-kafka-init` | 1회성. 토픽 생성 후 종료 | — |
| `mupid-dev-ch-init` | 1회성. DDL 적용 후 종료 | — |

`*-init` 컨테이너는 `Exited (0)` 이 정상이다.

### 두 개의 클러스터

같은 두 노드를 서로 다른 토폴로지로 정의한다. DDL 의 설계를 그대로 따른 것이다.

- **`mupid`** — 2 샤드 × 1 레플리카. `cityHash64(user_id)` 로 분산.
  Keeper 경로에 `{shard}` 가 들어가 샤드별로 데이터가 분리된다.
  (`listen_events`, `user_section_signals`, `play_section_coverage`, `rec_impressions`)
- **`mupid_all`** — 1 샤드 × 2 레플리카. 전 노드에 같은 데이터를 복제.
  `track_sections` 의 Keeper 경로에는 `{shard}` 가 없어 두 노드가 서로의 레플리카가 된다.
  `mv_user_section_signals` 가 **각 노드에서** `dictGet` 을 호출하므로 모든 노드가
  전체 구간 데이터를 갖고 있어야 하기 때문이다. (`track_sections`, `dict_track_sections`)

## 실행

`.env` 에 `MUPID_CLICKHOUSE_PASSWORD` 가 필요하다 (없으면 compose 가 즉시 실패한다).

```bash
docker compose up -d
```

`up -d` 는 1회성 컨테이너가 **끝나는** 것까지 기다리지 않는다. 즉 명령이 돌아온 뒤에도
`ch-init` 이 아직 DDL 을 적용하는 중일 수 있다. 스키마 적용까지 기다리려면:

```bash
docker compose up -d --wait
```

또는 `docker compose logs -f clickhouse-init` 으로 확인한다.
(`verify.sh` 는 `ch-init` 이 exited 가 될 때까지 알아서 기다리므로 그냥 실행해도 된다.)

DDL 만 다시 적용하려면 (DDL 은 `IF NOT EXISTS` / `OR REPLACE` 뿐이라 여러 번 실행해도 안전하다):

```bash
docker compose up --force-recreate clickhouse-init
```

전부 초기화하려면:

```bash
docker compose down -v --remove-orphans
```

> ⚠ `-v` 는 이 프로젝트의 **모든** 볼륨을 지운다. 분석 스택뿐 아니라
> `mupid-dev-mysql-data`·`redis-data`·`storage-data` 까지 사라져 로컬 계정과
> 업로드 파일이 날아간다. 스키마만 다시 적용하려는 것이라면 분석 스택만 지운다.
>
> ```bash
> docker compose rm -sfv clickhouse-01 clickhouse-02 clickhouse-keeper kafka kafka-init clickhouse-init
> docker volume rm mupid-dev-ch1-data mupid-dev-ch2-data mupid-dev-ch-keeper-data
> ```

`--remove-orphans` 를 붙이는 이유: compose 는 **현재 파일에 정의된 서비스만** 정리한다.
서비스 이름이 바뀌면(예: `kafka-1`/`kafka-2` → `kafka`) 예전 컨테이너가 orphan 으로
남아 포트를 계속 잡고 있어서, 새 컨테이너가 `port is already allocated` 로 뜨지 못한다.

## 접속

```bash
# 컨테이너 안에서
docker exec -it mupid-dev-ch-01 clickhouse-client --user mupid --password "$MUPID_CLICKHOUSE_PASSWORD"

# 호스트에서 HTTP
curl 'http://localhost:18123/?query=SELECT%20version()' \
  -H "X-ClickHouse-User: mupid" -H "X-ClickHouse-Key: $MUPID_CLICKHOUSE_PASSWORD"
```

스키마는 `default` 데이터베이스에 만든다. `dict_track_sections` 의 소스가
`SOURCE(CLICKHOUSE(TABLE 'v_track_sections_current'))` 처럼 DB 를 명시하지 않아,
사전 로딩 시 `default` 에서 뷰를 찾기 때문이다. 별도 DB 로 옮기려면 DDL 에 `DB` 절을
추가해야 한다.

## 파이프라인 확인

```bash
cd api && ./clickhouse/verify.sh
```

고정 ID 로만 데이터를 넣으므로 여러 번 실행해도 결과가 같다. 28개 항목을 검사한다.

| # | 검사 |
| --- | --- |
| 0 | `kafka-init` / `ch-init` 이 exit 0 으로 끝났는지 |
| 1 | `mupid` 2 샤드, `mupid_all` 1 샤드 2 레플리카 |
| 2 | 두 노드의 오브젝트 구성이 동일한지 (Replicated 5 / Distributed 5 / View 5 / Kafka 1 / Dictionary 1) |
| 3 | `system.distributed_ddl_queue` 에 실패 항목이 없는지 |
| 4 | `track_sections` 한 번 INSERT 가 두 노드에 모두 복제되는지 |
| 5 | `dict_track_sections` 가 두 노드에서 같은 `dictGet` 결과를 주는지 |
| 6 | Kafka → `mv_kafka_to_events` → `listen_events`, `occurred_at` 시간대 파싱 |
| 7 | `mv_user_section_signals` 의 구간 매핑과 가중치 (`LIKE` 1.0, `DISLIKE` -1.0 등) |
| 8 | `mv_play_section_coverage` 의 구간 경계 분할, `rec_impressions` 의 `Array` / `Map` 왕복 |
| 9 | `v_track_skips` 의 `skip_type` / `weight` 계산 |

## 눈으로 확인하기 (UI)

| 대상 | 주소 | 비고 |
| --- | --- | --- |
| ClickHouse 쿼리 콘솔 | http://localhost:18123/play | **내장**. 별도 컨테이너 불필요 |
| ClickHouse 모니터링 | http://localhost:18123/dashboard | 내장. QPS·메모리 그래프 |
| ch-02 (샤드 2) 콘솔 | http://localhost:18124/play | 샤드별로 직접 볼 때 |
| Kafka | http://localhost:18080 | `docker compose up -d kafka-ui` 로 띄운다 |

`/play` 는 우측 상단에 계정 입력란이 있다. **user `mupid`**, password 는 `.env` 의
`MUPID_CLICKHOUSE_PASSWORD` 값을 넣는다. Kafka UI 는 인증이 없다.

Kafka UI 는 `ui` profile 이라 `docker compose up -d` 로는 뜨지 않는다. 이름을 직접
지정하면 profile 이 자동으로 켜진다.

## 앱에서 실제로 발생시킨 이벤트 확인하기

앱을 띄워 놓고 곡을 듣거나 좋아요를 누른 뒤 http://localhost:18123/play 에서 아래를
차례로 던진다. 프론트 큐가 5초마다, 20건마다 보내고 ClickHouse 가 최대 15초 모았다
쓰므로 **누른 직후가 아니라 20초쯤 뒤에** 보인다.

> ⚠ **반드시 Distributed 테이블(`listen_events`)에 쿼리한다.** `listen_events_local`
> 은 접속한 노드에 앉은 절반만 보여준다. 게다가 Kafka 적재 경로는 샤딩 키를 타지
> 않아 한 사용자의 이벤트가 두 샤드에 흩어진다.

```sql
-- 1) 방금 들어온 것부터 본다. 이것만으로 "적재됐는가" 는 판정된다.
SELECT occurred_at, ingested_at, event_type, track_id, position_ms, from_ms, entry_path
FROM listen_events
ORDER BY ingested_at DESC
LIMIT 50;

-- 2) 어떤 이벤트가 몇 건씩 들어왔나. 22종 중 무엇이 안 나가는지 여기서 드러난다.
SELECT event_type, count() AS cnt, max(occurred_at) AS last_seen
FROM listen_events
WHERE occurred_at >= now() - INTERVAL 1 HOUR
GROUP BY event_type
ORDER BY cnt DESC;

-- 3) 재생 하나를 통째로 따라간다. play_id 는 PLAY_START 가 발급한 값이다.
SELECT occurred_at, event_type, position_ms, from_ms, skip_trigger, target
FROM listen_events
WHERE play_id = '여기에-play_id'
ORDER BY occurred_at;

-- 4) 구간 신호가 만들어졌는가. 이벤트는 들어왔는데 여기가 비면
--    track_id = 0 이거나 position_ms = 0 이거나 구간 매핑에 실패한 것이다.
SELECT user_id, section_uid, event_type, weight, occurred_at
FROM user_section_signals
ORDER BY occurred_at DESC
LIMIT 50;

-- 5) 커버리지. PLAY_PROGRESS 하트비트가 구간별로 쪼개져 쌓인다.
--    SummingMergeTree 부분합이라 반드시 GROUP BY 로 합산해서 읽는다.
SELECT user_id, play_id, section_uid, sum(listened_ms) AS listened_ms
FROM play_section_coverage
GROUP BY user_id, play_id, section_uid
ORDER BY max(play_id) DESC
LIMIT 50;
```

터미널에서 보고 싶으면 같은 쿼리를 이렇게 던진다.

```bash
docker exec mupid-dev-ch-01 clickhouse-client --user mupid --password "$MUPID_CLICKHOUSE_PASSWORD" \
  --query "SELECT event_type, count() FROM listen_events GROUP BY event_type ORDER BY 2 DESC"
```

### 안 보일 때 어디를 보나

| 증상 | 볼 곳 |
| --- | --- |
| `listen_events` 가 통째로 비었다 | Kafka UI(18080)에서 `mupid.listen.events` 토픽에 메시지가 있는지. 없으면 문제는 API 이전이다 |
| 토픽에는 있는데 테이블이 비었다 | `SELECT * FROM system.kafka_consumers` 의 `exceptions` · `last_poll_time` |
| API 가 이벤트를 안 받는다 | `POST /api/v1/events` 응답의 `accepted`/`duplicated`/`rejected`. `rejected` 가 있으면 값 검증에서 걸린 것이고 서버 로그에 남는다 |
| 브라우저에서 요청 자체가 안 나간다 | 비로그인이면 401 이라 큐에 쌓여만 있다. 로그인 뒤 다음 배치에 함께 나간다 |
| `listen_events` 는 차는데 `user_section_signals` 가 빈다 | 그 곡의 구간이 `track_sections` 에 등록됐는지. 매핑이 실패하면 이벤트만 남고 신호는 안 생긴다 |

## 손으로 데이터 흐름 확인하기

Kafka 에 이벤트를 하나 넣고 ClickHouse 두 테이블에 자동으로 도착하는지 보는 절차다.

```bash
cd api
export MSYS_NO_PATHCONV=1   # Git Bash 에서만 필요 (PowerShell 은 불필요)
P=$(grep '^MUPID_CLICKHOUSE_PASSWORD=' .env | cut -d= -f2- | tr -d '\r')
# docker exec 에 -i 를 붙이지 말 것. clickhouse-client 는 INSERT 일 때 추가 데이터를
# stdin 에서 읽으려 해서, 터미널에서 실행하면 EOF 가 안 와 그대로 멈춘다.
ch() { docker exec mupid-dev-ch-01 clickhouse-client --user mupid --password "$P" --query "$1"; }

# 1) 트랙 구간 등록 — 이게 있어야 이벤트가 어느 구간인지 판정된다
ch "INSERT INTO track_sections
      (track_id, analysis_version, section_index, section_uid, fn, start_ms, end_ms, peak_ms)
    SETTINGS distributed_foreground_insert = 1
    VALUES (1001,'v1',1,'s1001-intro','INTRO',0,30000,15000)"
ch "SYSTEM RELOAD DICTIONARY default.dict_track_sections"

# 2) Kafka 로 이벤트 1건 발행
echo '{"event_id":"aaaa0001-0000-4000-8000-000000000001","event_type":"LIKE","user_id":1234,"track_id":1001,"occurred_at":"2026-09-10 12:00:00.000","play_id":"aaaa0001-0000-4000-8000-0000000000ff","entry_path":"RECOMMEND","position_ms":15000}' \
  | docker exec -i mupid-dev-kafka /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server localhost:9092 --topic mupid.listen.events

# 3) 원본 이벤트 도착 확인 (Kafka 엔진 flush 에 몇 초 걸린다)
ch "SELECT event_type, user_id, track_id, position_ms, occurred_at
    FROM listen_events WHERE user_id = 1234 FORMAT PrettyCompactMonoBlock"

# 4) 파생 시그널이 자동 생성됐는지 (mv_user_section_signals + dictGet)
ch "SELECT user_id, signal, section_uid, weight
    FROM user_section_signals WHERE user_id = 1234 FORMAT PrettyCompactMonoBlock"

# 5) 어느 샤드에 앉았는지
ch "SELECT hostName() AS node, count() FROM listen_events WHERE user_id = 1234
    GROUP BY node FORMAT PrettyCompactMonoBlock"
```

4번에 행이 나오면 `Kafka → mv_kafka_to_events → listen_events_local →
mv_user_section_signals → user_section_signals_local` 연쇄가 전부 동작한 것이다.

되돌리려면 `docker compose down -v --remove-orphans` 후 다시 올린다.
(`verify.sh` 는 자기가 넣은 고정 ID 만 세므로 위 수동 데이터가 남아 있어도 통과한다.)

## 헬스체크 / 테스트 현황

| 대상 | 로컬 `compose.yml` | 운영 `infra/deploy/compose.yml` | CI |
| --- | --- | --- | --- |
| `clickhouse-01` / `02` | ✅ `clickhouse-client --query "SELECT 1"` | ❌ 서비스 자체가 없음 | ❌ |
| `clickhouse-keeper` | ✅ `ruok` → `imok` | ❌ | ❌ |
| `kafka` | ✅ `kafka-broker-api-versions.sh` | ❌ | ❌ |
| `storage` | ✅ `8333/status` | ❌ | ❌ |
| 파이프라인 전체 | `verify.sh` 수동 실행 | — | ❌ |

**운영 배포 파일에는 ClickHouse / Kafka 서비스가 아직 없다.** `infra/deploy/compose.yml`
은 nginx / api / mysql / redis 만 띄운다. 배포 파이프라인 연결은 별도 작업이다.

**CI 는 `verify.sh` 를 돌리지 않는다.** `infra/ci/backend.yml` 이 "Build-only CI by team
decision" 으로 `build -x test` 만 실행한다. 스키마가 깨져도 CI 는 통과한다.

Spring 의 `/api/actuator/health` 는 **의도적으로 ClickHouse 를 포함하지 않는다.**
(아래 "커넥션 풀을 빈으로 등록하지 않은 이유" 참고)

## 알아둘 것

### 공식 이미지가 `default` 계정을 localhost 로 묶는다

엔트리포인트가 `users.d/default-user.xml` 을 만들어 `default` 를 `127.0.0.1` / `::1` 로
제한한다. 그래서 노드 간 통신에 `default` 를 쓸 수 없고, `remote_servers` 의 각
`replica` 에 앱 계정(`mupid`)을 명시했다. 이 파일은 이름 순서상 `10-mupid-user.xml`
보다 나중에 병합되어 덮어쓸 수도 없다. 헬스체크만 `127.0.0.1` 로 `default` 를 쓴다.

`CLICKHOUSE_USER` / `CLICKHOUSE_PASSWORD` 환경변수는 쓰지 않는다. 그 방식은
엔트리포인트가 `default` 를 `remove="remove"` 로 **삭제**해서 복제가 깨진다.

### 서버 설정과 쿼리 설정은 파일이 다르다

`distributed_ddl_task_timeout`, `distributed_product_mode` 같은 쿼리(user) 레벨 설정을
`config.d` 최상위에 두면 `UNKNOWN_ELEMENT_IN_CONFIG` 로 **기동이 실패한다**.
`users.d` 의 `<profiles><default>` 안에 넣어야 한다. `config.d` 에는 서버 레벨 설정만.

또한 ClickHouse 는 `config.d` **하위 디렉터리를 재귀 탐색하지 않는다**. 그래서
compose 에서 디렉터리가 아니라 XML 파일 단위로 마운트한다.

### `v_track_skips` 는 `distributed_product_mode=global` 이 필요하다

이 뷰는 Distributed 테이블(`listen_events`)에 Distributed 서브쿼리
(`r`: `play_section_coverage` 를 재생 단위로 합산한 것)를 JOIN 한다.
기본값 `deny` 에서는 `DISTRIBUTED_IN_JOIN_SUBQUERY_DENIED` 로 실패한다.
서브쿼리를 `_local` 로 바꾸면 샤드 하나만 보게 되어 결과가 **틀리므로**,
`users.d` 에서 `global` 로 설정해 이니시에이터에서 계산한 뒤 각 샤드에 broadcast 한다.
서브쿼리 결과가 커지면 broadcast 비용이 문제가 될 수 있어 규모가 커지면 재설계가 필요하다.

### `v_track_skips` 컬럼 이름에 테이블 별칭이 붙는다

DDL 이 `SELECT s.user_id, ...` 처럼 별칭만 쓰고 `AS` 를 생략해서, 조인 대상 간에
이름이 겹치는 컬럼은 별칭이 그대로 컬럼명에 남는다.

```
event_id, occurred_at, position_ms, skip_trigger   -- s 에만 있어 깔끔
s.user_id, s.track_id, s.play_id                   -- 겹쳐서 별칭이 남음
entry_path                                         -- AS 를 붙여 별칭을 떼어냈다
sec.section_uid                                    -- 같은 이유
```

따라서 `SELECT user_id FROM v_track_skips` 는 실패하고 `` SELECT `s.user_id` `` 로 써야 한다.
DDL 에 `s.user_id AS user_id` 처럼 별칭을 붙이면 해결된다.

### Git Bash 에서 `docker exec` 에 컨테이너 경로를 넘길 때

Git Bash(MSYS)는 `/` 로 시작하는 인자를 Windows 경로로 바꿔버린다.

```
$ docker exec mupid-dev-kafka /opt/kafka/bin/kafka-topics.sh --list
exec: "C:/Program Files/Git/opt/kafka/bin/kafka-topics.sh": no such file or directory
```

`apache/kafka` 이미지는 kafka 툴을 `PATH` 에 넣어주지 않아 절대경로를 써야 하므로,
`verify.sh` 는 `MSYS_NO_PATHCONV=1` / `MSYS2_ARG_CONV_EXCL='*'` 를 설정한다.
PowerShell 에서는 이 문제가 없다.

### Kafka 브로커는 1대다

브로커 개수는 프로듀서(Spring/FastAPI) 대수나 ClickHouse 노드 수와 **무관하다.**
둘 다 네트워크 클라이언트일 뿐이라 브로커 한 대에 모두 붙는다.
2대는 오히려 애매하다 — KRaft 컨트롤러 쿼럼이 2면 둘 다 살아있어야 해서 장애를
한 대도 못 견딘다. 제대로 된 HA 는 3대부터라 2대는 리소스만 두 배 쓴다.

DDL 의 `kafka_broker_list = 'kafka1:9092,kafka2:9092'` 는 "브로커가 2대여야 한다"가
아니라 **클러스터를 찾기 위해 처음 접속해볼 부트스트랩 주소 목록**이다. 그래서 한 대에
`kafka1` / `kafka2` 두 네트워크 alias 를 붙여 DDL 을 수정하지 않고 쓴다. 클라이언트는
메타데이터를 받은 뒤 broker id 로 중복을 제거하므로 실제로는 한 대로 인식한다.

파티션 수(6)는 브로커 수와 무관하다. 브로커가 1대여도 6개가 다 생기고 ClickHouse
컨슈머 6개(노드당 `kafka_num_consumers=3` × 2노드)가 하나씩 나눠 갖는다.
다만 브로커가 1대이므로 replication factor 는 1이 최대다.

### Kafka 경로는 `cityHash64(user_id)` 샤딩을 타지 않는다

`kafka_listen_events` 는 `ON CLUSTER mupid` 로 두 노드에 각각 생기고
(`kafka_num_consumers = 3` × 2노드 = 컨슈머 6개, 그래서 토픽 파티션도 6개),
각 노드의 `mv_kafka_to_events` 가 **자기 노드의** `listen_events_local` 에 넣는다.
즉 행이 어느 샤드에 앉는지는 파티션 배정이 결정하며 `user_id` 해시와 무관하다.

Distributed 테이블로 읽으면 전 샤드를 조회하므로 **결과는 정확하다**. 다만 사용자
단위 지역성이 없다. 지역성이 필요하면 `mv_kafka_to_events` 의 대상을
`listen_events_local` 대신 Distributed 테이블 `listen_events` 로 바꾸면 되지만
노드 간 쓰기가 한 번 더 생긴다.

## Spring 연동

| 파일 | 내용 |
| --- | --- |
| `build.gradle` | `runtimeOnly 'com.clickhouse:clickhouse-jdbc:0.9.0'` |
| `application.yaml` | `clickhouse.*` (url / username / password / 풀 설정) |
| `analytics/properties/ClickHouseProperties` | `@ConfigurationProperties(prefix="clickhouse")` |
| `analytics/config/ClickHouseConfig` | `clickHouseJdbcTemplate` 빈 |
| `common/config/JdbcTemplateConfig` | MySQL 용 `@Primary JdbcTemplate` 복구 |

ClickHouse 는 JPA 로 다루지 않는다. 엔티티/트랜잭션 모델이 맞지 않고, 쓰는 쿼리가
집계와 대량 INSERT 뿐이라 `JdbcTemplate` 으로 충분하다.

### 커넥션 풀을 빈으로 등록하지 않은 이유

`HikariDataSource` 를 `@Bean` 으로 노출하면 `DataSource` 타입 빈이 두 개가 되면서
두 가지 부작용이 생긴다.

1. `DataSourceAutoConfiguration` 이 `@ConditionalOnMissingBean(DataSource.class)` 로
   물러나서 JPA/Batch 가 쓰는 MySQL DataSource 를 직접 재현해야 한다.
2. **`DataSource` 빈마다 헬스 컨트리뷰터가 자동으로 붙는다.** 그러면
   `/api/actuator/health` 가 ClickHouse 상태까지 집계해서, 분석 DB 가 죽으면
   API 컨테이너가 `unhealthy` 가 되고 nginx 의 `depends_on: service_healthy` 에 걸려
   서비스 전체가 내려간다. 인증/회원 기능은 ClickHouse 와 무관하므로 과한 결합이다.

그래서 풀은 `ClickHouseConfig` 가 직접 들고 있다가 `DisposableBean` 으로 닫고,
빈으로는 `clickHouseJdbcTemplate` 만 노출한다. 검증 결과 ClickHouse 두 노드를 모두
중단해도 `/api/actuator/health` 는 `UP` 을 유지한다.

ClickHouse 상태를 관측하고 싶으면 별도 헬스 그룹으로 분리해 컨테이너 헬스체크와
떼어놓거나, Grafana 쪽에서 직접 감시할 것.

### 주입 방법

MySQL `JdbcTemplate` 이 `@Primary` 이므로 **`@Qualifier` 가 없으면 MySQL 이 주입된다.**
컴파일도 되고 기동도 되지만 다른 DB 를 조회하게 되니 주의할 것.

```java
@Service
@RequiredArgsConstructor
public class SectionStatsService {

    @Qualifier("clickHouseJdbcTemplate")
    private final JdbcTemplate clickHouse;

    public long countSignals(long userId) {
        return clickHouse.queryForObject(
            "SELECT count() FROM user_section_signals WHERE user_id = ?",
            Long.class, userId);
    }
}
```

`@RequiredArgsConstructor` 가 생성하는 생성자로 `@Qualifier` 가 전달되도록
`api/lombok.config` 에 `lombok.copyableAnnotations` 를 설정해 두었다. 이 파일이 없으면
`@Qualifier` 가 조용히 무시된다.

아직 이 템플릿을 사용하는 애플리케이션 코드는 없다. 연결과 조회는 확인했고
(Distributed 테이블 조회, `dictGet` 포함) 실제 사용은 다음 작업에서 붙이면 된다.

## 함께 고친 것: `storage`(SeaweedFS)

ClickHouse 도입과는 무관하지만 같이 발견해서 고쳤다. 두 가지 문제가 겹쳐 있었다.

### 1. 헬스체크가 인증이 필요한 경로를 호출했다

`GET /` 는 S3 의 ListBuckets 라서 자격증명이 필요하다. `AWS_ACCESS_KEY_ID` /
`AWS_SECRET_ACCESS_KEY` 가 설정되면 SeaweedFS 는 그 값으로 S3 관리자 identity 를
만들고, 그때부터 익명 요청은 403 이 된다.

```
$ docker exec mupid-dev-storage wget -qO- http://127.0.0.1:8333/
wget: server returned error: HTTP/1.1 403 Forbidden
```

그래서 컨테이너가 영구히 `unhealthy` 로 남았다. 인증 없이 200 을 주는 경로로 바꿨다.

| 엔드포인트 | 인증 없이 |
| --- | --- |
| `8333/` | 403 |
| `8333/status` | **200** ← 채택 |
| `8333/healthz` | 200 |
| `9333/cluster/status` | 200 (master. S3 게이트웨이는 안 봄) |

앱이 실제로 쓰는 S3 게이트웨이를 검사하도록 `8333/status` 를 골랐다.

### 2. S3 버킷이 만들어지지 않았다

`compose.yml` 이 `S3_BUCKET=${MUPID_S3_BUCKET}` 을 넘기지만 **SeaweedFS 는 이 변수를
읽지 않는다.** 버킷이 없으면 앱의 업로드가 `NoSuchBucket` 으로 실패한다.
`storage-init` 컨테이너를 추가해 기동 시 만들도록 했다.

```bash
# storage-init 이 실행하는 것과 동일
docker exec mupid-dev-storage sh -c \
  'echo "s3.bucket.create -name mupid" | weed shell -master=127.0.0.1:9333'

# 확인
docker exec mupid-dev-storage sh -c \
  'echo "s3.bucket.list" | weed shell -master=127.0.0.1:9333'
```

`s3.bucket.create` 는 이미 있는 버킷에도 exit 0 이라 여러 번 실행해도 안전하다.
이미지의 ENTRYPOINT 가 `weed` 라서 셸을 쓰려면 entrypoint 를 덮어써야 한다.

## 로컬과 운영의 차이는 환경변수뿐

`clickhouse/server/*.xml` 과 `clickhouse/users/*.xml` 은 로컬(한 머신에 컨테이너 2개)과
운영(서버 2대)에서 **같은 파일을 그대로** 쓴다. 노드 정체성과 호스트를 `from_env` 로
받기 때문이다. 운영에서 바꿀 것은 아래 값들뿐이다.

| 환경변수 | 로컬 ch-01 | 로컬 ch-02 | 운영 서버 A / B |
| --- | --- | --- | --- |
| `CH_SHARD` | `01` | `02` | `01` / `02` |
| `CH_REPLICA` | `ch1` | `ch2` | `ch1` / `ch2` |
| `CH_INTERSERVER_HOST` | `mupid-dev-ch-01` | `mupid-dev-ch-02` | 각 서버 주소 |
| `CH_HOST_1` / `CH_HOST_2` | 컨테이너 이름 | 동일 | 서버 A / B 주소 |
| `CH_KEEPER_HOST` | `mupid-dev-ch-keeper` | 동일 | Keeper 주소 |
| `CH_MAX_SERVER_MEMORY` | `4294967296` (4GiB) | 동일 | `0` = 자동 |
| `MUPID_CLICKHOUSE_PASSWORD` | `.env` | 동일 | 시크릿 |

`CH_SHARD` / `CH_REPLICA` 가 비면 Keeper 경로가 겹쳐 복제가 조용히 깨지므로
compose 에서 필수 값으로 받는다.

### 운영에서 추가로 손봐야 하는 것

- **Keeper.** `clickhouse/keeper/keeper_config.xml` 은 단일 노드용이다. 쿼럼이 없어
  장애를 못 견디므로 운영은 3 노드 이상(홀수)으로 `raft_configuration` 을 다시 써야 한다.
  노드 개수가 가변이라 환경변수로는 만들 수 없어 이 파일만 환경별로 따로 둔다.
- **비밀번호.** 지금은 평문이다(`from_env`). 운영은 `password_sha256_hex` 와 시크릿 관리.
- **Kafka 데이터 볼륨.** 로컬은 일부러 안 붙였다. `docker compose down` 에서 사라지고,
  오프셋이 초기화돼 재소비가 일어나도 `listen_events` 가 ReplacingMergeTree 라
  `(user_id, occurred_at, event_id)` 기준으로 중복이 정리된다.
