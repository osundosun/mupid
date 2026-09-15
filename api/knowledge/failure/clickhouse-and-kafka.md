# ClickHouse · Kafka

23건. 증상으로 찾고, **원인 / 해결 / 근거** 순으로 읽는다. 새로 밟은 함정은 이 파일에 같은 형식으로 추가한다.

[← 함정 목록](README.md) · [지식 저장소](../README.md)

---

## ClickHouse · Kafka

### 설정 XML 을 추가했더니 `UNKNOWN_ELEMENT_IN_CONFIG` 로 서버가 기동하지 않는다

- **원인**: `distributed_ddl_task_timeout`, `distributed_product_mode` 같은 **쿼리(user) 레벨** 설정을 `config.d` 최상위에 뒀다.
- **해결**: user 레벨 설정은 `users.d` 의 `<profiles><default>` 안에 넣는다. `config.d` 에는 서버 레벨만 둔다. 새 설정을 추가할 때 어느 쪽인지부터 확인한다.
- **근거**: `api/clickhouse/users/00-profile.xml` (파일 상단 주석)

### 설정 디렉터리를 마운트했더니 이미지 기본 설정이 사라진다

- **원인**: ClickHouse 는 `config.d` 하위 디렉터리를 **재귀 탐색하지 않는다.** 게다가 `config.d`/`users.d` 를 디렉터리째 마운트하면 이미지가 넣는 `docker_related_config.xml` 과 엔트리포인트가 쓰는 `users.d/default-user.xml` 이 가려져 사라진다.
- **해결**: XML 을 **파일 단위**로 `:ro` 마운트한다. 파일이 늘어나면 compose 의 `volumes` 목록에 한 줄씩 추가한다(`api/compose.yml` 은 노드마다 6줄, `infra/deploy/compose.yml` 도 동일).
- **근거**: `api/compose.yml`, `api/clickhouse/README.md`

### 노드 간 통신이 `AUTHENTICATION_FAILED` 로 죽는다

- **원인**: 공식 이미지 엔트리포인트가 `users.d/default-user.xml` 을 만들어 `default` 계정을 `127.0.0.1`/`::1` 로 묶는다. 이 파일은 이름 순서상 `10-mupid-user.xml` 보다 나중에 병합되어 덮어쓸 수도 없다. `remote_servers` 에 계정을 지정하지 않으면 노드 간 접속이 `default` 로 시도된다.
- **해결**: `server/00-cluster.xml` 의 각 `<replica>` 에 앱 계정(`mupid`)과 비밀번호를 명시한다. `default` 는 `--host 127.0.0.1` 컨테이너 헬스체크에서만 쓴다.
- **근거**: `api/clickhouse/server/00-cluster.xml`, `api/clickhouse/users/10-mupid-user.xml`

### `CLICKHOUSE_USER` / `CLICKHOUSE_PASSWORD` 환경변수로 계정을 만들었더니 복제가 깨진다

- **원인**: 이미지 엔트리포인트가 이 방식에서는 `default` 계정을 `remove="remove"` 로 **삭제**한다.
- **해결**: 환경변수 대신 `users.d/10-mupid-user.xml` 에 계정을 정의하고 비밀번호만 `from_env` 로 받는다.
- **근거**: `api/clickhouse/users/10-mupid-user.xml`

### `v_track_skips` 가 단일 노드 테스트는 통과하고 운영에서 터진다

- **원인**: 이 뷰는 Distributed 테이블 `listen_events` 에 Distributed 서브쿼리(`r`: `play_section_coverage` 를 재생 단위로 합산한 것)를 JOIN 한다. (`PLAY_START` 를 되짚어 `entry_path` 를 얻던 서브쿼리 `st` 는, `entry_path` 를 재생의 모든 이벤트에 싣기로 하면서 없앴다.) `distributed_product_mode` 기본값 `deny` 에서는 `DISTRIBUTED_IN_JOIN_SUBQUERY_DENIED` 로 실패하는데, **샤드가 1개면 로컬로 최적화돼 오류가 보이지 않는다.**
- **해결**: `users/00-profile.xml` 이 `global` 로 설정해 해결했다. 검증은 반드시 2샤드 구성에서 한다. 남은 한계: 서브쿼리가 이벤트 전체 이력을 훑으므로 데이터가 쌓이면 broadcast 비용이 커진다 — 규모가 커지면 뷰 재설계가 필요하다.
- **근거**: `api/clickhouse/users/00-profile.xml` (주석에 근거가 그대로 있다)

