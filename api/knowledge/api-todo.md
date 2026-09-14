# 앞으로 필요한 API

프론트엔드(`frontend/`)가 실제로 쓰는 데이터·동작과 현재 스프링 컨트롤러를 대조해
**아직 없는 API** 를 모았다. 모든 항목에 근거 경로를 달았다.

읽는 법: P0 은 지금 화면이 막혀 있는 것, P1 은 브라우저 `localStorage` 에만
있어서 기기를 바꾸면 사라지는 것, P2 는 관리 기능의 빈 구멍, P3 는 분석·추천
파이프라인이 준비되어야 붙는 것이다.

관련 문서 — [domain/erd.md](domain/erd.md) · [architecture/api-conventions.md](architecture/api-conventions.md) ·
[frontend/docs/server-integration.md](../../frontend/docs/server-integration.md) ·
[frontend/docs/player.md](../../frontend/docs/player.md)

## 지금 있는 것

| 영역 | 엔드포인트 | 상태 |
|---|---|---|
| 인증 | `POST/GET/DELETE /api/v1/auth` | 프론트 연결 완료 |
| 회원 | `POST /api/v1/user`, `GET`, `PATCH`, `DELETE`, `POST /system` | 연결 완료 |
| 이메일 | `GET/POST /api/v1/mail/verification`, `GET /{id}` | 연결 완료 |
| 비밀번호 재설정 | `GET/POST /api/v1/user/reset` | 화면 미연결 |
| 곡 조회 | `GET /api/v1/music`, `GET /api/v1/music/{musicId}` | 인증 불필요 · 연결 완료 |
| 콘텐츠 등록 | `POST/PATCH/DELETE` — music · album · artist · lyric | **album·artist·lyric 은 쓰기 전용** |
| 스트리밍 | 스트림·청크 생성/업로드/조회/다운로드 | 연결 완료 |
| 이미지 | `POST/GET/DELETE /api/v1/storage/image/{category}/{key}` | 연결 완료 |
| 이벤트 수집 | `POST /api/v1/events` | 프론트 트래커 있음 |
| 온보딩 | `GET /api/v1/onboarding/option`, `GET`, `POST /api/v1/onboarding` | 연결 완료 |
| 좋아요·관심없음 | `PUT/DELETE /api/v1/music/{id}/reaction`, `GET /api/v1/user/reaction[/music]` | 연결 완료 |
| 최근 감상 | `GET /api/v1/user/history` (적재는 이벤트 수집기가) | 연결 완료 |
| 시점 댓글 | `GET/POST /api/v1/music/{id}/comment`, `PATCH/DELETE /api/v1/comment/{id}` | 연결 완료 |
| 프로필 사진 | `POST/GET/DELETE /api/v1/storage/image/PROFILE/{userId}` | 연결 완료 |
| 아티스트·앨범 | `GET /api/v1/artist[/{id}]`, `GET /api/v1/album[/{id}]` | 인증 불필요 |
| 가사·분석 | `GET /api/v1/music/{id}/lyric`, `.../analysis` | 인증 불필요 |
| 플레이리스트 | `POST /api/v1/playlist` | **생성만** |

근거: `api/src/main/java/com/osundosun/mupid/api/**/controller/`

---

## P0 — 이것 때문에 화면이 막혀 있다

### 1. 곡 목록·검색·상세 조회 — ✅ 완료

`GET /api/v1/music`(목록·검색·페이지네이션)과 `GET /api/v1/music/{musicId}`
(상세)을 추가했다. 조회는 **인증 없이** 부를 수 있다 — 첫 방문자가 로그인 전에
곡을 둘러보기 때문이다. 등록 계열과 컨트롤러를 나눴다(`MusicQueryController`).

곡 길이는 **`music` 에 컬럼을 만들지 않고 완료된 스트림에서 끌어왔다.** ERD 를
건드리지 않는 쪽을 택했다. 같은 곡에 스트림이 여럿이면 비트레이트가 가장 높은
것이 기준이다. 스트림이 없으면 `duration_ms` 는 null 이고 `playable` 이 false 다.

앨범·아티스트 이미지도 목록에 표지가 뜨도록 `GET` 을 열었다. 프로필 사진은
개인 정보라 열지 않았다.

