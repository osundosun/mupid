# ERD 스키마 (MySQL)

새 테이블을 만들거나 엔티티를 추가·변경하기 전에 읽는다. **MySQL 스키마의 출발점은 ERD 다.** DDL 원문은 [erd.sql](erd.sql) 에 있고, 이 문서는 그것을 어떻게 읽고 어떤 규칙으로 코드에 옮기는지를 적는다.

관련 문서: [persistence.md](../architecture/persistence.md)(JPA 매핑 규약) · [decision/0011](../decision/0011-jpa-manages-schema.md)(`ddl-auto: none`) · [decision/0013](../decision/0013-soft-delete-policy.md)(soft delete)

---

## ⚠ ERD 를 고치기 전에 반드시 물어본다

ERD 는 개발 과정에서 **바뀔 수 있다.** 다만 바꾸는 순간 다른 사람의 엔티티·쿼리·화면이 함께 깨지므로, 다음을 지킨다.

> **테이블·컬럼·타입·제약을 바꾸려면 먼저 팀에 수정 의사를 묻고 합의한 뒤에 고친다.**
> 혼자 판단해서 ERD 를 고치거나, ERD 와 다른 엔티티를 만들어 "코드가 기준" 으로 밀어붙이지 않는다.
> 작업 중 ERD 가 현실과 안 맞는다고 느끼면, 고치기 전에 **무엇이 왜 안 맞는지를 먼저 공유한다.**

AI 에이전트에게도 같은 규칙이 적용된다. 요청이 스키마 변경을 수반하면 **작업을 멈추고 사용자에게 확인을 받는다.** 확인 없이 `erd.sql` 을 수정하거나, ERD 에 없는 컬럼을 엔티티에 추가하지 않는다.

변경이 합의되면 순서는 이렇다.

1. ERD 도구에서 고치고 DDL 을 다시 산출해 `erd.sql` 을 통째로 교체한다 (손으로 부분 수정하지 않는다).
2. 대응 DDL 을 **실제 DB 에 직접 실행한다.** `ddl-auto` 가 `none` 이라 앱은 스키마를 만들지도 바꾸지도 않는다 → [decision/0011](../decision/0011-jpa-manages-schema.md).
3. 엔티티 매핑을 맞춘다.
4. 이 문서의 "코드와 어긋난 지점" 표를 갱신한다.

## 기준이 둘로 보이는 문제

[decision/0011](../decision/0011-jpa-manages-schema.md) 은 "스키마의 기준은 JPA 엔티티 매핑" 이라고 적는다. ERD 가 들어오면서 기준이 둘처럼 보이지만, 실제 역할은 다음과 같이 나뉜다.

| | 역할 |
|---|---|
| **ERD (`erd.sql`)** | 테이블이 **어떤 모양이어야 하는가** — 설계의 기준. DDL 산출물도 여기서 나온다 |
| **JPA 엔티티** | 애플리케이션이 그 테이블을 **어떻게 읽고 쓰는가** — ERD 를 따라간다 |

즉 **엔티티가 ERD 를 따르고, 둘이 어긋나면 어긋난 쪽이 결함**이다. ADR 본문을 이 관계에 맞게 고칠지는 아직 정하지 않았다 → [open-questions.md](../open-questions.md).

## 테이블 한눈에 보기

`erd.sql` 의 테이블 27개 + 뷰 1개. 굵게 표시한 것이 **현재 코드에 엔티티가 있는 테이블**이다.

| 묶음 | 테이블 |
|---|---|
| 회원 | **`users`**, **`email_verifications`**, `onboarding_survey`, `search_histories` |
| 파일 | **`object_storage_files`** |
| 카탈로그 | `music`, `albums`, `artists`, `genres`, `moods`, `instruments`, `codes`, `music_genres`, `music_moods`, `lyrics` |
| 음원 분석 | `music_analysis`, `music_analysis_instruments`, `music_section_analysis`, `music_section_analysis_mood` |
| 스트리밍 | `streams`, `chunks` |
| 사용자 행동 | `music_reactions`, `play_histories`, `comments` |
| 플레이리스트 | **`playlists`**, `playlist_songs` |
| 추천·분석 | `daily_recommendations`, `daily_recommendation_songs`, `analysis_reports`, `mbti` |
| AI 채팅 | `ai_chat_sessions`, `ai_chat_messages` |
| 뷰 | `user_auth` — `SELECT user_id, email, password FROM users WHERE status != 'WITHDRAW'` |

**엔티티가 4개뿐이라는 것은 나머지 테이블이 아직 코드에 없다는 뜻이다.** 새 도메인을 맡으면 그 테이블의 엔티티를 처음 만드는 사람이 된다.

## ERD 가 쓰는 공통 규약

