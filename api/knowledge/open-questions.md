# 결정이 필요한 것

조사 중 **사람이 정해야 답이 나오는** 항목만 모았다. 코드를 읽어서 알 수 있는 것은 여기 없다.

정해지면 → 되돌리기 어려운 것은 [decision/](decision/README.md) 에 ADR, 지킬 규칙은 [convention/](convention/README.md), 단순 수정이면 고치고 이 줄을 지운다.

---

## A. 보안·정책 — 먼저 정할 것

### A1. 비밀번호 재설정이 계정 존재 여부를 노출한다

`GET /api/v1/user/reset` 이 미가입 이메일에 `MUPID_USER_NOT_FOUND`(404)를 그대로 반환한다. 로그인은 커밋 `ffbac12` 에서 이를 의도적으로 막아 `WRONG_EMAIL_OR_PASSWORD`(401) 하나만 던지는데, 재설정 경로에는 같은 정책이 적용되지 않았다.

**정할 것**: 재설정도 항상 성공 응답을 주고 계정 존재를 숨길 것인가?

### A2. 리프레시 토큰을 회전할 것인가

재발급 응답에 새 `Set-Cookie` 가 없다. 같은 리프레시 토큰을 만료까지 계속 쓴다. 탈취 시 유효기간(7일) 내내 재발급이 가능하다.

**정할 것**: 회전(rotation)을 도입할지, 현재 방식을 유지할지.

### A3. 인증 메일 재발송 제한이 없다

동일 이메일 재발송 제한도, 기존 미인증 건 무효화 경로도 없다. `EmailVerificationRepository` 는 `JpaRepository` 기본 메서드만 가지고 있어 기존 건을 조회할 수단 자체가 없다.

**정할 것**: rate limit 을 걸지, 건다면 어디에(Redis / DB).

### A4. 운영 Redis 에 인증이 없다

`requirepass` 도 `spring.data.redis.password` 도 없다. `backend` 네트워크 격리에만 의존한다.

**정할 것**: 네트워크 격리로 충분하다고 볼 것인가.

### A5. Redis 메모리 상한·eviction 정책이 없다

세 compose 파일 모두 `--appendonly yes` 만 준다. 상한이 없어 OOM 위험이 있는데, **나중에 상한을 걸면서 eviction 정책을 잘못 잡으면 JWT 차단 목록이 지워져 로그아웃이 무력화된다** ([decision/0004](decision/0004-jwt-denylist-in-redis.md)).

**정할 것**: `maxmemory` 와 `maxmemory-policy`. 차단 목록은 evict 되면 안 된다.

---

## B. 스키마·데이터

### B1. 테이블 생성·변경 절차를 어디에 기록할 것인가

`ddl-auto: none` 이라 DDL 은 사람이 실행한다 ([decision/0011](decision/0011-jpa-manages-schema.md)). 그런데 **DDL 산출물도 실행 이력도 저장소에 없어서, 새 팀원이 로컬 DB 를 세팅할 방법이 어디에도 없다.**

**정할 것**: 참고용 DDL 을 커밋할지, 절차를 문서로 남길지, 아무것도 안 할지.

### B2. `EmailVerification.verifiedAt` 매핑이 코드와 맞지 않는다

`@Column(nullable = false)` 인데 발송 시점 빌더는 이 값을 채우지 않고, `validateToken()`·`isValid()` 는 `getVerifiedAt() != null` 로 미인증을 판별한다. **코드는 null 을 정상 상태로 쓰는데 매핑만 NOT NULL 이다.** 이 매핑을 기준으로 컬럼을 만들면 발송이 INSERT 에서 깨진다.

**정할 것**: `nullable = true` 로 고치는 것이 맞는지 (그렇게 보인다).

### B3. Spring Batch 메타 테이블

`spring-boot-starter-batch` / `-batch-jdbc` 가 클래스패스에 있는데 잡이 하나도 없고 `BATCH_*` 테이블도 준비돼 있지 않다.

**정할 것**: 배치를 쓸 계획이 없으면 스타터를 뺀다. 쓸 거면 `spring.batch.jdbc.initialize-schema` 를 정한다.

### B4. `analysis_version` 전환 절차

`v_track_sections_current` 에 `analysis_version = 'v1'` 이 하드코딩돼 있다. 버전을 올리려면 뷰를 `CREATE OR REPLACE` 해야 하고, 딕셔너리는 `LIFETIME`(300~600초) 후에 반영된다. 이미 쌓인 `user_section_signals` 는 소급되지 않는다.