프론트는 `lib/stream-catalog.ts` 의 하드코딩을 지우고 이 API 를 부른다.
`genres`·`moods` 는 계약에는 있지만 **데이터가 아직 없어 빈 배열**이다.
`codes` 는 온보딩 때문에 채웠지만(P1-3), 곡에 장르·분위기를 붙이는
`music_genres`·`music_moods` 는 여전히 비어 있다.

<details>
<summary>원래 적었던 내용</summary>

`MusicController` 에 조회가 하나도 없었다. 화면이 어떤 `music_id` 를 재생할지
알 방법이 없어서 클라이언트에 곡 번호를 하드코딩해 두었다.

- `GET /api/v1/music` — 목록·검색·페이지네이션
  - 화면이 쓰는 필터: 검색어, 분위기(mood)
  - 근거: `frontend/hooks/use-mupid.ts` 의 `query` · `moodFilter`,
    `frontend/lib/explore.ts`
- `GET /api/v1/music/{musicId}` — 상세

필요한 필드는 `frontend/lib/mupid-data.ts` 의 `Track` 이 기준이다.

| 필드 | 출처 테이블 | 비고 |
|---|---|---|
| `music_id` `title` `release_date` | `music` | |
| `artist` `album` | `artists` `albums` | 이름과 이미지가 함께 필요하다 |
| `duration` | `streams.duration_ms` | 곡 길이를 `music` 이 갖고 있지 않다 |
| `image` | `albums.image_file_id` | 조회용 URL 규약 필요 |
| `genre` `mood` `tags` | `music_genres` `music_moods` | |
| `sections` | `music_section_analysis` | 구간 UI 가 이 값을 쓴다 |

곡 길이가 `music` 에 없어 스트림을 조인할지 컬럼을 추가할지 정해야 했다.

</details>

### 2. 스트림 메타에 채널 수 — ✅ 완료 (서버 변경 없음)

**API 도 ERD 도 건드리지 않았다.** Opus 프레임의 TOC 바이트가 모노/스테레오를
직접 알려준다 (RFC 6716 §3.1 — `s` 비트 `0x04`). 플레이어가 첫 프레임에서 읽어
디코더에 알려주고, 출력은 항상 스테레오로 만든다. 모노는 좌·우에 같은 소리가
들어간다.

raw Opus 프레임은 채널 매핑 패밀리 0(모노·스테레오)만 담는다. 5.1 같은
멀티채널은 `OpusHead` 가 있어야 풀리므로 `channels` 컬럼 하나로 해결되지
않는다 — 그때 가서 컨테이너부터 다시 볼 문제다.

- 근거: `frontend/lib/player/decoder.ts` 의 `channelsFromToc`

---

## P1 — 사용자 데이터가 브라우저에만 있다

프론트는 아래를 `localStorage` 한 덩어리(`mupid.sky.preferences.v1`)로 저장했다.
기기를 바꾸면 사라지고, 로그인해도 계정에 붙지 않았다.

**지금은 로그인하면 계정이 기준이다.** 온보딩·좋아요·최근 감상·시점 댓글·프로필
사진이 서버로 옮겨졌고, `localStorage` 는 **비로그인 체험용**으로만 남는다.
남은 것은 플레이리스트(동료 담당)와 저장한 구간(ERD 합의 필요)이다.

근거: `frontend/lib/services/index.ts`, `frontend/lib/services/contracts.ts` 의 `Preferences`

> `savePreferences` 는 지금도 **스냅샷 하나**를 통째로 덮어쓰는 계약이다.
> 비로그인 경로에만 남아 있어 당장 문제는 없지만, 이 계약 자체를 걷어낼지는
> 정해야 한다 — `frontend/docs/server-integration.md` 4번 항목.

### 3. 온보딩 설문 — ✅ 완료

- `GET /api/v1/onboarding/option` — 고를 수 있는 분위기·장르 (**인증 불필요**)
- `POST /api/v1/onboarding` — 닉네임·분위기·장르 제출 (재제출 시 교체)
- `GET /api/v1/onboarding` — 제출 여부·제출 내용

분위기·장르는 이름이 아니라 `codes` 표의 **번호**로 주고받는다. `codes` 는 기준
데이터라 사람이 넣어야 한다 → [domain/seed-codes.sql](domain/seed-codes.sql).
비어 있으면 `GET /option` 이 503 이다.

