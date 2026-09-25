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
| 곡 일괄 조회 | `GET /api/v1/music/bulk?ids=` | 액세스 토큰 필요 · 프론트 연결은 S15P21A204-446 |
| 콘텐츠 등록 | `POST/PATCH/DELETE` — music · album · artist · lyric | **album·artist·lyric 은 쓰기 전용** |
| 스트리밍 | 스트림·청크 생성/업로드/조회/다운로드 | 연결 완료 |
| 이미지 | `POST/GET/DELETE /api/v1/storage/image/…` | 연결 완료 · 조회는 `{fileId}`, 쓰기는 `{category}/{key}` |
| 이벤트 수집 | `POST /api/v1/events` | 프론트 트래커 있음 |
| 온보딩 | `GET /api/v1/onboarding/option`, `POST /api/v1/onboarding` | 연결 완료 · **제출은 계정당 한 번** |
| 온보딩(배치) | `GET /api/v1/user/{userId}/onboarding` | **`ROLE_SYSTEM` 전용** · 추천 서버가 콜드스타트에 씀 |
| 좋아요·관심없음 | `PUT/DELETE /api/v1/music/{id}/reaction`, `GET /api/v1/user/reaction[/music]` | 연결 완료 |
| 최근 감상 | `GET /api/v1/user/history` (적재는 이벤트 수집기가) | 연결 완료 |
| 시점 댓글 | `GET/POST /api/v1/music/{id}/comment`, `PATCH/DELETE /api/v1/comment/{id}` | 연결 완료 |
| 프로필 사진 | `POST/GET/DELETE /api/v1/storage/image/PROFILE/{userId}` | 연결 완료 |
| 아티스트·앨범 | `GET /api/v1/artist[/{id}]`, `GET /api/v1/album[/{id}]` | 인증 불필요 |
| 가사·분석 | `GET /api/v1/music/{id}/lyric`, `.../analysis` | 인증 불필요 |
| 파형 | `GET /api/v1/music/{id}/waveform`, `PUT` (`ROLE_SYSTEM`) | 연결 완료 · **없으면 404 이고 화면이 직접 계산한다** ([decision/0026](decision/0026-waveform-is-precomputed-by-batch.md)) |
| 일일 추천 | `GET /api/v1/recommendation/daily` | 조회만 (데이터 없음) |
| 플레이리스트 | `GET/POST /api/v1/playlist`, `GET/PATCH/DELETE /api/v1/playlist/{id}`, `POST /api/v1/playlist/{id}/track`, `DELETE /api/v1/playlist/{id}/track/{musicId}` | 연결 완료 |

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
- `POST /api/v1/onboarding` — 분위기·장르 제출. **계정당 한 번**, 재제출은 409
- `GET /api/v1/user/{userId}/onboarding` — 제출 내용. **`ROLE_SYSTEM` 전용**

분위기·장르는 이름이 아니라 `codes` 표의 **번호**로 주고받는다. `codes` 가 비어
있으면 `GET /option` 이 503 이다. 기동 때 `bootstrap/BootstrapRunner` 가 빠진
코드를 채우므로 보통은 비지 않는다 — 손으로 넣으려면
[domain/seed-codes.sql](domain/seed-codes.sql).

저장 형태는 **분위기 × 장르 조합**이다. 표의 두 코드 칼럼이 모두 NOT NULL 이라
집합 두 개를 그대로 담을 수 없어서다 —
[decision/0017](decision/0017-onboarding-survey-cross-product.md).
더 나은 스키마는 ERD 변경이 필요해 팀에 물어야 한다
([open-questions C6](open-questions.md)).

따라온 것:

- 온보딩 화면에 **장르 선택**을 추가했다. 장르 없이는 행을 만들 수 없다.
- 로그인·세션 복원 시 `GET /api/v1/user` 의 `onboarding_submitted` 로 분기한다.
  다른 기기에서 답했으면 온보딩 화면을 건너뛴다.
- 비로그인은 예전처럼 브라우저 저장만 쓴다. 온보딩은 앱에 들어가는 관문이라
  서버가 없다고 막히면 안 된다.

**콜드스타트 입력으로 못박았다** (2026-09-15). 이 설문은 이력이 없는 사람의 첫
추천을 만드는 값이지 계속 고쳐 쓰는 취향 설정이 아니다 —
[decision/0017](decision/0017-onboarding-survey-cross-product.md) 의 "고친 것".

- 제출은 **계정당 한 번**이다. 두 번째는 `MUPID_ONBOARDING_ALREADY_SUBMITTED`(409).
- **본인 조회 API 를 없앴다.** 화면이 답을 되돌려 받을 일이 없다. 진입 분기에
  쓰는 불리언만 `GET /api/v1/user` 에 실었다.
