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

### B7. `mubti` 컬럼 이름이 ERD 와 실제 DB 에서 다르다

로컬 MySQL 은 `class_a`~`class_d` 인데 [domain/erd.sql](domain/erd.sql) 은 `classa`~`classd` 로 적는다. 엔티티는 이름을 적지 않아 하이버네이트가 `classa` 로 유도했고(대문자가 맨 끝이라 밑줄이 안 붙는다 — [함정](failure/auth-and-api.md)), 그래서 **조회가 통째로 실패했다.** 관리자 화면을 만들며 처음 드러났다.

당장은 실제 DB 에 맞춰 엔티티에 `@Column(name = "class_a")` 를 명시해 두었다. 행이 0건이고 이 엔티티를 읽는 앱 코드가 없어 지금은 영향이 없다. `subtitle`·`description` 길이와 `image_file_id` 의 NOT NULL 여부도 ERD 와 실제 DB 가 다르다.

**정할 것**: 어느 쪽을 기준으로 맞출 것인가. ERD 를 실제 DB 에 맞춰 고칠지, DB 를 ERD 에 맞춰 바꿀지. (ERD 수정은 팀 합의가 필요하다 — `api/AGENTS.md`)

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

### B7. `daily_recommendation_songs` 에 구간 컬럼이 없다 — 정해짐

`daily_recommendations` 주석은 "오늘의 **모먼트** 추천" 인데 수록곡 테이블에는 `music_id` 만
있고 어느 구간인지가 없다.

> 2026-09-11 결정: **ERD 를 그대로 둔다.** "모먼트" 는 이름일 뿐 오늘의 추천은 **곡 목록**이고
> 구간을 영속할 필요가 없다. `music_section_id` 컬럼을 추가하지 않는다.
>
> 구간이 필요한 화면(핵심 구간부터 재생)은 저장된 값이 아니라 **재생 시점에 추천 서비스가
> 돌려주는 값**을 쓴다. 노출 이력은 ClickHouse `rec_impressions.section_uids` 에 이미 남는다.

---

### B8. ERD 와 JPA 가 어긋난 곳 — 백엔드 수정 필요

2026-09-14 에 `music` 도메인 엔티티를 ERD(`domain/erd.sql`)와 대조한 결과다. AI 담당은
JPA 와 DDL 을 건드리지 않으므로 대상만 적는다. `ddl-auto` 가 `none` 이라
([decision/0011](decision/0011-jpa-manages-schema.md)) 어느 쪽으로 맞추든 **수동 DDL 이
필요하다.**

> **2026-09-15 확정: JPA 매핑을 기준으로 통일한다.** 두 벌이 공존하면 내보낼 때마다
> 어느 쪽인지 되묻게 된다. 돌고 있는 코드가 JPA 이므로 그쪽을 기준으로 삼고
> `api/knowledge/domain/erd.sql` 을 옮겼다. **실제 DB 에는 아직 적용하지 않았다** —
> Flyway 가 없어 수동 DDL 이다(대기 사항).