### 성능 때문에 서브쿼리를 `_local` 로 바꾸면 에러 없이 결과가 틀려진다

- **원인**: `_local` 로 바꾸면 샤드 하나만 본다. **Kafka 적재 경로가 `cityHash64(user_id)` 샤딩을 타지 않으므로** 다른 샤드의 `PLAY_START` 를 놓치고 `weight` 가 틀리게 계산된다.
- **해결**: 이 우회는 금지다. 이유(아래 항목)를 모르면 반드시 다시 저지르는 실수다.
- **근거**: `api/clickhouse/users/00-profile.xml`, `api/clickhouse/init/01-schema.sql`

### Kafka 로 들어온 행이 `user_id` 해시대로 앉지 않는다

- **원인**: `kafka_listen_events` 가 `ON CLUSTER mupid` 로 두 노드에 각각 생기고, 각 노드의 `mv_kafka_to_events` 가 **자기 노드의** `listen_events_local` 에 직접 넣는다. 행이 어느 샤드에 앉는지는 Kafka 파티션 배정이 결정하며 `user_id` 해시와 무관하다.
- **해결**: Distributed 테이블로 읽으면 결과 자체는 정확하다. 사용자 단위 지역성이 필요하면 MV 대상을 Distributed `listen_events` 로 바꿀 수 있지만 노드 간 쓰기가 한 번 더 생긴다. 이 성질이 `distributed_product_mode=global` 을 강제하는 원인이다.
- **근거**: `api/clickhouse/init/01-schema.sql`, `api/clickhouse/README.md`

### `SELECT user_id FROM v_track_skips` 가 실패한다

- **원인**: DDL 이 `SELECT s.event_id, s.user_id, …` 처럼 별칭만 쓰고 `AS` 를 생략해서, 조인 대상 간 이름이 겹치는 컬럼은 별칭이 컬럼명에 그대로 남는다. `event_id`, `occurred_at`, `position_ms`, `skip_trigger` 는 깔끔하지만 `s.user_id`, `s.track_id`, `s.play_id`, `sec.section_uid`, `st.entry_path` 는 그렇지 않다.
- **해결**: 지금은 백틱으로 `` `s.user_id` `` 라고 써야 한다. 근본 해결은 DDL 에 `s.user_id AS user_id` 처럼 별칭을 붙이는 것이다(`01-schema.sql` 184-194행 미수정).
- **근거**: `api/clickhouse/init/01-schema.sql:184-194`

### 새 `track_sections` 를 넣었는데 `user_section_signals` 가 계속 비어 있다

- **원인**: `mv_user_section_signals` 의 WHERE 절에 `dictGet('dict_track_sections','section_uid', toUInt64(track_id), position_ms) != ''` 가 있어 **사전에 없는 구간의 이벤트는 조용히 버려진다.** `dict_track_sections` 는 `LIFETIME(MIN 300 MAX 600)` 이라 새 행이 최대 10분간 보이지 않는다. 오류도 로그도 없다.
- **해결**: INSERT 직후 `SYSTEM RELOAD DICTIONARY default.dict_track_sections` 를 명시적으로 호출한다(README 수동 절차와 `verify.sh` 5단계가 그렇게 한다). 사전 소스가 `v_track_sections_current` 이므로 **`analysis_version = 'v1'` 이 아닌 행은 아예 사전에 들어오지 않는다**는 점도 함께 주의한다.
- **근거**: `api/clickhouse/init/01-schema.sql`, `api/clickhouse/verify.sh`

### 재생 수를 `uniqExact(play_id)` 로 셌더니 검색만 한 날에도 1 이 나온다