- 마이페이지의 취향 편집 칩을 지웠다. 누를 때마다 재제출하고 있었다.
- 설문이 **닉네임을 받지 않는다.** 회원가입이 이미 받는다. 화면도 3단계에서
  2단계로 줄었다.

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
- `GET /api/v1/user/history/search` — 최근 검색어. 같은 말은 한 번만, 최신순
- `DELETE /api/v1/user/history/search?keyword=` — 한 줄 지우기
- `DELETE /api/v1/user/history/search/all` — 전체 지우기

삭제는 **그 말의 기록을 전부** 지운다. 목록의 한 줄이 기록 하나가 아니라 같은
말의 묶음이라, 하나만 지우면 화면은 그대로인데 서버만 줄어든다.

전체 삭제는 길을 따로 냈다. 파라미터 없는 `DELETE .../search` 를 전체 삭제로
쓰면, 화면이 `keyword` 를 빠뜨린 것과 전부 지우려는 것이 서버에서 똑같아
보인다. 빈 `keyword` 는 400 이다.

없던 말을 지워도 성공이다. 다른 탭에서 먼저 지웠다면 이미 원하는 상태인데,
404 를 내면 사용자는 이미 이룬 일로 실패를 본다. 플레이리스트 곡 빼기가 404 를
내는 것과는 다른 판단이다 — 그쪽은 수록곡이 화면과 어긋난 것을 알려야 한다.

`SEARCH` 이벤트는 **제출할 때만** 나간다. 처음에는 입력이 멎으면 보냈는데,
그러면 "무" · "무피" · "무피드" 가 전부 검색어로 남아 최근 검색어가 앞자락으로
뒤덮였다. 지금은 엔터나 검색 버튼이 유일한 계기이고, 그 자리에서 큐를 비워
방금 찾은 말이 바로 목록에 뜬다.

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

### 6. 플레이리스트 — ✅ 완료

> ⚠ **이 항목은 원래 동료가 맡기로 되어 있었다.** 목록·상세·삭제·곡 추가는 그쪽이
> 먼저 만들었고, 프론트를 붙이면서 비어 있던 **곡 빼기와 이름 변경** 두 개를 채웠다.
> 겹쳐 작업했을 수 있으니 합칠 때 확인한다.

| 엔드포인트 | 만든 쪽 |
| --- | --- |
| `GET /api/v1/playlist` — 내 플레이리스트 목록 | 동료 |
| `GET /api/v1/playlist/{playlistId}` — 수록곡 포함 상세 | 동료 |
| `POST /api/v1/playlist` — 생성 | 동료 |
| `DELETE /api/v1/playlist/{playlistId}` — 소프트 삭제 | 동료 |
| `POST /api/v1/playlist/{playlistId}/track` — 곡 추가 | 동료 |
| `DELETE /api/v1/playlist/{playlistId}/track/{musicId}` — 곡 빼기 | 이번 작업 |
| `PATCH /api/v1/playlist/{playlistId}` — 이름 변경 | 이번 작업 |

프론트는 `frontend/lib/playlists.ts` 가 이 API 를 감싸고 `hooks/use-mupid.ts` 가
낙관적 갱신 + 실패 시 되돌리기로 쓴다. **`Playlist.id` 는 서버의 `playlist_id` 다** —
예전의 `Date.now()` 지역 번호는 청취 이벤트의 `playlist_id`(UInt32)에 들어가지 않아
그 이벤트가 실린 배치를 통째로 거부시켰다 → [domain/listening-events.md](domain/listening-events.md).

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
(`/api/v1/storage/image/{fileId}`). 절대 URL 이 아니므로 프론트는
`baseUrl` 과 합쳐 쓰면 된다.

규약을 만드는 자리는 하나다 — `StorageImageService.imageUrl(ObjectStorageFile)`.
곡 목록의 앨범·아티스트 이미지도 같은 메서드를 쓴다.

**한때는 저장 키로 조회했다.** 그때는 카테고리가 **대문자**인 것이 규약이었고
(`SecurityConfig` 의 공개 경로가 대문자로 적혀 있었다), 조회 URL 이 저장 키를 되짚어
만들어졌다. 키 모양이 곧 계약이라 키를 손으로 만든 코드 하나가 주소를 조용히
`null` 로 만들었고, 그것이 `/report` 의 MuBTI 캐릭터가 뜨지 않던 원인이었다.
지금은 **파일 번호**로 만들기 때문에 키 모양이 주소에 관여하지 않는다.