저장 형태는 **분위기 × 장르 조합**이다. 표의 두 코드 칼럼이 모두 NOT NULL 이라
집합 두 개를 그대로 담을 수 없어서다 —
[decision/0017](decision/0017-onboarding-survey-cross-product.md).
더 나은 스키마는 ERD 변경이 필요해 팀에 물어야 한다
([open-questions C6](open-questions.md)).

따라온 것:

- 온보딩 화면에 **장르 선택**을 추가했다. 장르 없이는 행을 만들 수 없다.
- 제출한 닉네임이 계정 닉네임(`users.nickname`)에도 반영된다.
- 프로필 화면의 "내가 좋아하는 분위기" 도 계정에 반영된다
  (`useMupid` 의 `updateMoods`).
- 로그인·세션 복원 시 계정에 있는 설문을 화면 상태로 끌어온다. 다른 기기에서
  답했으면 온보딩 화면을 건너뛴다.
- 비로그인은 예전처럼 브라우저 저장만 쓴다. 온보딩은 앱에 들어가는 관문이라
  서버가 없다고 막히면 안 된다.

### 4. 좋아요 / 관심 없음 — ✅ 완료

- `PUT /api/v1/music/{musicId}/reaction` — `LIKE` / `DISLIKE`
- `DELETE /api/v1/music/{musicId}/reaction` — 해제
- `GET /api/v1/user/reaction` — 반응한 곡 **번호**(페이징 없음)
- `GET /api/v1/user/reaction/music` — 반응한 곡 **목록**(곡 정보 포함)

조회를 둘로 나눈 이유: 화면이 어느 목록에서든 하트를 칠하려면 번호 전부가
필요하고(`int` 배열이라 가볍다), 라이브러리 화면은 곡 정보까지 필요하다.

한 곡에 반응은 하나다(`music_reactions` 의 복합키). 좋아요를 켜면 관심없음이
풀린다. `music.like_count` 는 반응과 함께 움직인다.

따라온 것:

- `MusicReaction.MusicReactionId` 의 키 컬럼이 `insertable = false` 라
  **INSERT 자체가 불가능**했다. 고쳤다 → [failure/database-and-jpa.md](failure/database-and-jpa.md)
- `@Modifying` 의 `flushAutomatically` 기본값(false) 때문에 같은 트랜잭션의
  저장이 사라졌다. 같은 문서에 기록했다.
- 타입이 안 맞는 쿼리 파라미터(`?page=abc`, `?type=LOVE`)가 500 이던 것을
  400 으로 고쳤다 → [failure/auth-and-api.md](failure/auth-and-api.md)
- 데모 곡(1~8)은 서버에 없어 브라우저에만 남는다. 로그인하면 계정 쪽이
  기준이 되어 브라우저 값을 덮는다.

### 5. 재생 기록 · 검색 기록 — ✅ 완료

- `GET /api/v1/user/history` — 최근 들은 곡. 같은 곡은 한 번만, 최신순

**적재 API 는 만들지 않았다.** 청취 이벤트(`POST /api/v1/events`)의 `PLAY_START`
· `SEARCH` 를 서버가 MySQL 사본으로도 남긴다. 화면이 같은 사실을 두 번 보내지
않게 하려는 것이다 →
[decision/0018](decision/0018-history-from-event-stream.md).

MySQL 은 서비스용(지금 이 사람의 최근 감상), ClickHouse 는 분석용이다.
중복이 아니라 용도가 다른 사본이다.

따라온 것:

- **프론트의 청취 이벤트 SDK 를 실제로 붙였다.** `lib/analytics` 는 만들어져만
  있고 어디서도 불리지 않았다. 전송을 콘솔 싱크에서 HTTP 로 바꾸고
  `PLAY_START`·`SEARCH` 를 연결했다. **나머지 20종은 아직 호출부가 없다.**
- `PlayHistory` 에 `@Table` 이 없어 `play_history` 라는 없는 표를 가리키고
  있었다. PK 타입도 어긋나 있었다 →
  [failure/database-and-jpa.md](failure/database-and-jpa.md)
- `PLAY_START` 는 곡을 튼 순간이지 들은 것이 아니다. 3초 만에 넘긴 곡도 남는다.
  즉시성을 택한 결과이고, 다시 볼 여지는 ADR 에 적었다.

