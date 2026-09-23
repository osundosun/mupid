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

`erd.sql` 의 테이블 30개. **뷰는 없다** — 2026-09-15 재산출에서 `user_auth`
(`SELECT user_id, email, password FROM users WHERE status != 'WITHDRAW'`)가
빠졌고, 코드도 이 뷰를 쓰지 않는다.

| 묶음 | 테이블 |
|---|---|
| 회원 | `users`, `email_verifications`, `onboarding_survey`, `search_histories` |
| 파일 | `object_storage_files` |
| 카탈로그 | `music`, `albums`, `artists`, `codes`, `music_genres`, `music_moods`, `lyrics` |
| 음원 분석 | `music_analysis`, `music_analysis_instruments`, `music_section_analysis`, `music_section_analysis_mood` |
| 스트리밍 | `streams`, `chunks` |
| 사용자 행동 | `music_reactions`, `play_histories`, `music_comments` |
| 플레이리스트 | `playlists`, `playlist_tracks` |
| 추천·분석 | `daily_recommendations`, `daily_recommendation_tracks`, `reports`, `listening`, `mubti` |
| AI 채팅 | `ai_chat_sessions`, `ai_chat_messages` |

**30개 모두 코드에 엔티티가 있다.** 2026-09-11 반입 때는 4개뿐이었지만 그 뒤로
도메인이 차례로 붙었다. 그래서 이제 ERD 를 고치면 **반드시 어딘가의 엔티티가
함께 바뀐다** — 위쪽 "ERD 를 고치기 전에 반드시 물어본다" 가 그만큼 무거워졌다.

무드·장르·악기는 별도 테이블이 아니라 `codes` 한 테이블에 `code_type`
(`MOOD`/`INSTRUMENT`/`GENRE`)으로 들어간다. 예전 `erd.sql` 에 있던 `genres` ·
`moods` · `instruments` 세 테이블은 없다.

`codes` 는 이름을 **두 벌** 갖는다 (2026-09-17 추가).

| 컬럼 | 무엇 | 예 |
|---|---|---|
| `code_name` | **영문 내부 코드.** AI 색인과 맞추는 값이고 스프링이 AI 서버로 넘긴다 | 장르 `poprock`, 무드 `melancholic` · `serene` |
| `display_name` | **화면에 그릴 한국어** | 장르 `팝 록`, 무드 `애상적인` · `잔잔한` |

**두 종류 모두 같은 방향이다** (2026-09-21 정리). 그전에는 무드만 반대여서
(`code_name` 이 한국어, `display_name` 이 영문) 운영 DB 에 같은 무드가
`melancholic` / `애상적인` 두 행으로 들어가 `music_moods` 와 `onboarding_survey` 가
다른 번호를 썼다. 정리 절차는 [`migrate-mood-codes-2026-09-21.sql`](migrate-mood-codes-2026-09-21.sql).

헷갈리면 **`AiRecommendToolImpl` 이 `CodeOption::name`(= `code_name`)을 보낸다**는
것을 기준으로 삼는다. 여기에 한국어가 실수로 들어가면 색인에 없는 말이 가서
씨앗이 0 이 되는데, **오류는 나지 않고 개인화만 사라진다.**

## ERD 가 쓰는 공통 규약