사진이 없으면 `/api/v1/storage/image/PROFILE/default` 를 가리킨다. 그 자리에는
파일이 없고(파일이 아니라 classpath 자산이다) 서버가 기본 이미지를 돌려주므로,
`<img src>` 에 그대로 넣어도 된다. 화면은 이 값이 `/PROFILE/default` 로 끝나면
"사진 없음" 으로 보고 마스코트를 띄운다.

**프로필 사진은 공개 경로가 아니다.** 개인 정보라 `SecurityConfig` 에서 열지
않았다. `<img src>` 는 `Authorization` 을 달 수 없으므로 프론트가 토큰을 붙여
바이트를 받고 object URL 로 바꿔 쓴다(`lib/profile-photo.ts`). 앨범·아티스트
이미지는 공개라 URL 을 그대로 쓴다.

업로드 키는 **본인 유저 번호**여야 한다. 아니면 403 이다. 삭제도 같은 규칙을 지난다 —
권한 판단이 카테고리 정책(`ImageAccessPolicy`) 하나로 모여 있어서, 업로드와 삭제가
같은 검사를 지나지 않으면 잠그지 않은 쪽으로 우회된다. 카탈로그 이미지
(`ARTIST`·`ALBUM`·`TRACK`·`MUBTI`)의 업로드·삭제는 `ROLE_SYSTEM` 이상만 한다.

고친 것 여섯 (전부 [failure/](failure/) 에 기록):

- `image_url` 이 저장 키를 통째로 넣어 **부를 수 없는 주소**였다
- 업로드가 인증 principal 에 값을 넣어 **DB 에 저장되지 않았다**
- principal 의 지연 연관을 읽어 `GET /api/v1/user` 가 500 이었다
- 삭제가 파일을 두 번 지워 롤백됐다
- **남의 번호를 키로 올려 남의 사진을 덮어쓸 수 있었다**
- **카탈로그 이미지는 로그인만 하면 누구나 덮어쓰고 지울 수 있었다** — 프로필에만
  검사가 있었다

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

### 12. 일일 추천 — ✅ 조회만 완료

- `GET /api/v1/recommendation/daily?date=YYYY-MM-DD`

**만드는 것은 배치다.** `daily_recommendations` 는 `DailyRecommendationBatch` 가 채우고,
조회는 저장된 것을 읽기만 한다. 먼저 매일 도는 데이터 배치(`music_preprocessing/run_batch.sh`)
가 분석이 성공한 날 마지막 단계로 `POST /api/v1/recommendation/daily/start`(`ROLE_SYSTEM`,
202 로 바로 돌아오고 생성은 서버 안에서 돈다)를 부른다. 정오 스케줄은 **예비**다 — 배치가
만든 날에는 전원 건너뛰고, 배치가 실패했거나 분석이 실패한 날에만 만든다. 두 길이 같은 Redis
선점을 잡는다(S15P21A204-448). 수동 재실행은 `POST /api/v1/recommendation/daily/generate`. 배치를 아직 타지 않은 사람이라도
`groups` 가 빈 배열로 나가는 일은 이제 거의 없다 — 아래 폴백을 본다.
**어느 쪽이든 404 가 아니다.**

요청한 날짜에 추천이 없으면 **가장 최근 추천이 있는 날**로 되돌아간다.
파이프라인이 매일 도는 보장이 없는데 빈 홈을 보여주면 곤란해서다. 응답의
`date` 가 실제로 무엇을 돌려줬는지 말한다.

**그 되돌림도 빈 경우의 폴백을 넣었다** (2026-09-21, S15P21A204-408). 방금
가입해 배치를 한 번도 타지 않은 사람에게는 되돌아갈 날짜 자체가 없어 홈이
빈 채로 남았다. 저장된 추천이 하나도 없으면 온보딩에서 고른 장르·분위기에
해당하는 곡 중 **조회수 상위 4곡**을 그 자리에서 골라 내려준다.

- **저장하지 않는다.** `daily_recommendations` 는 배치의 산출물이고, 여기에
  끼워 넣으면 배치가 "이미 만들었다" 고 판단해 진짜 추천을 건너뛴다. 그래서
  응답의 `recommendation_id` 가 `0` 이고 `title` 이 `첫 모먼트` 다 — 배치가
  만든 것(`오늘의 모먼트`)과 구분된다.
- 조회수는 MySQL `music.play_count` 를 본다. 재생 이벤트의 정본은 ClickHouse
  지만 요청 경로에서 분석 클러스터를 때리지 않는다 — 장르·분위기 태그가
  MySQL 에만 있어 한 질의로 묶이지도 않는다.