- **원인**: `SEARCH` 처럼 재생 밖에서 일어난 이벤트에는 `play_id` 가 없다. `mv_kafka_to_events` 의 `toUUIDOrZero` 가 빈 문자열을 **영 UUID(`00000000-0000-0000-0000-000000000000`)** 로 적재하므로, `uniqExact` 가 그것을 재생 1회로 센다. 오류가 아니라 **값이 조용히 1 커진다.**
- **해결**: `uniqExactIf(play_id, play_id != toUUID('00000000-0000-0000-0000-000000000000'))`. 취향 리포트의 재생 수가 이것 때문에 하루 30(실제 29)으로 나왔고, 그 값이 C축의 앞으로감기 빈도와 D축의 반복 강도에서 분모·분자로 쓰여 판정까지 흔들었다.
- **같은 함정**: `track_id` · `playlist_id` · `comment_id` 는 숫자라 없을 때 **0** 이다. 집계에서 세거나 나눌 때 `> 0` 으로 거른다. 기존 MV 들이 `track_id > 0` 을 붙여 둔 것이 그 이유다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/analytics/repository/ListenAnalyticsRepository.java`, `api/clickhouse/init/01-schema.sql` (`mv_kafka_to_events`)

### 집계 별칭을 컬럼명과 같게 뒀더니 `ILLEGAL_AGGREGATION` 으로 쿼리가 통째로 실패한다

- **원인**: `SELECT track_id, sum(ms) FROM (SELECT any(track_id) AS track_id, … GROUP BY event_id)` 처럼 서브쿼리의 집계 별칭이 컬럼명과 같으면, ClickHouse 가 바깥 조건을 서브쿼리 WHERE 로 밀어 넣으면서 그 별칭과 부딪힌다. `Code: 184. Aggregate function any(track_id) AS track_id is found in WHERE in query`.
- **증상**: 코드 리뷰로는 안 보인다. **실행해야 나온다** — SQL 자체는 멀쩡해 보인다.
- **해결**: 서브쿼리 집계 별칭을 컬럼명과 겹치지 않게 쓴다(`AS tid`). 바깥에서 `SELECT tid AS track_id` 로 이름을 되돌리면 응답 계약은 그대로다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/analytics/repository/ListenAnalyticsRepository.java`

### Kafka 재소비 후 시그널이 두 배로 늘어 있다

- **원인**: 로컬 `api/compose.yml` 이 Kafka 로그에 볼륨을 일부러 붙이지 않은 근거는 "오프셋이 초기화돼 재소비가 일어나도 `listen_events` 가 ReplacingMergeTree 라 중복이 정리된다" 인데, **이 설명은 `listen_events_local` 에만 해당한다.** `mv_user_section_signals` 는 들어오는 모든 INSERT 에 발화하고, 대상 `user_section_signals_local` 은 `ReplicatedMergeTree`(Replacing 아님)라 중복 시그널이 그대로 남는다. `mv_play_section_coverage` → `play_section_coverage_local`(SummingMergeTree) 도 같다 — 중복 하트비트의 `listened_ms` 가 그대로 더해진다. `rec_impressions_local` 도 마찬가지다.
- **해결**: 재소비가 일어났으면 파생 테이블을 따로 정리한다(`verify.sh` 가 6단계 앞에서 테스트 사용자의 커버리지를 지우는 것이 그 이유다). 근본 해결은 **수집기가 `event_id` 로 중복을 걸러 Kafka 에 넣지 않는 것**이다 → [decision/0014](../decision/0014-event-collector-in-api.md). 덧붙여 ReplacingMergeTree 의 중복 정리는 **병합 시점의 파트 단위**라 즉시 반영되지 않는다 — 정확한 카운트가 필요하면 `verify.sh` 처럼 `uniqExact()` 를 쓰거나 `FINAL` 을 붙인다.
- **근거**: `api/clickhouse/init/01-schema.sql`, `api/compose.yml`

### 스키마를 새로 적용하려고 `down -v` 했더니 로컬 계정과 업로드 파일까지 사라졌다

- **원인**: `-v` 는 그 compose 프로젝트의 **모든** 볼륨을 지운다. `api/compose.yml` 이 선언한 볼륨은 6개인데 분석 스택 것은 셋뿐이다(`ch1-data`, `ch2-data`, `ch-keeper-data`). 나머지 `mysql-data`·`redis-data`·`storage-data` 에는 회원·세션·업로드 파일이 들어 있다. 스키마는 `CREATE TABLE IF NOT EXISTS` 라 재적용으로 안 바뀌니 볼륨을 지워야 하는 것은 맞지만, 지울 범위가 과하다.
- **해결**: 분석 스택만 지운다. Kafka 는 볼륨이 없어 컨테이너만 지우면 로그도 함께 사라진다.

  ```bash
  docker compose rm -sfv clickhouse-01 clickhouse-02 clickhouse-keeper kafka kafka-init clickhouse-init
  docker volume rm mupid-dev-ch1-data mupid-dev-ch2-data mupid-dev-ch-keeper-data
  docker compose up -d --wait
  ```

