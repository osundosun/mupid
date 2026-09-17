# 결정 기록 (decision · ADR)

**왜 그렇게 정했는가.** 구조를 바꾸려 할 때, "이건 왜 이렇게 되어 있지?" 싶을 때 읽는다.

기록하는 이유는 하나다 — **이유를 모르면 같은 논의를 반복하거나, 이유가 있는 것을 이유 없이 되돌린다.** 현재 상태는 [architecture/](../architecture/README.md), 밟은 지뢰는 [failure/](../failure/README.md) 에 있다.

## 목록

| # | 결정 | 상태 |
|---|---|---|
| [0001](0001-spring-boot-4-snapshot.md) | Spring Boot 4 SNAPSHOT 을 쓴다 | 채택 · 되돌리기 어려움 |
| [0002](0002-mupid-env-prefix.md) | 모든 환경변수에 `MUPID_` 접두사를 붙인다 | 채택 · 강한 규칙 |
| [0003](0003-error-code-enum-owns-http-status.md) | HTTP 상태를 에러 코드 enum 이 들고 있다 | 채택 |
| [0004](0004-jwt-denylist-in-redis.md) | 로그아웃을 Redis 차단 목록으로 구현한다 | 채택 |
| [0005](0005-kafka-clickhouse-for-listen-events.md) | 청취 이벤트는 Kafka → ClickHouse 로 적재한다 | 채택 |
| [0006](0006-section-as-recommendation-unit.md) | 추천 단위를 곡이 아니라 구간으로 잡는다 | 채택 · 도메인 핵심 |
| [0007](0007-shared-nginx-template.md) | nginx 템플릿 하나를 개발·운영이 공유한다 | 채택 · `api/` 한정 |
| [0008](0008-deploy-image-from-infra.md) | 배포 이미지는 `infra/image/Dockerfile` 로 굽는다 | 채택 · **가장 헷갈림** |
| [0009](0009-husky-ticket-injection.md) | 커밋 티켓 번호를 husky 훅으로 자동 삽입한다 | 채택 |
| [0010](0010-service-interface-impl-pair.md) | 서비스를 인터페이스 + Impl 쌍으로 만든다 | 채택 · **재검토 대상** |
| [0011](0011-jpa-manages-schema.md) | 스키마는 Spring JPA 가 관리한다 (Flyway 미도입) | 채택 |
| [0012](0012-frontend-static-to-ec2.md) | 프론트엔드를 정적 산출물로 EC2 에 배포한다 | 채택 · 오해하기 쉬움 |
| [0013](0013-soft-delete-policy.md) | 삭제는 soft delete 로 한다 | 채택 · **코드가 미준수** |
| [0014](0014-event-collector-in-api.md) | 청취 이벤트 수집기를 API 안에 둔다 | 채택 · 구현 완료 |
| [0015](0015-derive-interpretation-at-query-time.md) | 해석(분류·가중치)은 저장하지 않고 쿼리 시점에 계산한다 | 채택 · **기존 MV 1개가 미준수** |
| [0016](0016-audio-upload-three-step.md) | 오디오 등록은 3단계 프로토콜로 하고, 바이트는 스프링을 거치지 않는다 | 채택 · 구현 완료 |
| [0017](0017-onboarding-survey-cross-product.md) | 온보딩 설문은 분위기 × 장르 조합으로 저장한다 | 채택 · 구현 완료 · 스키마 제안 중 |
| [0018](0018-history-from-event-stream.md) | 재생·검색 기록은 청취 이벤트에서 갈라 쓴다 | 채택 · 구현 완료 |
| [0019](0019-report-reads-clickhouse-directly.md) | 취향 리포트는 ClickHouse 를 직접 읽고, 결론만 MySQL 에 남긴다 | 채택 · 구현 완료 |
| [0020](0020-lyric-bulk-replace.md) | 가사는 곡 단위로 통째로 갈아 끼운다 | 채택 · 구현 완료 |
| [0021](0021-query-plan-instead-of-nl-interpreter.md) | 자연어는 대화 모델이 조건으로 바꾼다 — 별도 해석기를 세우지 않는다 | 채택 |
| [0022](0022-play-queue-in-redis.md) | 재생 큐는 Redis 에 사용자당 문서 하나로 둔다 | 채택 · 규칙 일부 대체됨 |
| [0023](0023-queue-insert-position-is-a-user-choice.md) | 담을 자리는 사용자가 고르고, 큐에 같은 곡은 한 번만 둔다 | 채택 |
| [0024](0024-mysql-ngram-fulltext-for-catalog-search.md) | 카탈로그 통합 검색은 MySQL ngram FULLTEXT 로 시작한다 | 채택 |

## 먼저 볼 것

- [0008](0008-deploy-image-from-infra.md) — `api/Dockerfile` 만 고치면 운영에 반영되지 않는다.
- [0011](0011-jpa-manages-schema.md) — 엔티티를 건드리기 전에 반드시 읽는다. `ddl-auto` 가 아직 미설정이다.
- [0012](0012-frontend-static-to-ec2.md) — Workers 의존성은 잔재다.

## 쓰는 법

번호는 이어서 붙이고, **한 번 쓴 ADR 은 고쳐 쓰지 않는다.** 결정이 바뀌면 새 ADR 을 쓰고 이전 것의 상태를 `대체됨 → 00NN` 으로 바꾼다. 왜 바꿨는지가 남아야 하기 때문이다.

```markdown
# 00NN. 결정을 한 줄로

**상태** 제안 | 채택 | 대체됨 → 00NN | 폐기

## 맥락
어떤 상황이었고 무엇을 골라야 했는가.

## 결정
무엇으로 정했는가.

## 결과
얻은 것과 **받아들인 비용.** 비용을 빼면 ADR 이 아니다.

## 근거
`경로/파일`
```

- 결정과 **현재 코드가 다르면** 그 사실을 ADR 안에 적는다 ([0013](0013-soft-delete-policy.md)).
- 되돌리기 어려운 결정(프레임워크·저장소·배포 방식)은 반드시 남긴다.
