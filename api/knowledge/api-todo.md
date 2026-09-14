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
| 콘텐츠 등록 | `POST/PATCH/DELETE` — music · album · artist · lyric | **쓰기 전용** |
| 스트리밍 | 스트림·청크 생성/업로드/조회/다운로드 | 연결 완료 |
| 이미지 | `POST/GET/DELETE /api/v1/storage/image/{category}/{key}` | 연결 완료 |
| 이벤트 수집 | `POST /api/v1/events` | 프론트 트래커 있음 |
| 플레이리스트 | `POST /api/v1/playlist` | **생성만** |

근거: `api/src/main/java/com/osundosun/mupid/api/**/controller/`

---

## P0 — 이것 때문에 화면이 막혀 있다

### 1. 곡 목록·검색·상세 조회

`MusicController` 에 조회가 하나도 없다. 화면이 어떤 `music_id` 를 재생할지
알 방법이 없어서 지금은 **클라이언트에 곡 번호를 하드코딩**해 두었다.

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

**곡 길이가 `music` 에 없다는 점을 먼저 정해야 한다.** 지금은 스트림 메타에만
있어서, 목록에서 길이를 보여주려면 스트림을 조인하거나 `music` 에 컬럼을
추가해야 한다 → [open-questions.md](open-questions.md) 에 추가할 것.

- **막고 있는 것**: `frontend/lib/stream-catalog.ts`(하드코딩 카탈로그) 제거,
  데모 곡 8개를 실제 카탈로그로 교체
- **근거**: `frontend/lib/stream-catalog.ts`, `frontend/docs/player.md` 의 "남은 제약"

### 2. 스트림 메타에 채널 수

`StreamInfo` 에 채널 수가 없어 플레이어가 스테레오(2)로 고정해 두었다. 모노
음원은 좌·우에 같은 소리를 넣어 처리하지만, 5.1 등이 들어오면 어긋난다.

- `streams` 에 `channels` 추가 또는 스트림 응답에 포함
- 근거: `frontend/lib/player/engine.ts` 의 `channels: 2` 주석

---

## P1 — 사용자 데이터가 브라우저에만 있다

프론트는 아래 전부를 `localStorage` 한 덩어리(`mupid.sky.preferences.v1`)로
저장한다. 기기를 바꾸면 사라지고, 로그인해도 계정에 붙지 않는다.

근거: `frontend/lib/services/index.ts`, `frontend/lib/services/contracts.ts` 의 `Preferences`

> `savePreferences` 는 지금 **스냅샷 하나**를 통째로 덮어쓰는 계약이다. 서버로
> 옮길 때는 항목별 API 로 쪼개야 한다 —
> `frontend/docs/server-integration.md` 4번 항목.

### 3. 온보딩 설문

`onboarding_survey` 테이블과 `OnboardingSurvey` 엔티티·리포지토리는 있는데
컨트롤러가 없다. 첫 방문자는 온보딩을 마쳐야 앱에 들어가는데, 그 결과가
서버에 남지 않는다.

- `POST /api/v1/onboarding` — 닉네임·선호 분위기 제출
- `GET /api/v1/onboarding` — 제출 여부(화면 진입 분기에 쓴다)
- 근거: `frontend/hooks/use-mupid.ts` 의 `completeOnboarding` ·
  `onboardingSubmittedAt`, `api/.../onboarding/repository/OnboardingSurveyRepository.java`

### 4. 좋아요 / 관심 없음

`music_reactions` 테이블·엔티티 있음, API 없음.

- `PUT /api/v1/music/{musicId}/reaction` — `LIKE` / `DISLIKE` / 해제
- `GET /api/v1/user/reactions` — 좋아요 목록(라이브러리 화면)
- 근거: `frontend/hooks/use-mupid.ts` 의 `liked` · `ignored` · `toggleLike`,
  `api/.../music/entity/MusicReaction.java`

### 5. 재생 기록 · 검색 기록

`play_histories` · `search_histories` 테이블·엔티티 있음, API 없음.

- `GET /api/v1/user/history` — 최근 감상(홈 화면 "최근 감상")
- 기록 적재는 `POST /api/v1/events` 로 대신할 수 있는지 먼저 정한다.
  이벤트 수집기는 ClickHouse 로 가고 이 테이블들은 MySQL 이라 **같은 사실이
  두 곳에 쌓이게 된다.** → 정할 것
- 근거: `frontend/hooks/use-mupid.ts` 의 `history`,
  `api/.../history/repository/`

### 6. 플레이리스트 (생성만 있음)

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

### 8. 곡 시점 댓글

`music_comments` 테이블과 `MusicComment` 엔티티는 있는데 API 가 없다. 프론트는
`localStorage` 에 직접 넣고 있고, 서비스 계층도 우회한다.

- `GET /api/v1/music/{musicId}/comment` — 시점 오름차순
- `POST /api/v1/music/{musicId}/comment` — `at_ms` · 본문
- `PATCH`/`DELETE /api/v1/comment/{commentId}` — 본인 것만
- 근거: `frontend/lib/track-comments.ts`(파일 주석이 "실제 작성자 신원과 인가는
  나중에 서버에서 와야 한다"고 인정한다),
  `frontend/components/mupid/track-comments.tsx`

### 9. 프로필 사진

업로드 자체는 범용 스토리지 API 로 된다. 남은 것은 **조회 URL 규약**이다.
`UserGetInfoResponse.imageUrl` 이 무엇을 돌려주는지 프론트가 그대로 쓸 수 있어야
한다(절대 URL인지, 스토리지 경로인지).

- 근거: `api/.../user/response/UserGetInfoResponse.java`,
  `frontend/lib/services/contracts.ts` 의 `readPhoto`/`savePhoto`

---

## P2 — 관리 기능의 빈 구멍

### 10. 아티스트 · 앨범 · 가사 조회

셋 다 `POST`/`PATCH`/`DELETE` 만 있고 조회가 없다. 관리 화면을 만들거나 곡
상세를 그리려면 필요하다.

- `GET /api/v1/artist`, `GET /api/v1/artist/{artistId}`
- `GET /api/v1/album`, `GET /api/v1/album/{albumId}` (수록곡 포함)
- `GET /api/v1/music/{musicId}/lyric`
- 근거: `api/.../music/controller/`, `frontend/components/mupid/lyrics.tsx`
  (지금은 곡 분위기로 만든 **가짜 가사**를 보여준다)

### 11. 곡 분석 메타 조회

`music_analysis` · `music_section_analysis` · 장르/무드/악기 매핑 테이블은 모두
엔티티가 있는데 노출하는 API 가 없다. 플레이어의 구간 UI·"이 곡이 추천된 이유"가
이 값을 필요로 한다.

- `GET /api/v1/music/{musicId}/analysis`
- 근거: `api/.../music/entity/MusicAnalysis.java`,
  `frontend/components/mupid/discovery.tsx`

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

- 곡 길이를 `music` 에 둘 것인가, 스트림에서 조인할 것인가 (P0-1)
- 저장한 구간 테이블 스키마 (P1-7) — ERD 변경이라 팀 합의가 먼저다
- 재생/검색 기록을 MySQL 테이블과 ClickHouse 이벤트 중 어디에 둘 것인가 (P1-5)
- 페이지네이션 규약 — 지금 어떤 목록 API 도 없어서 정해진 형식이 없다