- **DDL 만 검증할 거면 아무것도 지울 필요가 없다.** Keeper 경로(`/clickhouse/tables/`)를 다른 접두사로 바꾼 사본을 임시 데이터베이스에 `--queries-file` 로 적용하면, 엔진 인자·MV JOIN·뷰 SQL 이 받아들여지는지와 커버리지 분할이 맞는지까지 확인할 수 있다. 끝나면 그 데이터베이스만 `DROP DATABASE … ON CLUSTER` 한다. 다만 `verify.sh` 자체는 `default` 를 보므로 이 방법으로는 돌릴 수 없다.
- **근거**: `api/compose.yml` volumes 절 (선언 6개, 이름 `mupid-dev-*`)

### `docker compose up -d` 가 돌아왔는데 DDL 이 아직 적용 중이다

- **원인**: `up -d` 는 1회성 초기화 컨테이너(`mupid-dev-ch-init`)의 종료를 기다리지 않는다. 더 나쁜 건 **실행 중인 컨테이너의 `.State.ExitCode` 가 0** 이라서 종료 코드만 검사하면 아직 적용 중인데도 통과해버린다는 점이다.
- **해결**: `docker compose up -d --wait` 를 쓰거나 `.State.Status` 가 `exited` 가 될 때까지 기다린다(`verify.sh` 의 `wait_exited()` 가 최대 240초 대기). `*-init` 컨테이너는 `Exited (0)` 이 정상이다.
- **근거**: `api/clickhouse/verify.sh`

### 브로커 1대인데 컨슈머 그룹이 못 붙는다

- **원인**: `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR` / `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR` / `_MIN_ISR` 을 2 로 올리면 기동 후 `__consumer_offsets` 생성이 실패한다.
- **해결**: 브로커 1대면 내부 토픽 복제본도 1이어야 한다. 브로커를 늘릴 때는 alias 대신 실제 호스트를 쓰고 내부 토픽 replication factor 와 `create-topics.sh` 의 `REPLICATION` 을 함께 올린다.
- **근거**: `api/compose.yml`, `api/clickhouse/kafka/create-topics.sh`

### ClickHouse 컨슈머 6개 중 5개가 논다

- **원인**: 토픽 자동 생성에 맡기면 파티션이 1개로 만들어진다. `kafka_listen_events` 는 `ON CLUSTER mupid` 로 두 노드에 생기고 노드마다 `kafka_num_consumers = 3` 이라 컨슈머 그룹 `ch_listen_events` 에 총 6개가 붙는다.
- **해결**: `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"` 를 유지하고 `kafka-init` 이 `PARTITIONS: "6"` 으로 토픽을 미리 만들게 한다. 파티션 수는 브로커 수와 무관하다.
- **근거**: `api/compose.yml`, `api/clickhouse/kafka/create-topics.sh`

### 사전(dictionary)을 다른 DB 로 옮겼더니 로딩이 실패한다

- **원인**: DDL 이 `SOURCE(CLICKHOUSE(TABLE 'v_track_sections_current'))` 처럼 DB 를 명시하지 않아 사전 로딩 시 `default` 에서 찾는다.
- **해결**: 원본 DDL 을 그대로 쓰기 위해 `default` 를 사용한다. 별도 DB 로 옮기려면 DDL 에 `DB` 절을 추가해야 한다.
- **근거**: `api/clickhouse/init/apply-ddl.sh`

### 운영에 올렸는데 복제가 조용히 깨져 있다

- **원인**: `infra/deploy/compose.yml` 은 분석 변수를 `${CH_SHARD:-}` 처럼 **빈 기본값**으로 받는다. compose 가 profile 로 거르기 전에 파일 전체를 치환하므로, `${VAR:?}` 로 필수 표시를 하면 analytics 를 안 쓰는 배포의 `receive.sh` `up -d api` 까지 interpolation 오류로 실패하기 때문이다. 대가로 값을 빠뜨려도 compose 가 통과하고, `track_sections` 의 Keeper 경로에는 `{shard}` 가 없어 두 노드가 같은 경로를 잡는다.
- **해결**: 올린 뒤 반드시 확인한다 — `docker exec mupid-clickhouse clickhouse-client -q "SELECT * FROM system.macros"`. 로컬 `api/compose.yml` 은 반대로 `CH_SHARD: "01"` 을 하드코딩하고 비밀번호만 `${MUPID_CLICKHOUSE_PASSWORD:?…}` 로 필수 처리하므로, 두 파일에서 같은 패턴을 기대하지 않는다.
- **근거**: `infra/deploy/compose.yml:85-127` (주석에 근거가 그대로 있다)