### 6. 플레이리스트 (생성만 있음) — 🙋 동료 담당

**이 항목은 다른 사람이 맡았다. 중복 작업하지 말 것.**

`POST /api/v1/playlist` 하나뿐이다. 화면은 목록·이름 변경·삭제·곡 추가/제거를
모두 쓴다.

- `GET /api/v1/playlist` — 내 플레이리스트 목록
- `GET /api/v1/playlist/{playlistId}` — 수록곡 포함 상세
- `PATCH /api/v1/playlist/{playlistId}` — 이름 변경
- `DELETE /api/v1/playlist/{playlistId}`
- `POST/DELETE /api/v1/playlist/{playlistId}/track/{musicId}` — 수록곡 편집
- 근거: `frontend/hooks/use-mupid.ts` 의 `playlists` · `createPlaylist` ·
  `deletePlaylist` · `playPlaylist`, `frontend/components/mupid/collection.tsx`

### 7. 저장한 구간 (moment)

**테이블 자체가 없다.** 이 서비스의 핵심 기능("좋아한 구간 남기기")인데
`moments` 에 해당하는 스키마가 ERD 에 없다. `music_section_analysis` 는 분석
결과지 사용자가 저장한 구간이 아니다.

- 스키마부터 정해야 한다: `user_id` · `music_id` · `start_ms` · `end_ms` · `section_label`
- `POST /api/v1/music/{musicId}/moment`, `GET /api/v1/user/moments`,
  `DELETE /api/v1/moment/{momentId}`
- ERD 수정은 **반드시 팀에 먼저 물을 것** ([AGENTS.md](../AGENTS.md))
- 근거: `frontend/hooks/use-mupid.ts` 의 `moments` · `saveMoment` ·
  `playSavedMoment`, `frontend/lib/services/contracts.ts` 의 `SavedMoment`

### 8. 곡 시점 댓글 — ✅ 완료

- `GET /api/v1/music/{musicId}/comment` — 시점 오름차순
- `POST /api/v1/music/{musicId}/comment` — `timestamp_ms` · `content`
- `PATCH /api/v1/comment/{commentId}` — 내용만. 시점은 못 바꾼다
- `DELETE /api/v1/comment/{commentId}` — 소프트 삭제

`mine` 은 **서버가 판정한다.** 화면이 `user_id` 를 비교하게 두면 비교를
빠뜨렸을 때 남의 댓글에 수정 버튼이 뜬다.

조회도 인증이 필요하다. 댓글은 재생 화면에서만 보이고 재생이 로그인을 요구한다.

따라온 것:

- 프론트가 `localStorage` 를 쓰지 않는다. 이제 **다른 사람의 댓글도 보인다.**
- **댓글 길이가 500자에서 100자로 줄었다.** `music_comments.content` 가
  `varchar(100)` 이다. 화면이 500자를 받아 놓고 서버가 거절하는 것보다 낫다.
  더 길게 받으려면 ERD 변경이라 팀에 물어야 한다.
- 순수 규칙(`validateComment`·`commentable`)을 `lib/comment-rules.ts` 로 뺐다.
  `lib/track-comments.ts` 가 API 를 부르게 되어 통신 없이 규칙만 쓰려는 곳이
  그쪽을 끌어오지 않게 하려는 것이다.
- `MusicComment.isDeleted` 에 `@Builder.Default` 가 없어 NOT NULL 위반이 날
  자리였다. `Album` 이 밟았던 것과 같다.
- 시점이 곡 길이를 넘는지는 **검사하지 않는다.** 곡 길이가 스트림에서 오는
  값이라(P0-1) 스트림 없는 곡에는 기준이 없다. 화면이 먼저 막는다.

### 9. 프로필 사진 — ✅ 완료

**조회 URL 규약**: `image_url` 은 서버 기준 **상대 경로**다
(`/api/v1/storage/image/{CATEGORY}/{키}`). 절대 URL 이 아니므로 프론트는
`baseUrl` 과 합쳐 쓰면 된다. 카테고리는 **대문자**다.

규약을 만드는 자리는 하나다 — `StorageImageService.imageUrl(...)`. 곡 목록의
앨범·아티스트 이미지도 같은 메서드를 쓴다.

