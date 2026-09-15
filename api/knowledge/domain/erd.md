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

## ERD 가 쓰는 공통 규약

| 항목 | 규칙 | 예외 |
|---|---|---|
| PK | `<단수명>_id`, `INT AUTO_INCREMENT` | `ai_chat_messages`·`play_histories` 는 `BIGINT`. `ai_chat_sessions` 는 `AUTO_INCREMENT` 가 없다. `music_analysis` 는 PK 가 곧 FK(`music_id`) 다 |
| 복합 PK | 매핑·이력 테이블은 두 컬럼을 묶는다 — `chunks`(`stream_id`,`index`), `listening`, `music_genres`, `music_moods`, `music_reactions`, `music_section_analysis_mood`, `music_analysis_instruments`, `daily_recommendation_tracks`(`…_id`,`rank_order`), `playlist_tracks`(`playlist_id`,`track_order`) | |
| 테이블명 | 복수형 snake_case | `music`(불가산), `mubti`, `onboarding_survey`, `listening`(단수) |
| 감사 컬럼 | `created_at` / `updated_at` `DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP` | 이력성 테이블은 `created_at` 만 둔다. `chunks`·`listening`·`lyrics`·`mubti`·`music_analysis`·`music_section_analysis`·`streams` 는 둘 다 없다 |
| enum | MySQL `ENUM(...)` 컬럼 | — |
| soft delete | **세 가지가 섞여 있다** — `is_deleted TINYINT(1)` (`albums`, `artists`, `music`, `music_comments`, `object_storage_files`, `ai_chat_sessions`) / `deleted_at DATETIME` (`playlists`) / 상태 enum (`users.status = WITHDRAW`) | |
| 시간 | `DATETIME` (초 단위) | `play_histories.created_at`·`search_histories.created_at` 는 **`DATETIME(6)`** — 같은 초에 여러 건이 쌓여 정렬이 흔들리던 것을 막는다. 날짜만 쓰는 곳은 `DATE` (`music`·`albums`.`release_date`, `daily_recommendations.recommendation_date`, `reports.start_date`·`end_date`) |
| ms 단위 | `INT UNSIGNED` 컬럼에 밀리초를 담는다 (`duration_ms`, `time_ms`, `timestamp_ms`, `listening_ms`) | `music_section_analysis.start_time_ms`·`end_time_ms` 만 부호 있는 `INT` |
| FK | `ALTER TABLE ... ADD CONSTRAINT fk_<테이블>_<대상>` 으로 뒤에 몰아서 건다. `ON DELETE` 는 `ai_chat_messages`·`ai_chat_sessions` 두 개만 `CASCADE`, 나머지는 기본(`RESTRICT`) | |
| 인덱스 | `uq_*` 유니크 / `ix_*`·`ux_*` 일반 | **이름이 `uq_` 라고 유니크인 것은 아니다.** `uq_song_genre`·`uq_song_mood`·`uq_user_email` 은 `CREATE INDEX` 다. 반대로 `ix_song_reactions_`·`ux_mubti_*`·`ux_play_histories_*` 는 유니크다. 이름은 ERD 도구가 붙인 것이라 믿지 말고 `CREATE (UNIQUE )?INDEX` 를 직접 읽는다 |

재산출된 DDL 에는 **도구가 남긴 흔적**이 몇 군데 있다. 고치기 전에 팀에 묻되,
읽을 때 당황하지 않도록 적어 둔다.

- `music_analysis_instruments.music_section_analysis_id` 에 `AUTO_INCREMENT` 가
  붙어 있다. 복합 PK 의 앞 컬럼이자 FK 인데 자동 증가일 수는 없다.
- `ix_song_reactions_`(`music_reactions`)·`ux_play_histories_play_history_id`
  는 PK 와 같은 컬럼에 걸린 중복 인덱스다.
- `reports` 의 유니크 인덱스 이름이
  `analysis_reports_copy_uq_analysis_report_period` 다. 예전 테이블명
  (`analysis_reports`)을 복사하며 생긴 이름이다.

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

2026-09-15 재산출 기준이다. 재산출이 `erd.sql` 을 코드·실제 DB 쪽으로 끌어와서
차이가 많이 줄었다 — `onboarding_survey` 의 `_code_id`, 무드·장르의 `codes`
통합, `chunks` 복합 PK, `music_section_analysis_id` 이름은 **모두 해소됐다.**

| 테이블 | ERD | 현재 코드 | 영향 |
|---|---|---|---|
| `music.release_date` · `albums.release_date` | `DATE` | `LocalDateTime` | 엔티티가 시:분:초까지 들고 API 도 `2026-09-11T00:00:00` 로 주고받는다. MySQL 이 `DATE` 로 잘라 저장하므로 **시각 부분은 버려진다.** 읽어 오면 언제나 `T00:00:00` 이다 |
| `ai_chat_messages.ai_chat_message_id` | `BIGINT` | `Integer` | 21억 건을 넘길 일은 없지만, 타입이 어긋난 채로 두면 나중에 `Long` 으로 바꾸는 순간 API 응답 타입도 함께 바뀐다 |
| `users.nickname` | `VARCHAR(10)` | 길이 미지정 | 요청 검증이 2~10자라 실질 충돌은 없다 |
| `users.email` | `VARCHAR(320)`, 유니크 아님 (`CREATE INDEX uq_user_email`) | 길이 미지정, 중복은 애플리케이션에서만 막는다 | 동시 요청에서 같은 이메일이 두 번 들어갈 수 있다 |
| `users.birth_year` | `SMALLINT` | `Integer` | 범위 안이면 무해 |
| `email_verifications.token` | `VARCHAR(6)` + **UNIQUE** | 길이·유니크 없음 | 6자리 랜덤이 충돌하면 발송이 실패한다 |
| `onboarding_survey` | `mood_code_id` · `genre_code_id` **NOT NULL** | 같음 | 한 줄에 답 하나를 담을 수 없다. 지금은 분위기 × 장르 조합으로 우회한다 → [decision/0017](../decision/0017-onboarding-survey-cross-product.md) |

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
`MusicSummary.imageUrl`, `SecurityConfig` 의 `/api/v1/storage/image/TRACK/*` 공개,
`MusicRepository` 의 `LEFT JOIN FETCH m.imageFile`.

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

## 곡 식별자가 둘이다

ClickHouse 쪽 분석 스키마는 곡을 **Jamendo 카탈로그의 `track_id`** 로 식별하고([glossary.md](glossary.md)), MySQL ERD 는 자체 **`music.music_id`** 로 식별한다. `listen_events.track_id` 와 `playlist_tracks.music_id` 가 같은 곡을 가리키는지, 둘을 잇는 매핑이 어디에 있는지는 아직 정해지지 않았다 → [open-questions.md](../open-questions.md).

MySQL 도메인을 만들 때는 **`music_id` 를 쓴다.** ERD 에 `track_id` 컬럼은 없다.

## 출처

[erd.sql](erd.sql) — ERD 도구 산출 DDL (2026-09-11 반입, 2026-09-15 재산출).

[seed-codes.sql](seed-codes.sql) — `codes` 기준 데이터. `ddl-auto` 가 `none` 이라 사람이 직접 넣는다. 비어 있으면 온보딩이 503 이다.