### `extra_hosts` 의 `kafka2` 만 기본값이 없다

- **원인**: `- 'kafka1:${MUPID_KAFKA_HOST:-127.0.0.1}'`(141행)에는 fallback 이 있는데 바로 다음 줄 `- 'kafka2:${MUPID_KAFKA_HOST}'`(142행)에는 없다. `MUPID_KAFKA_HOST` 를 비운 채 `analytics` profile 로 clickhouse 를 만들면 두 항목이 서로 다른 값이 된다. `infra/deploy/.env.example` 이 `MUPID_KAFKA_HOST=` 를 빈 값으로 배포하므로 채우는 걸 잊기 쉽다.
- **해결**: `MUPID_KAFKA_HOST` 를 반드시 채운다. api 전용 배포 경로(`receive.sh`)는 clickhouse 서비스를 만들지 않아 이 줄에 걸리지 않는다.
- **근거**: `infra/deploy/compose.yml:140-142`

### `api/clickhouse/` 를 고쳤는데 서버에 반영되지 않는다

- **원인**: `infra/deploy/compose.yml` 은 XML·스크립트를 `/srv/mupid/config/clickhouse/{server,users,keeper,kafka,init}` 에서 마운트하는데, 저장소의 파일을 그 경로로 복사하는 **자동화가 없다.** `infra/ci/*` 와 `infra/bootstrap-docker.sh` 어디에도 복사 코드가 없다(`infra/ci/backend.yml`·`release.yml` 에 나오는 `infra/deploy/compose.yml` 은 파이프라인 트리거용 `changes` 목록일 뿐이다).
- **해결**: 수동 복사 후 컨테이너 재생성이 필요하다(nginx.conf 와 같은 위치 규칙).
- **근거**: `infra/deploy/compose.yml`

### Keeper 가 죽으면 Replicated 테이블이 읽기 전용이 된다