**정할 것**: 뷰 교체가 정식 절차인지, 다른 전환 방식을 쓸지.

### B5. 가중치를 저장할 것인가

수집 계층에는 가중치가 없다 — 어떤 행동이 얼마나 긍정인지는 분석·모델의 판단이고, 해석은 쿼리 시점에 계산해야 소급된다 ([decision/0015](decision/0015-derive-interpretation-at-query-time.md)). 그런데 `mv_user_section_signals` 는 이벤트가 들어오는 순간 가중치를 계산해 `user_section_signals.weight` 에 **저장한다.** 값을 바꿔도 과거 행에는 반영되지 않아 옛 신호와 새 신호가 섞인다.

덧붙여 이 MV 는 `target` 을 보지 않는다. 명세는 `LIKE(target=TRACK)` 를 곡의 전 구간 긍정으로, `DISLIKE` 를 "들은 부분만 부정 · 안 들은 부분은 중립" 으로 처리하라고 하는데, 지금은 `position_ms` 가 가리키는 구간 하나에만 값을 준다. `DISLIKE` 쪽은 "들은 부분" 을 알아야 해서 `play_section_coverage` 와 JOIN 해야 한다.

같은 `position_ms > 0` 조건 때문에 생긴 문제가 둘 더 있다.

- **`PLAYLIST_REMOVE` 의 −0.8 은 발동하지 않는다.** 이 행동에는 재생 위치가 없어 `position_ms` 가 0 이다. 애초에 *구간* 신호가 아니라 *곡* 신호라 이 목록에 있는 것 자체가 어색하다.
- **구간 지정이 여러 구간에 걸치면 시작점만 잡힌다.** 프론트가 `position_ms = start_ms` 를 함께 보내 신호가 사라지는 것은 막았지만(→ `frontend/lib/analytics/tracker.ts`), `[start_ms, end_ms)` 전체를 커버리지처럼 쪼개 주지는 않는다.

**정할 것**: `user_section_signals` 를 가중치 없는 사실 테이블(어떤 이벤트가 어떤 구간에서 일어났는가)로 낮추고 해석을 VIEW·모델에 넘길지, 지금의 기본선을 유지할지. 바꾸면 이미 쌓인 `weight` 는 버린다.

### B6. 회원 탈퇴 시 ClickHouse 에 남는 데이터

`listen_events` 는 댓글 원문(`content`)과 검색어(`search_query`)를 **18개월** 보관한다. 탈퇴 처리가 MySQL 만 건드리면 이 데이터는 그대로 남는다. ClickHouse 에서 지우려면 `ALTER TABLE … DELETE WHERE user_id = ?` 뮤테이션이 필요하고, 파생 테이블(`user_section_signals`, `play_section_coverage`)도 같이 지워야 한다.

**정할 것**: 탈퇴 플로우에 ClickHouse 삭제를 넣을지, TTL 만료에 맡길지 → [decision/0013](decision/0013-soft-delete-policy.md)

---

## C. 미구현 영역의 주인

### C1. 액세스 토큰을 클라이언트 어디에 둘 것인가

`frontend/` 전체에 `access_token`·`Bearer`·`api/v1/auth` 가 0건이다. `frontend/lib/email-verification.ts` 도 "backend contract is agreed" 이후로 미뤄져 있다.

**정할 것**: 메모리 / 쿠키 / 스토리지. 프론트 인증 작업의 선행 조건이다.

### C2. 온보딩 (`PENDING → ACTIVE`)

`UserStatus.PENDING → ACTIVE` 전환 코드가 없다. `users.onboarding_submitted_at` 은 ERD 에 없어 엔티티에서 제거했으므로, 제출 여부를 `onboarding_survey` 로 판단할지 상태 전이만으로 볼지도 함께 정해야 한다.

**정할 것**: auth 도메인에서 처리할지 별도 도메인으로 뺄지.

### C3. 수집기 운영 정책

[decision/0014](decision/0014-event-collector-in-api.md) 가 구조는 정했지만 두 가지 값이 비어 있다.