- 넓히는 순서는 장르 ∧ 분위기 → 장르 ∨ 분위기 → 카탈로그 전체 조회수 상위다.
  **한 곡이라도 나오면 거기서 멈춘다** — 네 곡이 안 되어도 모자란 대로 내린다.
  묶음 하나에 사유가 하나뿐인 계약이라, 취향과 무관한 곡으로 채우면 그 사유가
  절반은 거짓이 된다. 온보딩을 안 한 사람은 바로 마지막 갈래다(예외가 아니다).
- `groups` 가 비는 경우는 이제 카탈로그에 살아 있는 곡이 하나도 없을 때뿐이다.

따라온 것:

- `DailyRecommendation` 의 PK 필드가 `recommendationId` 인데 컬럼은
  `daily_recommendation_id` 다. `@Column` 이 없어 **없는 컬럼을 가리키고
  있었다.**
- `recommendation_date` 가 `DATE` 인데 엔티티는 `LocalDateTime` 이었다.
  `LocalDate` 로 고쳤다 — 그대로 두면 쓸 때 잘린다.
- 화면 연결은 아직이다. 넣을 데이터가 없어 붙여도 빈 화면이다.

> **14~15 는 지금 만들 수 없다.** 없는 인프라와 정해지지 않은 것에 걸려 있다.
> 아래 각 항목에 무엇이 막고 있는지 적어 두었다. 껍데기만 먼저 만들면
> 계약이 잘못 굳는다.

### 13. 취향 리포트 · MuBTI — ✅ 구현

`GET /api/v1/report?date=YYYY-MM-DD` · 화면 연결까지 끝났다.
여러 날을 한 번에 받는 `GET /api/v1/report/daily?from=&to=` 가 S15P21A204-379 에서 더해졌다.

막고 있던 것은 **API 서버에 ClickHouse 조회 계층이 없다**는 것이었다. 리포트가
그 첫 사용처가 됐다 — `analytics/repository/ListenAnalyticsRepository`. 서비스
경로에서 분석 클러스터를 직접 읽되 의존을 이 화면 하나에 가두는 쪽으로 정했다
→ [decision/0019](decision/0019-report-reads-clickhouse-directly.md).

- 곡별 청취량은 `PLAY_PROGRESS` 하트비트에서 집계한다 — 재생기가 돈 시간이
  아니라 **실제로 귀에 닿은 시간**이다
- 키워드는 분위기·장르를 청취 시간으로 가중한다
- MuBTI 네 축은 **조회할 때마다 계산한다.** 저장하는 것은 그날의 결론
  (`reports.mubti_id`)과 곡별 청취량(`listening`)뿐이다 →
  [decision/0015](decision/0015-derive-interpretation-at-query-time.md)
- 집계·판정 규칙 전부 → [domain/mubti-report.md](domain/mubti-report.md)

따라온 것:

- **기간은 일간뿐이다.** 원래 이 항목은 `period=week|month|year&offset=N` 을
  적어 두었지만, 화면이 실제로 쓰는 것은 하루치다. 채우지 않을 파라미터를
  미리 열면 계약이 잘못 굳는다.
  > 2026-09-20 (S15P21A204-379): **주·월 "집계" 는 여전히 없다.** 다만 이미 있는 일별
  > 리포트를 **여러 날 묶어 주는** `GET /api/v1/report/daily?from=&to=` 가 더해졌다.
  > 주간 MUBTI 대화상자가 일곱 날을 그리면서 요청을 날마다 따로 보내고 있었다
  > (`listening-calendar.tsx` 의 `CONCURRENCY = 3`) — 스냅샷 없는 지난 날짜는 그때마다
  > ClickHouse 를 다시 때렸다. 위 문장의 뜻(채우지 않을 `period` 를 열지 않는다)은
  > 그대로다: 늘어난 것은 기간의 **단위**가 아니라 **묶음**이다.
  > 규칙·상한·오류는 `domain/mubti-report.md` §2-A 와 `api/docs/Report/` 참고.
- **`mubti` 표 16행은 기동 때 `BootstrapRunner` 가 채운다** (S15P21A204-344).
  `image_file_id` 가 NOT NULL 이라 행만 넣을 수 없어 손으로 넣던 `seed-mubti.sql`
  은 없앴다. 비어 있으면 503 이라는 점은 그대로다.
- **구간 기반 신호를 하나도 못 썼다.** `track_sections` 를 채우는 파이프라인이
  없어 `play_section_coverage` · `v_track_skips` 가 전부 빈 결과다. MuBTI
  A축(보컬/멜로디)이 가장 약한 축이 된 이유다.