| 항목 | 규칙 | 예외 |
|---|---|---|
| PK | `<단수명>_id`, `INT AUTO_INCREMENT` | `ai_chat_messages`·`daily_recommendations`·`play_histories` 는 `BIGINT`, `instruments` 는 `SMALLINT UNSIGNED`, `ai_chat_sessions` 는 `AUTO_INCREMENT` 가 없다 |
| 테이블명 | 복수형 snake_case | `music`(불가산), `mbti`, `onboarding_survey`(단수) |
| 감사 컬럼 | `created_at` / `updated_at` `DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP` | 이력성 테이블은 `created_at` 만 둔다 |
| enum | MySQL `ENUM(...)` 컬럼 | — |
| soft delete | **두 가지가 섞여 있다** — `is_deleted TINYINT(1)` (`albums`, `artists`, `music`, `comments`, `object_storage_files`, `ai_chat_sessions`) / `deleted_at DATETIME` (`playlists`) / 상태 enum (`users.status = WITHDRAW`) | |
| 시간 | `DATETIME` (초 단위). `DATETIME(6)` 이나 `TIMESTAMP` 를 쓰지 않는다 | |
| ms 단위 | `INT UNSIGNED` 컬럼에 밀리초를 담는다 (`start_time_ms`, `duration_ms`, `timestamp_ms`) | `music_section_analysis` 만 부호 있는 `INT` |
| FK | `ALTER TABLE ... ADD CONSTRAINT fk_<테이블>_<대상>` 으로 뒤에 몰아서 건다. `ON DELETE` 는 `ai_chat_*` 두 개만 `CASCADE`, 나머지는 기본(`RESTRICT`) | |
| 인덱스 | `uq_*` 유니크 / `ix_*`·`ux_*` 일반. 이름이 `uq_` 로 시작해도 유니크가 아닌 것이 있다 (`users.uq_user_email` 은 `CREATE INDEX`) | |

## 엔티티로 옮기는 규칙

ERD 컬럼을 JPA 엔티티로 옮길 때는 [persistence.md](../architecture/persistence.md) 의 기존 규약을 그대로 따른다. ERD 때문에 달라지는 점만 적는다.

| ERD | 엔티티 |
|---|---|
| `INT AUTO_INCREMENT` PK | `Integer` + `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)` |
| `BIGINT AUTO_INCREMENT` PK | `Long`. 기존 엔티티가 전부 `Integer` 라 이 경우가 처음 생기면 짚고 넘어간다 |
| `SMALLINT UNSIGNED` | `Integer` — 범위가 0~65535 라 `Short`(최대 32767) 로는 부족하다 |
| `INT UNSIGNED` | `Long` 을 검토한다. `Integer` 는 21억을 넘는 값에서 깨진다 |
| `TINYINT(1)` | `Boolean` |
| `ENUM(...)` | 자바 enum + `@Enumerated(EnumType.STRING)`. **enum 상수명을 ERD 의 ENUM 값과 글자 그대로 맞춘다** |
| `NULL` 허용 FK | `@ManyToOne(fetch = LAZY, optional = true)` + `@JoinColumn(nullable = true)` |
| `VARCHAR(n)` | `@Column(length = ...)` 을 적지 않는다 — 기존 엔티티가 길이를 지정하지 않는다. 길이 제한은 **요청 DTO 의 `@Size`** 로 막는다 |
| `DEFAULT <값>` | `@Builder.Default` 로 자바 쪽에도 같은 기본값을 준다. JPA 는 INSERT 문에 컬럼을 항상 실으므로 DB 기본값이 적용되지 않는다 |
| `created_at` / `updated_at` | `@CreatedDate` / `@LastModifiedDate` + `@EntityListeners(AuditingEntityListener.class)`. 값은 DB 기본값이 아니라 **Spring Data 감사가 채운다** |

컬럼명은 `@Column(name = ...)` 없이 Hibernate 의 암묵 네이밍(`musicCount` → `music_count`)에 맡긴다. ERD 컬럼명이 필드명의 스네이크 변환과 다르면 그때만 이름을 지정한다.

`index` 는 MySQL 예약어가 아니지만 백틱 없이는 쓰기 불편한 컬럼명이다 (`lyrics`, `chunks`, `music_section_analysis`, `ai_chat_messages`). 엔티티 필드명은 `index` 로 두되 매핑에서 문제가 생기면 `@Column(name = "\`index\`")` 가 필요할 수 있다.

## 코드와 어긋난 지점

ERD 반입 시점(2026-09-11)에 기존 엔티티 3개가 ERD 와 여러 곳에서 달랐고, **같은 날 코드를 ERD 에 맞추는 쪽으로 정리했다.** 남은 차이와 정리 내역을 함께 적는다.