- **Redis 가 죽었을 때** — `event_id` 멱등 처리를 못 한다. 지금 구현은 `setIfAbsent` 가 던지는 예외를 잡지 않으므로 **요청 전체가 500 으로 실패한다.** 중복을 감수하고 통과시킬 것인지 정해야 한다 — 통과시키면 `user_section_signals` 가중치가 부풀고 되돌릴 수 없다.
- **`occurred_at` 클램프 범위** — 지금은 GA4 Measurement Protocol 을 따라 `72시간 전 ~ 5분 후` 로 두고 벗어나면 경계값으로 자른다(`event.occurred-at-max-past` / `-max-future`). 오프라인 사용이 거의 없다면 더 좁혀도 된다.

**정할 것**: Redis 장애 시 동작. 클램프 범위는 기본값이 들어갔으니 값만 조정하면 된다.

### C4. `decision/0011` 본문이 아직 "스키마 기준은 JPA 매핑" 이라고 적는다

엔티티와 ERD 가 어긋나 있던 컬럼들(`users.gender`/`role`, `users.onboarding_submitted_at`, `object_storage_files.file_type`·`category`·`mime`, `email_verifications.verified_at`)은 **2026-09-11 에 코드를 ERD 에 맞추는 쪽으로 정리했다** — 내역은 [domain/erd.md](domain/erd.md) 의 "코드와 어긋난 지점". 그래서 기준이 ERD 라는 것은 사실상 정해졌지만, ADR 본문은 여전히 JPA 매핑을 기준으로 적고 있다.

**정할 것**: [decision/0011](decision/0011-jpa-manages-schema.md) 을 개정할지, 아니면 ERD 를 다루는 새 ADR 을 세울지. 그리고 `users.email` 유니크 제약과 `email_verifications.token` 유니크를 애플리케이션이 아니라 DB 로 옮길지.


### C5. 곡 식별자가 `track_id` 와 `music_id` 로 갈린다

ClickHouse 분석 스키마는 Jamendo `track_id`, MySQL ERD 는 자체 `music.music_id` 를 쓴다. 둘이 같은 곡을 가리키는지, 매핑 테이블을 둘지 정해지지 않았다.

**정할 것**: 카탈로그의 단일 기준과 두 식별자를 잇는 방법.

---

## D. 정리하면 좋은 것

| | 항목 | 정할 것 |
|---|---|---|
| D1 | 서비스 인터페이스 + Impl 쌍 | 구현체가 하나뿐인 인터페이스 7개. 계속 강제할지 → [decision/0010](decision/0010-service-interface-impl-pair.md) |
| D2 | `api/compose.ci.yml` | 어디서도 참조되지 않는다. 남길지 지울지 |
| D3 | `api/compose.prod.yml` | `infra/deploy/compose.yml` 과 역할이 겹친다. 검증용으로 유지할지 |
| D4 | `redis.key-prefix` | 선언만 있고 읽는 코드가 없다. 살릴지 지울지 (`MUPID_KAFKA_BOOTSTRAP` 은 `event.kafka-bootstrap-servers` 가 읽게 됐다) |
| D5 | 브랜치 `type` 어휘 | `feat` 과 `feature` 가 공존한다. 고정할지 → [convention/git.md](convention/git.md) |
| D6 | CI 백엔드 테스트 | 현재 build-only. 테스트 실행을 넣을지 |
| D7 | `GlobalErrorCode.NOT_READABLE_MESSAGE` = 404 | 400 이 맞아 보이나 Bruno 문서에도 404 로 기록돼 이미 계약이다. 바꿀지 |
| D8 | `StorageServiceImpl` 읽기 메서드의 `@Transactional(readOnly = false)` | 의도인지 오타인지 |
| D9 | `EmailCheckAddressVerification` | 유일하게 `Response` 접미사가 없다. 통일할지 |
| D10 | `common/gmail` 패키지 구조 | S3·ClickHouse 는 `config`+`properties` 로 쪼개져 있는데 여기만 다르다 |
| D11 | `aws.s3.region: "SeaweedFS"` | 운영이 실제 S3 인지 SeaweedFS 인지. 하드코딩된 채로 전환 가능한지 |
| D12 | ClickHouse `validation-timeout: 3s` | 주석은 "넉넉히 둔다" 인데 값이 `connection-timeout` 과 같다 |
| D13 | `PLAY_PROGRESS` 5초 하트비트 | 전체 이벤트의 90% 이상을 차지한다. 간격을 늘릴지, 별도 토픽·테이블로 뺄지 |