| # | 대상 엔티티 | 현재 JPA | 기존 ERD | 결정 (JPA 기준) |
|---|---|---|---|---|
| 1 | `MusicSectionAnalysis` | `musicSectionAnalysisId` → `music_section_analysis_id` | `music_section_id` | **ERD 를 `music_section_analysis_id` 로 바꿨다.** AI 계약의 JSON 필드명은 `music_section_id` 그대로이고 내보낼 때 별칭으로 맞춘다 |
| 2 | `MusicSectionAnalysis` | `externalId` → `external_id` | `embedding_id VARCHAR(50)` | **ERD 를 `external_id VARCHAR(64)` + UNIQUE 로 바꿨다.** 넘치면 MySQL 이 조용히 자르고 잘린 값끼리는 매핑 1:1 검사까지 통과한다 |
| 3 | `MusicSectionAnalysis` | `mood_id` 미매핑 | `mood_id INT **NOT NULL**` | **현재 ERD 에서 컬럼이 빠졌다.** 엔티티에 필드가 없어 NOT NULL 이면 INSERT 가 통째로 실패한다 |
| 4 | `MusicSectionAnalysisMood` | `@Table(name = "music_section_analysis_mood")` | 같음 | **이 줄은 사실이 아니었다.** `music_moods` 는 다른 엔티티(`MusicMood`)다. 테이블명은 원래 일치하고, 조인 컬럼을 JPA 기준(`music_section_analysis_id`·`mood_code_id` → `codes`)으로 맞췄다 |
| 5 | `MusicAnalysis` | `@Id @Column(name = "music_id")` (공유 PK) | `music_analysis_id INT AUTO_INCREMENT` | **공유 PK 로 바꿨다.** 대리키를 두면 한 곡에 분석이 여러 벌 생겨 어느 것이 맞는지 알 수 없다 |
| 6 | `DailyRecommendation` | `Integer recommendationId` | `recommendation_id **BIGINT**` | `Long` 으로 바꾼다 — 공개 JSON 도 `recommendationId` 는 64비트다 |
| 7 | `DailyRecommendationTrack` | `@Table(name = "daily_recommendation_tracks")` | `daily_recommendation_songs` | 이름이 셋(`tracks`/`songs`/표준 `music`)으로 갈려 있다. 아래 참조 |
| 8 | `DailyRecommendationTrack` | 복합키 `(daily_recommendation_id, rank_order)` | `recommendation_song_id INT AUTO_INCREMENT` 단일 PK | 키 구조가 다르다 |

**장르·무드·악기는 `codes` 가 아니라 별도 테이블을 쓴다** (2026-09-14 확정). ERD 에는
`codes(code_type ENUM('MOOD','INSTRUMENT','GENRE'))` 와 `genres`·`moods`·`instruments` 가
**둘 다** 있는데, 현재 JPA 는 `codes` 쪽을 쓴다 (`MusicSectionAnalysisMood.moodCodeId → Code`,
`DailyRecommendation.moodCode → Code`). 별도 테이블 기준으로 옮기는 일은 백엔드 몫이다.

**이름 표준**: 과거 `song`·`songId`·`songs` 표기는 `music`·`musicId`·`music_id` 로 통일한다
(2026-09-14 확정). 2026-09-14 최신 `origin/develop` 기준 재대조 결과는 다음과 같다.

| 대상 | ERD | JPA | 상태 |
|---|---|---|---|
| 플레이리스트 수록곡 | `playlist_tracks` | `playlist_tracks` | **해소됨** — S15P21A204-174 에서 `playlist_songs` → `playlist_tracks` 로 정리 |
| 오늘의 추천 수록곡 | `daily_recommendation_songs` | `daily_recommendation_tracks` | **불일치 유지** |

플레이리스트 쪽은 `tracks` 로 통일됐다. 위 이름 표준(`music`)과는 다른 낱말이지만 ERD 와
JPA 가 서로 맞았으므로 이 항목에서는 해소로 본다. `tracks` 를 다시 `music` 으로 바꿀지는
별도 판단이 필요하며 여기서 임의로 바꾸지 않는다.

**정할 것**: 1~8 각각을 ERD 기준으로 맞출지, 코드 기준으로 ERD 를 고칠지. ERD 수정은
팀 합의가 선행된다([domain/erd.md](domain/erd.md)).

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

구간 식별자도 같은 문제가 있다. MySQL `music_section_analysis.embedding_id`(VARCHAR 50)와
ClickHouse `section_uid`(String)가 같은 대상을 가리키는데 이름도 생성 주체도 정해지지 않았다.
곡 식별자는 AI 쪽 문자열(`T000001`)까지 더해 세 가지다. 하나로 통일하면 키 하나로
MySQL·ClickHouse·벡터스토어가 연결된다.
→ [infra/AI-INTEGRATION-2026-09-11.md](../../infra/AI-INTEGRATION-2026-09-11.md) §4-B

### C6. `onboarding_survey` 한 줄에 답 하나를 담을 수 없다