사진이 없으면 `/api/v1/storage/image/PROFILE/default` 를 가리킨다. 그 키에는
파일이 없고 서버가 기본 이미지를 돌려주므로 `<img>` 에 그대로 넣어도 된다.
화면은 이 값이 `default` 로 끝나면 "사진 없음" 으로 보고 마스코트를 띄운다.

**프로필 사진은 공개 경로가 아니다.** 개인 정보라 `SecurityConfig` 에서 열지
않았다. `<img src>` 는 `Authorization` 을 달 수 없으므로 프론트가 토큰을 붙여
바이트를 받고 object URL 로 바꿔 쓴다(`lib/profile-photo.ts`). 앨범·아티스트
이미지는 공개라 URL 을 그대로 쓴다.

업로드 키는 **본인 유저 번호**여야 한다. 아니면 403 이다.

고친 것 다섯 (전부 [failure/](failure/) 에 기록):

- `image_url` 이 저장 키를 통째로 넣어 **부를 수 없는 주소**였다
- 업로드가 인증 principal 에 값을 넣어 **DB 에 저장되지 않았다**
- principal 의 지연 연관을 읽어 `GET /api/v1/user` 가 500 이었다
- 삭제가 파일을 두 번 지워 롤백됐다
- **남의 번호를 키로 올려 남의 사진을 덮어쓸 수 있었다**

---

## P2 — 관리 기능의 빈 구멍

### 10. 아티스트 · 앨범 · 가사 조회 — ✅ 완료

- `GET /api/v1/artist`, `GET /api/v1/artist/{artistId}`
- `GET /api/v1/album`(`q`·`artist_id` 필터), `GET /api/v1/album/{albumId}` (수록곡 포함)
- `GET /api/v1/music/{musicId}/lyric`

전부 **인증 불필요**다. 곡 조회와 같은 이유다.

앨범 상세의 수록곡은 `곡 목록 조회` 와 **완전히 같은 표현**이라 화면이 목록에서
쓰던 코드를 그대로 쓴다.

따라온 것:

- 프론트의 **가짜 가사를 걷어냈다.** `components/mupid/lyrics.tsx` 가 곡 분위기로
  지어낸 문장을 보여주고 있었다. 이제 서버 가사를 시간과 함께 보여주고, 없으면
  "아직 등록된 가사가 없어요" 라고 말한다.
- `MusicSummaryAssembler` 를 갈라냈다. 곡 표현을 만드는 코드가 `MusicService` 에
  있으면 앨범 서비스가 곡 서비스를, 곡 서비스가 앨범 서비스를 부르는 **순환
  의존**이 된다. 조립기는 리포지토리만 알면 되므로 누구나 부를 수 있다.
- 쿼리 파라미터 이름은 `artist_id` 로 snake_case 다. SNAKE_CASE 설정은 JSON
  본문에만 걸려서 컨트롤러가 직접 적어 준다.

### 11. 곡 분석 메타 조회 — ✅ 완료

- `GET /api/v1/music/{musicId}/analysis` — 구간·보컬·악기

**분석 파이프라인이 아직 없어 대부분 `analyzed: false`** 로 온다. 404 가 아니다 —
곡은 있는데 분석만 없는 것은 오류가 아니다. 화면은 이 값을 보고 구간 UI 를
띄울지 정한다.

악기는 곡이 아니라 **구간**에 달린다(`music_analysis_instruments` 의 외래키가
`music_section_analysis_id` 다).

따라온 것:

- `MusicSectionAnalysis.index` 가 MySQL 예약어인데 백틱이 없었다. 넣지 않으면
  INSERT·SELECT 가 문법 오류로 터진다. `Chunk` 가 밟았던 것과 같다.
- `MusicAnalysisInstrument` 의 필드 이름이 `moodCodeId` 였다(복사 실수).
  `instrumentCode` 로 고쳤다.
- 프론트가 재생 대상이 바뀔 때 분석을 받아 구간을 갈아 끼운다. 분석 전이면
  임시 구간(곡을 둘로 나눈 것)이 그대로 남는다.
- `codes` 에 `INSTRUMENT` 가 하나도 없다. 악기 목록은 분석 파이프라인이
  정할 몫이라 시드에 넣지 않았다.