- 저장소의 **첫 `@EnableScheduling`** 이 들어왔다 (자정 배치). 새 `@Scheduled`
  를 붙일 때는 여러 인스턴스에서 무슨 일이 생기는지 먼저 본다.
- 트리맵이 진짜 면적 비례가 됐다. 예전에는 모든 타일이 같은 크기였고 값도
  가짜였다 (`frontend/lib/treemap.ts`).

### 14. AI 채팅 추천 — ⛔ 막힘

`ai_chat_sessions` · `ai_chat_messages` 엔티티 있음, API 없음. 프론트는 외부
AI 를 부르지 않고 로컬 규칙으로 목록을 정렬한다.

- `POST /api/v1/ai/chat` — 프롬프트 + 현재 곡 → 추천 곡 목록 + 답변
- `GET /api/v1/ai/chat/{sessionId}` — 대화 이력
- **브라우저를 내부 FastAPI/Kafka/DB 에 직접 붙이지 않는다.** 추천 어댑터는
  Spring API 만 호출한다 → `frontend/docs/server-integration.md`
- **막는 것**: 어떤 모델을 어디서 부를지 정해진 것이 없다. 키 보관·비용·
  지연·실패 시 동작까지 함께 정해야 한다 → 정할 것
- 근거: `frontend/lib/mupid-data.ts` 의 `deriveChatRecommendations`,
  `frontend/hooks/use-mupid.ts` 의 `messages` · `sendMessage`

### 15. 비슷한 구간 · 파생곡 — ⛔ 막힘

"이 구간으로 파생곡 찾기" 가 지금은 데모 데이터 정렬이다.

- `GET /api/v1/music/{musicId}/similar?start_ms=&end_ms=`
- **막는 것**: 벡터 DB 가 없다. `music_section_analysis.external_id` 가
  "벡터DB ID" 라고 적혀 있지만 그런 저장소가 어디에도 설정돼 있지 않고,
  구간 분석 자체도 비어 있다 → 정할 것
- 근거: `frontend/components/mupid/discovery.tsx:286,308`

---

## 같이 정리하면 좋을 결함

API 신설은 아니지만 프론트 연결에서 실제로 걸린 것들이다.

| 증상 | 내용 |
|---|---|
| 인증 메일 링크가 죽어 있다 | 서버가 넣는 경로는 `/verify/email`(`EmailVerificationPurpose.mailUrl`), 프론트 라우트는 `/email/verify`. 게다가 링크에 `verification_id` 가 없어 링크만으로는 인증을 끝낼 수 없다 |
| 잘못된 이메일이 500 | Gmail 이 `Invalid To header` 로 거절하면 `GmailException` 이 도메인 오류로 매핑되지 않아 `MUPID_INTERNAL_SERVER_ERROR` 가 나간다 → [failure/auth-and-api.md](failure/auth-and-api.md) |
| 회원 탈퇴가 하드 삭제 | 정책은 soft delete → [decision/0013](decision/0013-soft-delete-policy.md) |

## 로그인하지 않으면 서비스를 쓸 수 없다

화면 가드(`frontend/lib/routes.ts`)가 `/`·`/login`·`/signup` 말고는 전부 막는다.
곡 목록도 재생도 로그인한 뒤의 일이다. 브라우저 저장으로 세션을 되살리던
체험 로그인은 없앴다.

> **서버는 아직 곡 조회를 공개로 둔다.** `GET /api/v1/music` · `/artist` ·
> `/album` · `/music/{id}/lyric` · `/music/{id}/analysis` · `/onboarding/option`
> 은 permitAll 이다. 사용자 데이터가 아니고, 화면이 로그인 전에 카탈로그를
> 미리 받아 두기 때문이다. 계정 없이 카탈로그를 긁어가는 것까지 막으려면
> 이들을 닫고 **로그인 뒤 카탈로그를 다시 받도록** 화면을 고쳐야 한다 → 정할 것

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
- 서비스 경로에서 ClickHouse 집계를 어떻게 읽을 것인가 (P3-13) — 직접 조회 ·
  집계 테이블 복제 · 배치로 MySQL 적재 중 무엇인지
- AI 추천을 어떤 모델로 어디서 부를 것인가 (P3-14) — 키 보관·비용·지연·
  실패 시 동작까지
- 곡 조회 API 를 로그인 뒤에만 열 것인가 — 화면은 이미 로그인을 요구한다.
  닫으려면 로그인 시점에 카탈로그를 다시 받는 처리가 함께 필요하다
- 벡터 DB 를 무엇으로 둘 것인가 (P3-15) — `music_section_analysis.external_id`
  가 무엇을 가리키는지부터 정해야 한다