### 정리된 것

| 테이블 | ERD | 고친 내용 |
|---|---|---|
| `users.gender` | `ENUM('M','F','ETC')` | `UserGender` 상수를 `MALE`/`FEMALE` → **`M`/`F`** 로 바꿨다. 요청 `gender` 값도 `M`/`F`/`ETC` 다 |
| `users.role` | `ENUM('ROLE_USER','ROLE_SYSTEM','ROLE_ADMIN')` | `UserRole` 상수를 `GUEST`/`USER`/`ADMIN` → **`ROLE_USER`/`ROLE_SYSTEM`/`ROLE_ADMIN`** 로 바꿨다. `GUEST` 는 없어졌고, 가입 시 권한은 `ROLE_USER` 다 |
| `users` | `onboarding_submitted_at` 없음 | `User.onboardingSubmittedAt` 필드와 `UserGetInfoResponse` 의 `onboarding_submitted_at` 을 **제거했다.** 온보딩 제출 여부는 앞으로 `onboarding_survey` 로 판단한다 |
| `object_storage_files` | `file_type`·`category` NOT NULL, MIME 컬럼명은 `mime` | `StorageFileType`(`IMG`/`MUSIC`)·`StorageFileCategory`(`PROFILE`/`ARTIST`/`ALBUM`/`STREAMING`) enum 을 추가하고 `contentType` 필드를 **`mime`** 으로 바꿨다. `StorageService.uploadFile` 이 두 값을 인자로 받는다 |
| `email_verifications.verified_at` | NULL 허용 | 엔티티가 `nullable = false` 로 잘못 선언돼 있던 것을 `nullable = true` 로 고쳤다 |

이 변경으로 **API 계약이 바뀌었다.** `gender` 로 `MALE` 을 보내면 500 이 되고, `GET /api/v1/user` 응답에서 `onboarding_submitted_at` 이 사라졌으며, `role` 값이 `ROLE_USER` 로 나간다. 그리고 **기존에 발급된 액세스 토큰은 `role` 클레임이 옛 값이라 깨진다** — 재로그인이 필요하다.

### 남은 차이

| 테이블 | ERD | 현재 코드 | 영향 |
|---|---|---|---|
| `users.nickname` | `VARCHAR(10)` | 길이 미지정 | 요청 검증이 2~10자라 실질 충돌은 없다 |
| `users.email` | `VARCHAR(320)`, 유니크 아님 (`CREATE INDEX`) | 길이 미지정, 중복은 애플리케이션에서만 막는다 | 동시 요청에서 같은 이메일이 두 번 들어갈 수 있다 |
| `users.birth_year` | `SMALLINT` | `Integer` | 범위 안이면 무해 |
| `email_verifications.token` | `VARCHAR(6)` + **UNIQUE** | 길이·유니크 없음 | 6자리 랜덤이 충돌하면 발송이 실패한다 |
| 프론트엔드 | — | `frontend/` 의 회원가입 폼과 `SignupInput` 타입이 아직 `MALE`/`FEMALE` 을 쓴다 | 지금은 데모 모드라 API 를 부르지 않지만, 연동하는 사람이 반드시 함께 고쳐야 한다 |
| `onboarding_survey` | `mood_id` · `genre_id` | 엔티티·**실제 DB 모두** `mood_code_id` · `genre_code_id` | **`erd.sql` 쪽이 낡았다.** 개발 DB 에 적용된 DDL 은 `_code_id` 다. 코드는 실제 DB 를 따랐다 |
| `onboarding_survey` | `mood_id` · `genre_id` **NOT NULL** | 같음 | 한 줄에 답 하나를 담을 수 없다. 지금은 분위기 × 장르 조합으로 우회한다 → [decision/0017](../decision/0017-onboarding-survey-cross-product.md) |

## 곡 식별자가 둘이다

ClickHouse 쪽 분석 스키마는 곡을 **Jamendo 카탈로그의 `track_id`** 로 식별하고([glossary.md](glossary.md)), MySQL ERD 는 자체 **`music.music_id`** 로 식별한다. `listen_events.track_id` 와 `playlist_songs.music_id` 가 같은 곡을 가리키는지, 둘을 잇는 매핑이 어디에 있는지는 아직 정해지지 않았다 → [open-questions.md](../open-questions.md).

MySQL 도메인을 만들 때는 **`music_id` 를 쓴다.** ERD 에 `track_id` 컬럼은 없다.

## 출처

[erd.sql](erd.sql) — ERD 도구 산출 DDL (2026-09-11 반입).

[seed-codes.sql](seed-codes.sql) — `codes` 기준 데이터. `ddl-auto` 가 `none` 이라 사람이 직접 넣는다. 비어 있으면 온보딩이 503 이다.