---

## P3 — 분석·추천 파이프라인이 준비되어야 붙는다

### 12. 일일 추천

`daily_recommendations` · `daily_recommendation_tracks` 엔티티 있음, API 없음.
홈의 "오늘의 Mupid Mix" 가 이 자리다.

- `GET /api/v1/recommendation/daily`
- 근거: `api/.../recommendation/repository/`, `frontend/components/mupid/home.tsx`

### 13. 취향 리포트 · MUBTI

`reports` · `mubti` · `listening` 엔티티 있음, API 없음. 리포트 화면은 지금
`moments`/`history` 길이로 **로컬에서 계산**한다.

- `GET /api/v1/report?period=week|month|year&offset=N` — 화면이 기간·오프셋을
  이미 쓰고 있다
- 구간별 선호도·키워드·MUBTI 가 응답에 필요하다
- 집계는 ClickHouse 쪽이다 → [architecture/analytics-clickhouse.md](architecture/analytics-clickhouse.md)
- 근거: `frontend/components/mupid/collection.tsx:329-460`

### 14. AI 채팅 추천

`ai_chat_sessions` · `ai_chat_messages` 엔티티 있음, API 없음. 프론트는 외부
AI 를 부르지 않고 로컬 규칙으로 목록을 정렬한다.

- `POST /api/v1/ai/chat` — 프롬프트 + 현재 곡 → 추천 곡 목록 + 답변
- `GET /api/v1/ai/chat/{sessionId}` — 대화 이력
- **브라우저를 내부 FastAPI/Kafka/DB 에 직접 붙이지 않는다.** 추천 어댑터는
  Spring API 만 호출한다 → `frontend/docs/server-integration.md`
- 근거: `frontend/lib/mupid-data.ts` 의 `deriveChatRecommendations`,
  `frontend/hooks/use-mupid.ts` 의 `messages` · `sendMessage`

### 15. 비슷한 구간 · 파생곡

"이 구간으로 파생곡 찾기" 가 지금은 데모 데이터 정렬이다.

- `GET /api/v1/music/{musicId}/similar?start_ms=&end_ms=`
- 근거: `frontend/components/mupid/discovery.tsx:286,308`

---

## 같이 정리하면 좋을 결함

API 신설은 아니지만 프론트 연결에서 실제로 걸린 것들이다.

| 증상 | 내용 |
|---|---|
| 인증 메일 링크가 죽어 있다 | 서버가 넣는 경로는 `/verify/email`(`EmailVerificationPurpose.mailUrl`), 프론트 라우트는 `/email/verify`. 게다가 링크에 `verification_id` 가 없어 링크만으로는 인증을 끝낼 수 없다 |
| 잘못된 이메일이 500 | Gmail 이 `Invalid To header` 로 거절하면 `GmailException` 이 도메인 오류로 매핑되지 않아 `MUPID_INTERNAL_SERVER_ERROR` 가 나간다 → [failure/auth-and-api.md](failure/auth-and-api.md) |
| 회원 탈퇴가 하드 삭제 | 정책은 soft delete → [decision/0013](decision/0013-soft-delete-policy.md) |

## 정할 것

- ~~곡 길이를 `music` 에 둘 것인가, 스트림에서 조인할 것인가 (P0-1)~~ →
  스트림에서 조인하기로 했다. 목록이 커지면 다시 볼 것
- ~~페이지네이션 규약~~ → `{ items, page, size, total_elements, total_pages }`,
  `page` 는 0부터. `Music / 곡 목록 조회` 가 첫 사례다
- 저장한 구간 테이블 스키마 (P1-7) — ERD 변경이라 팀 합의가 먼저다
- ~~재생/검색 기록을 MySQL 테이블과 ClickHouse 이벤트 중 어디에 둘 것인가 (P1-5)~~
  → 둘 다 둔다. 화면은 한 번만 보내고 수집기가 갈라 쓴다. MySQL 은 서비스용,
  ClickHouse 는 분석용 → [decision/0018](decision/0018-history-from-event-stream.md)
- 온보딩 설문을 한 줄에 답 하나로 담을 것인가 (P1-3) — ERD 변경이라 팀 합의가
  먼저다 → [decision/0017](decision/0017-onboarding-survey-cross-product.md)