- **원인**: `keeper_config.xml` 의 `raft_configuration` 에 서버가 하나뿐(`<id>1</id> <hostname>mupid-dev-ch-keeper</hostname> <port>9234</port>)이라 쿼럼이 없다. 운영도 같은 파일을 쓴다(`infra/deploy/compose.yml` 의 `clickhouse-keeper` 는 profile `keeper` 로 서버 A 에서만 뜬다).
- **해결**: 운영은 3 노드 이상(홀수)으로 `raft_configuration` 과 `server/01-keeper.xml` 의 `<zookeeper>` 블록을 함께 늘려야 한다. 노드 개수가 가변이라 환경변수로 만들 수 없어 이 파일만 환경별로 따로 둔다.
- **근거**: `api/clickhouse/keeper/keeper_config.xml`

### ClickHouse 비밀번호가 평문이고 접근 네트워크가 전체 허용이다 (운영 전 필수 변경)

- **원인**: `10-mupid-user.xml` 은 `<password from_env="MUPID_CLICKHOUSE_PASSWORD"/>` 즉 서버가 평문으로 들고 있는 방식이고 `<networks><ip>::/0</ip></networks>` 는 전체 허용이다.
- **해결**: 운영에서는 `<password_sha256_hex>`(`echo -n '평문' | sha256sum`)로 바꾸고 시크릿 관리로 주입하며, 네트워크를 API 서버 대역으로 좁힌다. 계정 이름 `mupid` 는 XML 엘리먼트 이름이라 환경변수로 받을 수 없어, 바꾸려면 `users/10-mupid-user.xml` 과 `server/00-cluster.xml` 의 `<user>` 를 함께 고친다. 운영에 노출되는 9000/9009/9181/9092 도 상대 서버 IP 에만 열어야 한다.
- **근거**: `api/clickhouse/users/10-mupid-user.xml` (파일 주석), `infra/deploy/compose.yml`

### 기본 배포로는 ClickHouse / Kafka 가 하나도 안 뜬다

- **원인**: `infra/deploy/compose.yml` 의 분석 스택은 `profiles: [analytics]`(clickhouse), `[keeper]`, `[kafka]`, `[init]` 뒤에 있다. `COMPOSE_PROFILES` 를 주지 않고 서비스 이름도 지정하지 않으면 하나도 뜨지 않는다. `infra/deploy/receive.sh` 는 `up -d … api` 와 `nginx` 만 재기동하고 profile 을 켜지 않는다 — **의도된 동작이다.**
- **해결**: 서버별로 이름을 직접 지정한다 — `docker compose up -d clickhouse clickhouse-keeper` / `docker compose up -d clickhouse kafka`. 개발용 `.env` 값 `MUPID_CLICKHOUSE_URL=jdbc:clickhouse://localhost:18123/default` 는 컨테이너 안에서 통하지 않으므로 운영 `.env` 에서 반드시 덮어쓴다.
- **근거**: `infra/deploy/compose.yml:116-250`, `infra/deploy/receive.sh`

### `api/clickhouse/README.md` 의 "운영 배포 파일에는 ClickHouse/Kafka 가 없다" 는 낡았다 — 해소됨

- **원인**: README 165행 표는 `infra/deploy/compose.yml` 칸을 "❌ 서비스 자체가 없음" 으로, 171행 본문은 "**운영 배포 파일에는 ClickHouse / Kafka 서비스가 아직 없다.**" 로 단언한다. 실제로는 `clickhouse`(116행), `clickhouse-keeper`(167행), `kafka`(191행), `kafka-init`(233행), `clickhouse-init`(249행)이 모두 정의되어 있다. 커밋 `7cf92ea`(운영 compose 에 ClickHouse/Kafka 추가, 10:47:57) 직후 `6c34838`(가이드 추가, 10:47:58)이 들어오면서 갱신이 누락된 것으로 보인다.
- **해결**: 사실 확인은 README 가 아니라 `infra/deploy/compose.yml` 을 직접 본다. 다만 "기본 배포로는 안 뜬다" 는 취지 자체는 위 항목대로 여전히 유효하다.
- **근거**: `api/clickhouse/README.md:165,171` vs `infra/deploy/compose.yml`

### ClickHouse 셸 스크립트에 실행 권한이 없었다 — 해소됨

- **원인**: `apply-ddl.sh`, `create-topics.sh`, `verify.sh` 가 mode 100644 라 컨테이너 엔트리포인트로 마운트했을 때 실행되지 않았다.
- **해결**: 커밋 `ce1fd4c`(셸 스크립트 실행 권한 부여)에서 100755 로 변경. 현재 이 세 개만 실행 비트가 있다. 컨테이너에 마운트되는 스크립트는 커밋 시 mode 를 확인한다.
- **근거**: `git ls-files -s -- '*.sh'`, `api/clickhouse/verify.sh`

---

### `user_id` 타입이 MySQL 과 어긋난다 — 해소됨

- **원인**: 회원 식별자의 기준은 MySQL `users.user_id`(`Integer`, INT)인데 `api/clickhouse/init/01-schema.sql` 이 `user_id` 를 `UInt64` 로 선언했다. ClickHouse 쪽 명세 오류였다.
- **해결**: 테이블 5개(`listen_events_local`, `user_section_signals_local`, `play_section_coverage_local`, `rec_impressions_local`, `kafka_listen_events`)의 선언을 **`UInt32` 로 수정 완료.** `track_id` 와 같은 타입이 됐다. `ORDER BY`·`cityHash64(user_id)` 는 타입 무관이라 그대로 두었다.
- **⚠ 이미 띄운 클러스터에는 반영되지 않는다.** DDL 이 `CREATE TABLE IF NOT EXISTS` 라 기존 테이블은 그대로다. 로컬은 볼륨을 지우고 다시 초기화하고, 이미 만들어진 환경은 `DROP TABLE … ON CLUSTER` 후 재생성하거나 `ALTER TABLE … MODIFY COLUMN` 한다. `mv_kafka_to_events`·`mv_user_section_signals` 는 생성 시점 정의로 고정되므로 함께 재생성한다.
- **이어서**: `playlist_id`·`comment_id` 도 `UInt32` 로 맞췄다. 대응하는 MySQL 엔티티가 아직 없으므로, 플레이리스트·댓글 도메인을 만들 때 PK 를 다른 타입으로 두면 다시 어긋난다. 이 저장소의 엔티티 PK 는 전부 `Integer` 다.
- **근거**: `api/clickhouse/init/01-schema.sql`, `api/src/main/java/com/osundosun/mupid/api/user/entity/User.java`
