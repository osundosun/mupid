# 0005. 청취 이벤트는 Kafka → ClickHouse 로 적재한다

**상태** 채택 · 인프라 구축 완료, 프로듀서 미구현

## 맥락

사용자 한 명이 음악을 들으면 `PLAY_START`, `SEEK_BACK`, `TRACK_SKIP` 같은 이벤트가 계속 쌓인다. **사용자가 늘면 음원 수보다 청취 이벤트 수가 훨씬 빠르게 증가한다.** 이 데이터로 사용자 선호를 갱신하려면 이벤트와 구간 정보를 반복해서 결합·집계해야 하는데, 서비스 DB(MySQL)에서 할 작업이 아니다.

## 결정

이벤트 전용 경로를 분리한다.

- 이벤트는 Kafka 토픽 `mupid.listen.events` 로 발행한다.
- ClickHouse 의 `Kafka` 엔진 테이블이 이를 소비하고, MATERIALIZED VIEW 가 실제 테이블로 옮긴다.
- 원본 이벤트는 수정하지 않고 **계속 추가하는 형태로만** 저장한다. 정제 규칙이 바뀌어도 원본부터 다시 계산할 수 있어야 하기 때문이다.
- 파생 집계도 MATERIALIZED VIEW 로 ClickHouse 안에서 만든다.

## 결과

- 이벤트 적재가 API 응답 경로에서 분리된다. 분석 쿼리가 서비스 DB 를 흔들지 않는다.
- 운영해야 할 것이 늘었다 — Kafka 2대, ClickHouse 클러스터, Keeper. 로컬 개발에서도 이 스택을 띄워야 분석 경로를 검증할 수 있다.
- **CI 가 이 영역을 전혀 검증하지 않는다.** 스키마나 XML 을 고치면 `api/clickhouse/verify.sh` 를 직접 돌려야 한다.
- 이벤트를 발행하는 프로듀서가 아직 저장소에 없다. 현재 데이터는 `verify.sh` 의 수동 INSERT 뿐이다.
- 식별자 타입은 MySQL 을 기준으로 맞춘다. `user_id`·`track_id` 모두 `UInt32` 다 (`users.user_id` 가 `Integer`). → [domain/listening-events.md](../domain/listening-events.md)

## 근거

`api/clickhouse/init/01-schema.sql`, `api/clickhouse/kafka/create-topics.sh`, [architecture/analytics-clickhouse.md](../architecture/analytics-clickhouse.md)