| 항목 | 규칙 | 예외 |
|---|---|---|
| PK | `<단수명>_id`, `INT AUTO_INCREMENT` | `ai_chat_messages`·`play_histories` 는 `BIGINT`. **`ai_chat_sessions.ai_chat_session_id` 에는 `AUTO_INCREMENT` 가 반드시 있어야 한다** — `AiChatSession` 이 `GenerationType.IDENTITY` 라 빼면 세션 생성이 죽는다(수기 보정 #3). `music_analysis` 는 PK 가 곧 FK(`music_id`) 다 |
| **FK 자리** | **`AUTO_INCREMENT` 를 붙이지 않는다.** 남의 PK 를 가리키는 컬럼은 값을 발급받는 자리가 아니다 | 아래 "`AUTO_INCREMENT` 는 PK 자리에만" 참고 |
| 복합 PK | 매핑·이력 테이블은 두 컬럼을 묶는다 — `chunks`(`stream_id`,`index`), `listening`, `music_genres`, `music_moods`, `music_reactions`, `music_section_analysis_mood`, `music_analysis_instruments`, `daily_recommendation_tracks`(`…_id`,`rank_order`), `playlist_tracks`(`playlist_id`,`track_order`) | |
| 테이블명 | 복수형 snake_case | `music`(불가산), `mubti`, `onboarding_survey`, `listening`(단수) |
| 감사 컬럼 | `created_at` / `updated_at` `DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP` | 이력성 테이블은 `created_at` 만 둔다. `chunks`·`listening`·`lyrics`·`mubti`·`music_analysis`·`music_section_analysis`·`streams` 는 둘 다 없다 |
| enum | MySQL `ENUM(...)` 컬럼 | — |
| soft delete | **세 가지가 섞여 있다** — `is_deleted TINYINT(1)` (`albums`, `artists`, `music`, `music_comments`, `object_storage_files`, `ai_chat_sessions`) / `deleted_at DATETIME` (`playlists`) / 상태 enum (`users.status = WITHDRAW`) | |
| 시간 | `DATETIME` (초 단위) | `play_histories.created_at`·`search_histories.created_at` 는 **`DATETIME(6)`** — 같은 초에 여러 건이 쌓여 정렬이 흔들리던 것을 막는다. 날짜만 쓰는 곳은 `DATE` (`music`·`albums`.`release_date`, `daily_recommendations.recommendation_date`, `reports.start_date`·`end_date`) |
| ms 단위 | `INT UNSIGNED` 컬럼에 밀리초를 담는다 (`duration_ms`, `time_ms`, `timestamp_ms`, `listening_ms`) | `music_section_analysis.start_time_ms`·`end_time_ms` 만 부호 있는 `INT` |
| FK | `ALTER TABLE ... ADD CONSTRAINT fk_<테이블>_<대상>` 으로 뒤에 몰아서 건다. `ON DELETE` 는 `ai_chat_messages`·`ai_chat_sessions` 두 개만 `CASCADE`, 나머지는 기본(`RESTRICT`) | |
| 인덱스 | `uq_*` 유니크 / `ix_*`·`ux_*` 일반 | **이름이 `uq_` 라고 유니크인 것은 아니다.** `uq_song_genre`·`uq_song_mood`·`uq_user_email` 은 `CREATE INDEX` 다. 반대로 `ix_song_reactions_`·`ux_mubti_*`·`ux_play_histories_*` 는 유니크다. 이름은 ERD 도구가 붙인 것이라 믿지 말고 `CREATE (UNIQUE )?INDEX` 를 직접 읽는다 |

카탈로그 검색은 예외적으로 `music.title`·`artists.name`·`albums.title` 에 일반
prefix 인덱스와 ngram FULLTEXT 인덱스를 함께 둔다. 설계 이유와 전용 검색엔진 전환
기준은 [decision/0024](../decision/0024-mysql-ngram-fulltext-for-catalog-search.md),
기존 DB 적용문은 [apply-search-indexes.sql](apply-search-indexes.sql)에 있다.

재산출된 DDL 에는 **도구가 남긴 흔적**이 몇 군데 있다. 고치기 전에 팀에 묻되,
읽을 때 당황하지 않도록 적어 둔다.

- `ix_song_reactions_`(`music_reactions`)·`ux_play_histories_play_history_id`
  는 PK 와 같은 컬럼에 걸린 중복 인덱스다.
- `reports` 의 유니크 인덱스 이름이
  `analysis_reports_copy_uq_analysis_report_period` 다. 예전 테이블명
  (`analysis_reports`)을 복사하며 생긴 이름이다.

### `AUTO_INCREMENT` 는 PK 자리에만 — 오류 없이 어긋나는 자리

**`AUTO_INCREMENT` 가 붙은 컬럼은 전부 단일 PK 여야 한다.** 특히 **FK 자리에는
절대 붙이지 않는다.** 이 규칙은 미관이 아니라 실패 모드 때문이다.

2026-09-17 재산출까지 `music_analysis_instruments.music_section_analysis_id` 에
`AUTO_INCREMENT` 가 붙어 있었다. 그 컬럼은 복합 PK 의 앞 컬럼이면서
**`music_section_analysis` 를 가리키는 FK** 다 — 값을 발급받는 자리가 아니다.
붙어 있으면 `0` 이 들어올 때 MySQL 이 `NO_AUTO_VALUE_ON_ZERO` 를 보고
**새 번호를 발급한다.** 로컬 재현(운영과 같은 모양의 임시 표):

```sql
INSERT INTO links (section_id, code_id) VALUES (1, 100);   -- 1번 구간에 기타
INSERT INTO links (section_id, code_id) VALUES (0, 200);   -- 값이 0으로 들어온 경우

section_id  code_id
    1         100
    2         200     ← 0을 넣었는데 2가 됐다. 외래키 검사도 통과했다
```

**세 가지가 겹쳐서 조용히 어긋난다.**

1. 발급된 번호가 마침 존재하는 구간 번호라 **외래키 검사를 통과한다** —
   무결성 검사가 이 오류를 못 잡는다.
2. **오류가 나지 않는다.** INSERT 성공, 응답 200, 화면 정상. 악기만 엉뚱한
   구간에 붙는다.
3. 떼면 같은 INSERT 가 `ERROR 1452 ... foreign key constraint fails` 로
   **시끄럽게 실패한다** — 그게 맞는 동작이다. 잘못된 구간 번호를 보낸 쪽을
   즉시 알려 주니까.

**지금 당장 터지지는 않는다.** JPA 경로는 `@MapsId` 가 실제 PK 를 채워 보내므로
`0` 이 갈 일이 없다(`MusicAnalysisInstrument.MusicAnalysisInstrumentId` 참고 —
`insertable = false` 를 두면 안 된다고 적혀 있다). 위험한 것은 손으로 쓴 SQL ·
벌크 로더 · id 가 채워지지 않은 INSERT 다. 그때 이 컬럼이 조용히 남을 가리킨다.

**그래서 게이트가 이걸 본다.** `infra_k8s/deploy/schema-check.py` 는 DB 의
`extra` 에 `auto_increment` 가 있는데 `erd.sql` 에는 없는 컬럼을 차이로 보고한다.
`information_schema.columns` 를 보면서 `extra` 를 안 읽던 동안에는 이 컬럼이
정반대여도 **"차이 없음"** 이 나왔다 — 인덱스도 조회하지 않아 `external_id` 의
UNIQUE 누락이 같이 숨었다. 둘 다 2026-09-17 에 고쳤다.

`music_section_analysis.music_section_analysis_id` 는 **이름이 같아도 다른 표**라
`AUTO_INCREMENT` 가 맞다 (그건 진짜 PK 다). 헷갈리기 쉬우니 표를 확인한다.

코드 쪽에도 짝이 하나 있다. `storage/enumerate/` 에 **쓰이지 않는 쌍둥이 enum**
`StorageFileCategory` · `StorageFileType` 이 남아 있다. 실제로 도는 것은
`FileCategory` · `FileType` 이고, 쌍둥이 쪽에는 `TRACK` 이 없다. 이름이 더
그럴듯해 보여서 잘못 집기 쉬우니, `ObjectStorageFile` 이 무엇을 쓰는지 먼저
확인한다.

## 엔티티로 옮기는 규칙

ERD 컬럼을 JPA 엔티티로 옮길 때는 [persistence.md](../architecture/persistence.md) 의 기존 규약을 그대로 따른다. ERD 때문에 달라지는 점만 적는다.

| ERD | 엔티티 |
|---|---|
| `INT AUTO_INCREMENT` PK | `Integer` + `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)` |
| `BIGINT AUTO_INCREMENT` PK | `Long` — `PlayHistory.playHistoryId` 가 이렇게 한다. `AiChatMessage` 는 아직 `Integer` 라 어긋나 있다 |
| `SMALLINT UNSIGNED` | `Integer` — 범위가 0~65535 라 `Short`(최대 32767) 로는 부족하다 |
| `INT UNSIGNED` | `Long` 을 검토한다. `Integer` 는 21억을 넘는 값에서 깨진다 |
| `TINYINT(1)` | `Boolean` |
| `DATE` | `LocalDate`. `LocalDateTime` 으로 받으면 **시각이 조용히 잘린다** — `music`·`albums`.`release_date` 가 지금 그 상태다 |
| `ENUM(...)` | 자바 enum + `@Enumerated(EnumType.STRING)`. **enum 상수명을 ERD 의 ENUM 값과 글자 그대로 맞춘다** |
| `NULL` 허용 FK | `@ManyToOne(fetch = LAZY, optional = true)` + `@JoinColumn(nullable = true)` |
| `VARCHAR(n)` | `@Column(length = ...)` 을 적지 않는다 — 기존 엔티티가 길이를 지정하지 않는다. 길이 제한은 **요청 DTO 의 `@Size`** 로 막는다 |
| `DEFAULT <값>` | `@Builder.Default` 로 자바 쪽에도 같은 기본값을 준다. JPA 는 INSERT 문에 컬럼을 항상 실으므로 DB 기본값이 적용되지 않는다 |
| `created_at` / `updated_at` | `@CreatedDate` / `@LastModifiedDate` + `@EntityListeners(AuditingEntityListener.class)`. 값은 DB 기본값이 아니라 **Spring Data 감사가 채운다** |

컬럼명은 `@Column(name = ...)` 없이 Hibernate 의 암묵 네이밍(`musicCount` → `music_count`)에 맡긴다. ERD 컬럼명이 필드명의 스네이크 변환과 다르면 그때만 이름을 지정한다.

`index` 는 MySQL 예약어가 아니지만 백틱 없이는 쓰기 불편한 컬럼명이다 (`chunks`, `music_section_analysis`, `ai_chat_messages` — `lyrics` 는 재산출에서 이 컬럼이 빠졌다). 엔티티 필드명은 `index` 로 두고, 매핑에는 `@Column(name = "\`index\`")` 를 붙인다. 기존 엔티티가 전부 그렇게 하고 있다.

## 코드와 어긋난 지점

ERD 반입 시점(2026-09-11)에 기존 엔티티 3개가 ERD 와 여러 곳에서 달랐고, **같은 날 코드를 ERD 에 맞추는 쪽으로 정리했다.** 남은 차이와 정리 내역을 함께 적는다.

### 정리된 것

| 테이블 | ERD | 고친 내용 |
|---|---|---|
| `users.gender` | `ENUM('M','F','ETC')` | `UserGender` 상수를 `MALE`/`FEMALE` → **`M`/`F`** 로 바꿨다. 요청 `gender` 값도 `M`/`F`/`ETC` 다 |
| `users.role` | `ENUM('ROLE_USER','ROLE_SYSTEM','ROLE_ADMIN')` | `UserRole` 상수를 `GUEST`/`USER`/`ADMIN` → **`ROLE_USER`/`ROLE_SYSTEM`/`ROLE_ADMIN`** 로 바꿨다. `GUEST` 는 없어졌고, 가입 시 권한은 `ROLE_USER` 다 |
| `users` | `onboarding_submitted_at` 없음 | `User.onboardingSubmittedAt` 필드와 `UserGetInfoResponse` 의 `onboarding_submitted_at` 을 **제거했다.** 온보딩 제출 여부는 `onboarding_survey` 로 판단한다 — `UserGetInfoResponse.onboardingSubmitted` 는 컬럼이 아니라 `OnboardingService.hasSubmitted` 가 그 표를 보고 만드는 값이다 |
| `object_storage_files` | `file_type`·`category` NOT NULL, MIME 컬럼명은 `mime` | `FileType`(`IMG`/`MUSIC`)·`FileCategory` enum 을 추가하고 `contentType` 필드를 **`mime`** 으로 바꿨다. `StorageService.uploadFile` 이 두 값을 인자로 받는다 |
| `email_verifications.verified_at` | NULL 허용 | 엔티티가 `nullable = false` 로 잘못 선언돼 있던 것을 `nullable = true` 로 고쳤다 |

이 변경으로 **API 계약이 바뀌었다.** `gender` 로 `MALE` 을 보내면 500 이 되고, `GET /api/v1/user` 응답에서 `onboarding_submitted_at` 이 사라졌으며, `role` 값이 `ROLE_USER` 로 나간다. 그리고 **기존에 발급된 액세스 토큰은 `role` 클레임이 옛 값이라 깨진다** — 재로그인이 필요하다.

### 남은 차이

2026-09-17 재산출 기준이다. 재산출이 `erd.sql` 을 코드·실제 DB 쪽으로 끌어와서
차이가 많이 줄었다 — `onboarding_survey` 의 `_code_id`, 무드·장르의 `codes`
통합, `chunks` 복합 PK, `music_section_analysis_id` 이름은 **모두 해소됐다.**

**2026-09-17 부터 로컬 MySQL 이 `erd.sql` 과 실제로 같다.** 그전까지 로컬 DB 는
과거 `ddl-auto: update` 가 만든 것이라 문자열이 전부 `varchar(255)` 였고 UNIQUE 도
거의 없었다. 아래 "길이 미지정" 항목들이 그동안 무해했던 이유가 그것이다 — 이제는
**DB 가 실제로 자른다.** 경위는 [failure/database-and-jpa.md](../failure/database-and-jpa.md) 를 본다.

| 테이블 | ERD | 현재 코드 | 영향 |
|---|---|---|---|
| `music.release_date` · `albums.release_date` | `DATE` | `LocalDateTime` | 엔티티가 시:분:초까지 들고 API 도 `2026-09-11T00:00:00` 로 주고받는다. MySQL 이 `DATE` 로 잘라 저장하므로 **시각 부분은 버려진다.** 읽어 오면 언제나 `T00:00:00` 이다 |
| `ai_chat_messages.ai_chat_message_id` | `BIGINT` | `Integer` | 21억 건을 넘길 일은 없지만, 타입이 어긋난 채로 두면 나중에 `Long` 으로 바꾸는 순간 API 응답 타입도 함께 바뀐다 |
| `users.nickname` | `VARCHAR(10)` | 길이 미지정 | 요청 검증이 2~10자라 실질 충돌은 없다. 다만 검증을 건너뛰는 경로가 생기면 **이제 DB 가 1406 으로 막는다** |
| `users.password` | `VARCHAR(60)` | 길이 미지정 | BCrypt 해시가 정확히 60자다. 다른 인코더로 바꾸면 **넘쳐서 저장이 실패한다** |
| `users.email` | `VARCHAR(320)`, 유니크 아님 (`CREATE INDEX uq_user_email`) | 길이 미지정, 중복은 애플리케이션에서만 막는다 | 동시 요청에서 같은 이메일이 두 번 들어갈 수 있다. **ERD 의 `uq_user_email` 은 이름과 달리 UNIQUE 가 아니다** — 이름만 보고 막힌다고 믿지 않는다 |
| `users.birth_year` | `SMALLINT` | `Integer` | 범위 안이면 무해 |
| `email_verifications.token` | `VARCHAR(6)` + **UNIQUE** | 길이·유니크 없음 | 6자리 랜덤이 충돌하면 발송이 실패한다. UNIQUE 가 이제 실제로 걸려 있다 |
| `music_comments.content` · `playlists.title` 등 | `VARCHAR(100)` | 길이 미지정 | 요청 검증에 기대고 있다. 검증보다 긴 값이 들어오면 DB 가 막는다 |
| `onboarding_survey` | `mood_code_id` · `genre_code_id` **NOT NULL** | 같음 | 한 줄에 답 하나를 담을 수 없다. 지금은 분위기 × 장르 조합으로 우회한다 → [decision/0017](../decision/0017-onboarding-survey-cross-product.md) |

### 아직 DB 에 적용하지 않은 DDL (2026-09-17 기준)

`erd.sql` 이 **최종 모양**을 적고 실제 DB 가 뒤처져 있는 것들이다. `ddl-auto` 가
`none` 이라 앱이 바꾸지 않는다 — **사람이 직접 실행해야 한다.**

`infra_k8s/deploy/schema-check.py` 로 정확한 목록을 뽑는다 (아래 "출처" 참고).

| | 대상 | 지금 DB | erd.sql | 운영 | 개발 |
|---|---|---|---|---|---|
| ① | `music_analysis_instruments.music_section_analysis_id` | `int auto_increment` | `int` | 미적용 | 미적용 |
| ② | `music_section_analysis.external_id` UNIQUE | 없음 | 있음 | 미적용 | **적용됨** |
| ③ | `codes.display_name` | 컬럼 없음 | `VARCHAR(200)` | 미적용 | **적용됨** |
| ④ | `object_storage_files` 의 `file_type`·`category`·`mime` | `ENUM('IMG','MUSIC')` · `ENUM(…,'STREAMING')` · `VARCHAR(15)` | 각각 `WAVEFORM` 추가 · `VARCHAR(64)` | 미적용 | **적용됨** (2026-09-22) |
| ⑤ | `music.waveform_file_id` + `fk_music_waveform_file_id` | 컬럼 없음 | `INT` NULL + FK | 미적용 | **적용됨** (2026-09-22) |
| ⑥ | `music_analysis` 의 `bpm`·`bpm_requested_min/max`·`language`·`category`, `music_section_analysis.energy` | 컬럼 없음 | `DOUBLE`·`SMALLINT`·`VARCHAR(32)`·`VARCHAR(64)`·`DOUBLE`, 전부 NULL | 미적용 | **적용됨** (2026-09-23) |

운영·개발이 갈린 이유는 개발 DB 를 손으로 고쳤기 때문이다. ①의 DDL:

```sql
-- FK 를 먼저 떼야 MODIFY 가 통과한다. 붙은 채로 하면 ERROR 1832 로 거부된다.
ALTER TABLE music_analysis_instruments
  DROP FOREIGN KEY fk_music_analysis_instruments_music_section_analysis_id;
ALTER TABLE music_analysis_instruments
  MODIFY music_section_analysis_id INT NOT NULL;
ALTER TABLE music_analysis_instruments
  ADD CONSTRAINT fk_music_analysis_instruments_music_section_analysis_id
  FOREIGN KEY (music_section_analysis_id)
  REFERENCES music_section_analysis (music_section_analysis_id);
```

②·③ 은 각각 이렇게 된다. **②는 행이 들어오기 전에 걸어야 한다** — 중복이 이미
생긴 뒤에는 실패한다(세 테이블 모두 0행인 지금이 가장 싸다).

```sql
ALTER TABLE music_section_analysis
  ADD UNIQUE KEY uk_music_section_analysis_external_id (external_id);

ALTER TABLE codes ADD COLUMN display_name VARCHAR(200) NOT NULL AFTER code_name;
UPDATE codes SET display_name = code_name WHERE display_name = '';
```

> **③ 은 앱보다 먼저 들어가야 한다.** `Code` 엔티티가 이 컬럼을 매핑하므로,
> 없으면 스프링 컨텍스트가 못 뜨고 **모든 `@SpringBootTest` 가 통째로 죽는다.**
> 원인이 "컨텍스트 로딩 실패" 로만 보여 스키마 문제인 줄 알아채기 어렵다.

인덱스 이름은 곳에 따라 다르다 — erd.sql 은 `ux_…`, 위 DDL 은 `uk_…`, 이미 적용된
개발 DB 도 `uk_…` 다. 게이트가 **이름을 보지 않으므로** 아무 문제가 없다
(`schema-check.py` 의 "인덱스는 이름을 비교하지 않는다").

④·⑤ 는 [migrate-waveform-2026-09-22.sql](migrate-waveform-2026-09-22.sql) 한 파일에
들어 있다. 아래 "2026-09-22 — 파형" 절을 본다.

### 재산출이 풀어 준 것

| 무엇 | 전 | 후 |
|---|---|---|
| 무드·장르·악기 | `moods`·`genres`·`instruments` 세 테이블 | `codes` 한 테이블 + `code_type`. 매핑 테이블도 `mood_code_id`·`genre_code_id`·`instrument_code_id` 로 통일 |
| `onboarding_survey` | `mood_id`·`genre_id` (엔티티는 `_code_id`) | `_code_id`. **`erd.sql` 쪽이 낡았던 것이 맞았다** |
| `streams` | `ux_music_streams_music_id` 유니크 — 곡당 스트림 하나 | 인덱스 없음. **곡 하나에 비트레이트별 스트림 여러 개**를 둘 수 있다. `MusicSummaryAssembler` 가 이미 "가장 높은 비트레이트" 를 고르고 있었다 |
| `chunks` | `chunk_id` 대리키 | `(stream_id, index)` 복합 PK — 엔티티의 `Chunk.ChunkId` 와 맞는다 |
| `music_section_analysis` | `music_section_id` | `music_section_analysis_id` |
| `mubti` | `classa`~`classd` | `class_a`~`class_d`, `image_file_id` NOT NULL |
| `reports` · `listening` | `mubti_id` FK 가 `mbti` 라는 없는 테이블을 가리켰다 | `mubti` 를 제대로 가리킨다 |
| 프론트엔드 `gender` | 회원가입 폼이 `MALE`/`FEMALE` 을 그대로 보냈다 | `lib/api/endpoints.ts` 가 `'M'\|'F'\|'ETC'` 이고, 화면 값은 `demo-login.tsx` 가 변환해 보낸다 |

## 2026-09-15 재산출 — 곡 커버

`erd.sql` 을 ERD 도구에서 다시 산출해 통째로 교체했다. 대부분은 낡았던
`erd.sql` 을 **이미 돌고 있는 코드·DB 에 맞춘 것**이다 (`codes` 기반 무드·장르,
`mubti`, `reports`, `chunks` 복합 PK, `music_section_analysis_id` 등 — 엔티티는
원래 이 모양이었다). 무엇이 풀렸는지는 위 "재산출이 풀어 준 것" 표에 있다.
설계가 실제로 **바뀐** 것은 둘뿐이다.

| 변경 | 내용 |
|---|---|
| `object_storage_files.category` | `ENUM(...)` 에 **`TRACK`** 추가 — 곡 커버 이미지 |
| `music.image_file_id` | **컬럼 추가** (`INT`, NULL 허용) + `fk_music_image_file_id` |

곡 커버는 `albums`·`artists` 의 이미지와 달리 **NULL 을 허용한다.** 곡마다 표지가
따로 있는 카탈로그(`music_preprocessing` 배치가 올리는 flow_music 데이터)가 있는가
하면, 표지 없이 앨범 커버만 쓰는 곡도 있다. 화면은 곡 표지 → 앨범 커버 →
기본 이미지 순으로 내려간다 (`frontend/lib/stream-catalog.ts`).

따라온 코드 변경: `FileCategory.TRACK`, `Music.imageFile`,
`MusicAddRequest.imageFileId`(선택), `MusicUpdateRequest.imageFileId`,
`MusicSummary.imageUrl`, 조회 URL 규약(`StorageImageService.imageUrl` — 파일 번호
주소라 `SecurityConfig` 의 경로 규칙과 무관하다), `MusicRepository` 의
`LEFT JOIN FETCH m.imageFile`.

> ⚠ **DDL 은 아직 적용 대상이다.** `ddl-auto: none` 이라 앱이 스키마를 바꾸지
> 않는다. 개발 DB 에 아래를 직접 실행해야 곡 커버 등록이 통과한다.
>
> ```sql
> ALTER TABLE `object_storage_files`
>   MODIFY `category` ENUM('PROFILE','ARTIST','ALBUM','TRACK','STREAMING') NOT NULL;
> ALTER TABLE `music` ADD COLUMN `image_file_id` INT NULL AFTER `like_count`;
> ALTER TABLE `music` ADD CONSTRAINT `fk_music_image_file_id`
>   FOREIGN KEY (`image_file_id`) REFERENCES `object_storage_files` (`file_id`);
> ```

## 2026-09-22 — 파형 (S15P21A204-420)

파형(곡의 진폭 포락선)을 **배치가 계산해 서버에 올리고** 프런트가 받아 쓰도록
바꾸면서 `object_storage_files` 의 컬럼 셋이 늘었다. 그전까지 파형은 기기가 곡을
열 때마다 청크를 전부 내려받아 디코딩해 만들었다
(`frontend/lib/track-waveform.ts`). 파형은 곡이 바뀌지 않는 한 같은 값이라
한 번 만들어 두면 되는 데이터다.

파형 자체는 오브젝트 스토리지에 **JSON 파일 하나**로 올라간다 —
키는 `music/{musicId}/waveform.json` 이고, `object_storage_files` 에는 그 파일의
메타데이터가 한 행 남는다. 곡은 `music.waveform_file_id` 로 그 행을 가리킨다.

| 변경 | 내용 |
|---|---|
| `object_storage_files.file_type` | `ENUM(…)` 에 **`WAVEFORM`** 추가 — 파형은 이미지도 음원도 아니다 |
| `object_storage_files.category` | `ENUM(…)` 에 **`WAVEFORM`** 추가 — 용도가 청크(`STREAMING`)와 다르다. 청크는 배치만 읽지만 파형은 로그인한 사용자가 읽는다 |
| `object_storage_files.mime` | `VARCHAR(15)` → **`VARCHAR(64)`** |
| `music.waveform_file_id` | **컬럼 추가** (`INT`, NULL 허용) + `fk_music_waveform_file_id` — `image_file_id` 와 같은 모양 |

**`mime` 을 넓힌 것이 이 변경의 숨은 핵심이다.** `application/json` 은 16자라
`VARCHAR(15)` 에 들어가지 않는다. `StorageService.uploadFile` 의 주석이 경고하던
것이 정확히 이것이고, 그 우회로(호출부가 짧은 mime 을 넘기는 오버로드)는
컬럼이 좁다는 사실을 **호출부마다 기억해야** 유지된다. 넓히는 쪽이 싸다.

### `waveform_file_id` 를 둔 이유

키가 곡 번호에서 그대로 만들어지므로 컬럼 없이 `StorageService.getFileByKey` 로
찾을 수도 있었다. 한 번은 그렇게 가기로 했다가 되돌렸다. 근거는 셋이다.

1. **이 스키마의 파일 참조는 전부 FK 컬럼이다** — `albums`·`artists`·`music`·
   `users`·`mubti` 의 `image_file_id`, `chunks.file_id`. 키로 파일을 찾는 코드가
   있긴 하지만(`BootstrapRunner`, 이미지 삭제) 전부 **호출부가 이미 키를 들고
   있는** 경우고, 도메인이 파일을 가리키는 수단으로 쓰는 곳은 없다. 파형만
   규칙으로 찾으면 `music` 을 봐도 이 곡에 파형이 있는지 알 수 없다.
2. **백필과 감사가 컬럼을 요구한다.** 기존 곡에 파형을 채우는 명령이 대상을
   `WHERE waveform_file_id IS NULL` 로 뽑는다. 없으면 곡마다 조회를 한 번씩 때려
   404 를 세야 하고(카탈로그가 1060곡이다), 그 목록을 배치의 `registered.json` 에
   기억시키면 `audit.py`·`orphans.py` 가 이미 다루는 드리프트를 하나 더 만든다.
3. **`storage_object_key` 에는 인덱스가 없다** — `object_storage_files` 의 인덱스는
   `PRIMARY` 뿐이다(2026-09-22 실측). 청크 때문에 행이 곡당 수십 개씩 쌓이는
   테이블이라 곡을 열 때마다 풀스캔이 된다. 피하려면 어차피 인덱스를 얹어야 하고,
   그럴 바엔 PK 조인이 되는 FK 가 낫다.

NULL 을 허용한다. 파형이 아직 없는 곡은 비정상이 아니다 — 프런트가 그때는 기기에서
계산하는 예전 경로로 내려간다. 재업로드에도 흔들리지 않는다.
`StorageServiceImpl.uploadFile` 이 같은 키면 기존 행을 재사용하므로 `file_id` 가
유지된다.

> ⚠️ **개발 DB 에만 적용했다** (2026-09-22). 운영 DB 는 사람이 직접 적용한다.
> DDL 은 [migrate-waveform-2026-09-22.sql](migrate-waveform-2026-09-22.sql) 에
> 네 가지가 모두 들어 있다. `ENUM` 값 추가·`VARCHAR` 확장·NULL 컬럼 추가는 모두
> 기존 행을 건드리지 않아 서로 순서 제약이 없다 — 다만 **앱보다 먼저 들어가야**
> 파형 등록이 통과한다.

## 2026-09-23 — 추천 값 (S15P21A204-423)

추천 서버는 Spring 을 부르지 않는다(`infra/AI-SPRING-CONTRACT.md` 의 2026-09-23 절).
후보와 그 값은 Spring 이 추천 요청마다 본문 `catalog` 로 싣는데, BPM·언어·스타일과
구간 에너지는 **MySQL 에 자리가 없어** AI 색인 파일(`track_features.parquet`,
`moments.parquet` 의 `rms`)에만 있었다. 그 자리를 만든다.

| 표 | 컬럼 | 형 | 뜻 |
|---|---|---|---|
| `music_analysis` | `bpm` | `DOUBLE NULL` | 오디오에서 잰 BPM |
| `music_analysis` | `bpm_requested_min`·`_max` | `SMALLINT NULL` | 생성 때 요청한 BPM 범위. 템포 조건은 이 중간값을 먼저 본다 — 측정값은 옥타브 오류가 잦다 |
| `music_analysis` | `language` | `VARCHAR(32) NULL` | `korean`·`english`… |
| `music_analysis` | `category` | `VARCHAR(64) NULL` | 생성 스타일 원문(`female_korean_ballad`) |
| `music_section_analysis` | `energy` | `DOUBLE NULL` | 구간 RMS(0~1) |

전부 분석 파이프라인이 잰 값이고 쓰는 쪽도 하나라 `music_analysis` 에 모았다(곡 메타
`music` 이 아니라). 적재는 `PUT /api/v1/music/{id}/analysis`·`/section` 이 그대로 받고,
기존 곡은 `music_preprocessing analysis-values` 로 한 번 채운다.

`has_vocal` 은 NOT NULL 그대로다. 생성 음원은 생성 요청의 보컬 종류가 정답이라 늘 값이
있다 — 분류기가 판정을 못 한(확률 0.60 미만) 곡은 확인해 보니 전부 연주곡이었다.

> ⚠️ **개발 DB 에만 적용했다** (2026-09-23). 운영 DB 는 앱 배포 **전에** 사람이
> [migrate-ai-values-2026-09-23.sql](migrate-ai-values-2026-09-23.sql) 를 적용한다.
> NULL 컬럼 추가뿐이라 기존 행을 건드리지 않는다. 앱이 먼저 올라가면 엔티티가 없는
> 컬럼을 읽어 곡 조회가 실패한다.

## 곡 식별자가 둘이다

ClickHouse 쪽 분석 스키마는 곡을 **Jamendo 카탈로그의 `track_id`** 로 식별하고([glossary.md](glossary.md)), MySQL ERD 는 자체 **`music.music_id`** 로 식별한다. `listen_events.track_id` 와 `playlist_tracks.music_id` 가 같은 곡을 가리키는지, 둘을 잇는 매핑이 어디에 있는지는 아직 정해지지 않았다 → [open-questions.md](../open-questions.md).

MySQL 도메인을 만들 때는 **`music_id` 를 쓴다.** ERD 에 `track_id` 컬럼은 없다.

## 출처

[erd.sql](erd.sql) — ERD 도구 산출 DDL (2026-09-11 반입, 2026-09-15 · 2026-09-17 · **2026-09-21** 재산출).

**2026-09-22 의 파형 변경은 재산출이 아니라 수기 편집이다** (위 "2026-09-22 — 파형").
같이 딸려 온 잔 변경이 셋 있다 — `music_analysis.vocal_gender` 에 `DEFAULT NULL` 이
명시됐고(의미는 같다), `music.image_file_id` 에 주석이 붙었고,
`ai_chat_sessions` 위의 `AUTO_INCREMENT` 경고 주석이 빠졌다. 마지막 것은 잃은 게
없다 — 같은 근거가 `erd.sql` 머리말의 **수기 보정 #3** 에 그대로 있고, 규약대로
주석을 본문에 다시 넣지는 않는다(아래 2026-09-17 절 끝).

다만 이것을 확인하다 **문서 쪽 오류를 하나 찾아 고쳤다.** 위 "ERD 가 쓰는 공통
규약" 표의 PK 줄이 `ai_chat_sessions` 에 `AUTO_INCREMENT` 가 **없다**고 거꾸로
적고 있었다. 수기 보정 #3 이 그것을 붙인 뒤로 이 문장만 따라오지 않은 것이다.

**2026-09-21 재산출은 `music_analysis.vocal_gender` 한 줄을 고쳤다.**

| | 무엇 | 왜 |
|---|---|---|
| 정정 | `music_analysis.vocal_gender` 를 `ENUM('M', 'F', 'ETC')` → **`ENUM('M', 'F', 'MIX')`** | **운영 DB 와 코드가 `MIX` 를 쓰는데 `erd.sql` 만 `ETC` 로 뒤처져 있었다.** `VocalGender.MIX` 는 `4aa08e2`(S15P21A204-388)에서 도입됐고 그때 `erd.sql` 이 따라오지 않았다 |

**`ETC` 는 `users.gender` 의 값이다**(`erd.sql` 의 같은 이름 컬럼, `UserGender.ETC`). 두 컬럼이 혼동된 것이 원인이었고, 그래서 **`users.gender` 는 그대로 두었다** — 그쪽은 `ETC` 가 맞다.

**이 드리프트는 조용하지 않았다. 이미 개발 DB 를 오염시켰다.** 개발 DB 가 `erd.sql` 을 따라 `ETC` 로 만들어진 상태에서 코드가 `MIX` 를 쓰자 `Data truncated for column 'vocal_gender'` 가 났다. `VocalFilterSearchTest` 는 `@BeforeAll` 에서 픽스처를 만들다 이것으로 죽었고, `@AfterAll` 이 돌지 않아 **픽스처가 DB 에 남아 다음 실행까지 오염**시켰다(누적 36행 실측). **실패가 실행 순서에 따라 갈려 회귀를 오진하게 만들었다.**

> ⚠️ **운영 DB 에는 아무것도 하지 않았다.** 운영이 정본이고, 잘못된 방향으로 `ALTER` 하면 **정상 값(`MIX`)이 `ETC` 로 잘린다.** 이 재산출은 **문서를 현실에 맞춘 것**이지 운영 스키마를 바꾼 것이 아니다.

재산출 후 확인한 것:

- **수기 보정 #1~#4 가 전부 살아 있다** — 도구가 되돌리지 않았다(`music_analysis_instruments.music_section_analysis_id` 의 `AUTO_INCREMENT` 없음 / `music_section_analysis.external_id` UNIQUE / `ai_chat_sessions.ai_chat_session_id` 의 `AUTO_INCREMENT` / ngram FULLTEXT 인덱스 3개).
- 이번에는 **`erd.sql` 의 `--` 주석도 보존됐다**(2026-09-17 때는 사라졌다). 머리말의 수기 보정 목록이 그대로 있다.
- **개발 DB 와의 대조 차이가 2건 → 1건으로 줄었다.** 남은 1건은 아래를 본다.

### ⚠️ 남은 차이 1건 — `music_analysis_instruments` 의 `AUTO_INCREMENT`

재산출 후에도 **개발 DB 에만** `music_analysis_instruments.music_section_analysis_id` 에 `AUTO_INCREMENT` 가 붙어 있다(`erd.sql` 에는 없다 — 수기 보정 #1 이 맞다).

**이것은 위 "아직 DB 에 적용하지 않은 DDL" 표의 ① 이다.** 그 표는 운영·개발 **둘 다 미적용**이라고 적고 있으나, 실측에서는 **개발 DB 에 붙어 있다** — 표가 낡았거나 개발 DB 가 그 뒤 손으로 만들어진 것이다.

**대상은 DB 이지 `erd.sql` 이 아니다**(`erd.sql` 이 올바르다). 위험한 부류다 — `0` 이 들어오면 MySQL 이 새 번호를 발급하고 **외래키 검사를 통과**하며 **오류도 남기지 않는다**(위 "`AUTO_INCREMENT` 는 PK 자리에만" 절). 적용 DDL 은 그 표에 있다. **운영 DB 는 손대지 않는다.**

**2026-09-17 재산출은 `codes.display_name` 추가와 두 가지 결함 정정을 담았다.**

| | 무엇 | 왜 |
|---|---|---|
| 추가 | `codes.display_name VARCHAR(200) NOT NULL` | 화면에 그릴 이름과 AI 색인 값을 갈라 두기 위해서다. 장르 45종은 `code_name` 이 색인 원문(`poprock`), taxonomy 무드 9종은 `code_name` 이 화면용 한국어(`애상적인`)다 — **어느 쪽이 색인 값인지는 종류마다 반대다.** 배경은 [open-questions.md](../open-questions.md) 와 `ai_server/app/onboarding.py` |
| 정정 | `music_analysis_instruments.music_section_analysis_id` 의 `AUTO_INCREMENT` 제거 | FK 자리다. 위 "`AUTO_INCREMENT` 는 PK 자리에만" 참고 |
| 정정 | `music_section_analysis.external_id` 에 UNIQUE 추가 | 문자열 UID 와 숫자 id 를 잇는 유일한 근거다. 없으면 같은 값이 두 구간에 존재할 수 있다 |

> **이 재산출로 `erd.sql` 에서 손으로 넣었던 설명 주석이 사라졌다.** 도구가 `--`
> 주석을 보존하지 않는다. 커밋 `b6d5c7e`(S15P21A204-344)가 붙였던 근거는 위
> "`AUTO_INCREMENT` 는 PK 자리에만" 절로 옮겨 적었다 — **`erd.sql` 에 다시
> 손으로 주석을 넣지 않는다.** 지식은 이 문서에만 산다.

### 스키마 대조 도구

[`infra_k8s/deploy/schema-check.py`](../../../infra_k8s/deploy/schema-check.py) 가
`erd.sql` · DB · 엔티티 셋을 맞춰 본다. **여기서 뽑은 목록이 위 "아직 DB 에
적용하지 않은 DDL" 표다.**

```bash
# 이 PC 에서 운영·개발 스키마를 뜬다 (kubectl / docker)
bash infra_k8s/deploy/schema-dump-local.sh prod.tsv
MUPID_SCHEMA_SOURCE=dev bash infra_k8s/deploy/schema-dump-local.sh dev.tsv

# 대조. --unique-only 는 erd.sql 에 없는 일반 인덱스 소음을 뺀다
python3 infra_k8s/deploy/schema-check.py --schema prod.tsv --unique-only
```

**`extra`(AUTO_INCREMENT)와 인덱스를 반드시 같이 뽑는다.** 한동안 `column_type`
만 읽었는데, 그러면 `AUTO_INCREMENT` 는 `extra` 에 있고 인덱스는 다른 표에 있어
**둘 다 안 보인다.** 실제로 그 상태에서 위 두 결함이 정반대인데도 "차이 없음" 이
나왔다. `schema-dump.sh`(노드용)도 같은 두 조회를 한다.

[seed-codes.sql](seed-codes.sql) — `codes` 기준 데이터. 비어 있으면 온보딩이 503 이다. 기동 때 `bootstrap/BootstrapRunner` 가 같은 목록으로 빠진 것만 채우므로 보통은 손댈 일이 없다. 테이블 자체는 `ddl-auto` 가 `none` 이라 사람이 만들어야 한다.

`mubti` 16종도 같은 러너가 채운다. 이쪽은 SQL 파일이 없다 — `image_file_id` 가 NOT NULL 이라 행만 넣을 수 없고, 캐릭터 이미지를 오브젝트 스토리지에 올린 뒤 그 `file_id` 를 물려야 하기 때문이다. 이미지는 `src/main/resources/bootstrap/mubti-characters.zip` 에 WebP 로 들어 있고 파일 이름이 곧 네 축의 조합(`VTFC.webp`)이다. 그래서 이 표는 **앱을 한 번 띄워야** 채워진다.

캐릭터의 저장 키는 **`image/mubti/{CODE}.webp`** 다 — 다른 이미지와 같은 `image/{카테고리}/` 규약이다. 이 접두사가 빠져 있던 시절의 이미지는 기동할 때 `BootstrapRunner.repairMubtiImageKeys` 가 제자리로 옮긴다. 자세한 사정은 [mubti-report.md](mubti-report.md).