`mood_code_id` · `genre_code_id` 가 둘 다 NOT NULL 인데 표 주석은 "온보딩 문항별
응답" 이다. 화면은 분위기와 장르를 짝지어 묻지 않으므로 지금은 **고른 분위기 ×
고른 장르의 모든 조합**을 넣는다 — 손실은 없지만 한 사람 최대 64줄이다.
배경은 [decision/0017](decision/0017-onboarding-survey-cross-product.md).

**정할 것**: 두 칼럼을 NULL 허용으로 내려 한 줄에 답 하나를 담을지. 그러면 줄 수가
M + G 로 줄고 표 주석과도 맞는다. 조합에서 각 축을 `DISTINCT` 해 옮기면 되므로
마이그레이션에 값 손실은 없다. **ERD 변경이라 팀 합의가 먼저다.**

### C7. 청취 이벤트 프로듀서는 누가 만드는가

`infra/deploy/compose.yml` 주석이 "서버 B(FastAPI)" 를 언급하지만 **그런 서비스가 이 저장소에 없다.** Kafka `mupid.listen.events` 에 이벤트를 넣는 주체와 스키마 계약을 관리할 곳이 정해지지 않았다.

**정할 것**: 백엔드에서 발행할지, 별도 서비스인지. 이게 정해져야 [domain/listening-events.md](domain/listening-events.md) 의 나머지가 굴러간다.

> 2026-09-11: 주석이 말하는 "서버 B(FastAPI)" 는 AI 추천 서비스다. 현재는 Kafka 가 아니라
> 파일에 쓴다. AI 담당자는 **백엔드 발행(1번)** 을 권한다 — AI 가 무상태가 되어 다중화·복구가
> 단순해진다. → [infra/AI-INTEGRATION-2026-09-11.md](../../infra/AI-INTEGRATION-2026-09-11.md) §4-C

### C8. AI 산출물을 어떻게 전달할 것인가

추천 서빙에 필요한 파일은 약 515MB 이고(2026-09-14 실측, 이전 120MB 는 오기) 곡이 늘 때마다 바뀐다. 이미지에 넣으면 곡 추가마다
재배포해야 하고, SeaweedFS 에 두면 배포와 분리되지만 운영 가동 확인이 선행된다.

네 파일(`moments.parquet`·`moments_emb.npy`·`features_v1.npz`·`stage1_section.npy`)은
**행 번호로 서로를 가리켜** 버전이 섞이면 엉뚱한 곡을 추천하고, 그 상태로도 서비스가
정상 기동한다. 2026-09-09~10 에 실제로 두 번 발생했다.

**정할 것**: 전달 경로(이미지 / SeaweedFS)와, 묶음 단위 배포·기동 시 검증을 규약으로 넣을지.
→ [infra/AI-INTEGRATION-2026-09-11.md](../../infra/AI-INTEGRATION-2026-09-11.md) §4-H

### C9. `music` 적재에 필요한 앨범·아티스트

`music.album_id`·`music.artist_id` 가 NOT NULL 이다. 자체 생성 음원에는 둘 다 없고 Jamendo
곡에는 아티스트만 있다.

**정할 것**: 더미 아티스트·앨범을 만들지, 두 컬럼을 NULL 허용으로 바꿀지.
→ [infra/AI-INTEGRATION-2026-09-11.md](../../infra/AI-INTEGRATION-2026-09-11.md) §4-F

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
| D14 | `music_analysis_instruments.music_analysis_id` | 연결 테이블의 FK 인데 `AUTO_INCREMENT` 가 붙어 있다. `INT NOT NULL` 이 맞아 보인다 |
| D15 | `ai_chat_sessions.ai_chat_session_id` | 다른 PK 와 달리 `AUTO_INCREMENT` 가 없다. 의도인지 누락인지 |
| D16 | `codes` 테이블 | `moods`·`genres`·`instruments` 와 같은 정보를 중복 관리한다. 하나로 정리할지 |
| D17 | `daily_recommendations.reason VARCHAR(100)` | AI 가 만드는 추천 사유 문구가 이보다 길다. 컬럼을 늘릴지 문구를 줄일지 |
