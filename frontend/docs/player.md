# API 클라이언트와 스트리밍 플레이어

## 구성

```
lib/store.ts              관찰 가능한 최소 스토어 (useSyncExternalStore)
lib/api/transport.ts      전송 계층 추상화 + fetch 구현
lib/api/client.ts         ApiClient — 토큰·오류 정규화·재발급 큐
lib/api/endpoints.ts      엔드포인트 래퍼 (경로와 응답 모양)
lib/api/session.ts        세션 스토어 · login/logout/restoreSession
lib/player/rtp.ts         .rtp 컨테이너 파서 (RTP 헤더 제거)
lib/player/decoder.ts     Opus → PCM (WebCodecs AudioDecoder)
lib/player/ring-buffer.ts 수신 ↔ 디코딩 완충
lib/player/engine.ts      스케줄러 · Seek · 페이드 · 프리로드
lib/player/index.ts       usePlayer() 훅
```

## 전송 계층을 바꾸려면

`ApiClient` 는 `Transport` 인터페이스만 안다. fetch 의 `Request`/`Response`
타입은 상위 계층에 새어 나가지 않는다.

```ts
const client = new ApiClient({ transport: createAxiosTransport() });
```

`Transport` 는 `send(TransportRequest): Promise<TransportResponse>` 하나다.
응답 본문은 `responseType`(`json` / `text` / `arraybuffer` / `none`)에 맞춰
파싱해 돌려주면 된다. 헤더 키는 소문자로 정규화한다.

## 토큰 재발급 큐

액세스 토큰은 **메모리에만** 둔다. 새로고침하면 사라지고, HttpOnly 쿠키의
리프레시 토큰으로 다시 받는다.

401 이 오면:

1. 재발급(`GET /api/v1/auth`)을 시작한다. 여러 요청이 동시에 401 이어도
   실제 재발급은 **한 번만** 돈다.
2. 재발급이 도는 동안 들어온 요청은 FIFO 로 줄을 선다. 토큰 없이 내보내면
   어차피 401 이 되고 재발급을 여러 번 돌게 만든다.
3. 성공하면 밀려 있던 요청을 들어온 순서대로 내보내고, 401 이던 요청도
   새 토큰으로 한 번 더 보낸다.
4. 실패하면 대기열 전체를 같은 오류로 거절하고 `onSessionExpired` 를 부른다.
   같은 세션에서 재발급을 다시 시도하지 않는다.

검증: 일부러 토큰을 망가뜨리고 세 요청을 동시에 보내면 재발급 1회 · 세 요청
모두 재시도 성공.

## 오류 처리

서버 오류는 두 경로로 온다. 둘 다 봐야 한다.

| 경로 | 내용 |
|---|---|
| 본문 | `{ code, message, error_details }` — 화면에 보여줄 문구 |
| `X-Mupid-Error` 헤더 | 오류 코드. 본문이 사라지는 경로(nginx auth_request)를 위한 것 |

**오류 응답의 본문은 `responseType` 과 무관하게 항상 JSON 으로 읽는다.**
`responseType: 'none'` 이나 `'arraybuffer'` 로 선언했다고 본문을 건너뛰면
서버가 준 "이메일이 중복되었습니다." 같은 문구를 통째로 잃고 화면에는
"요청에 실패했어요" 만 남는다. 다른 Transport 구현으로 갈아끼울 때도 이 규칙을
지켜야 한다.

검증 오류(`MUPID_INVALID_REQUEST_BODY`)의 `error_details` 는 필드별 메시지
맵이다. **키는 camelCase**(`birthYear`) — Map 키라서 snake_case 변환 대상이
아니다. 순서가 보장되지 않으므로 하나만 뽑지 말고 전부 보여준다.

## 스트리밍 재생

### 왜 `<audio>` 나 `decodeAudioData` 가 아닌가

서버가 주는 청크는 컨테이너가 아니라 **RTP 에서 벗겨낸 raw Opus 프레임**이다
(`RTPK` 자체 포맷, 12바이트 RTP 헤더 + 20ms Opus 프레임). `<audio>` 도
`decodeAudioData` 도 Ogg/WebM/MP4 같은 컨테이너만 받는다. 그래서 WebCodecs
`AudioDecoder` 로 직접 푼다.

**WebCodecs 가 없으면 재생할 수 없다.** `state.supported` 가 false 면 화면에서
안내해야 한다 (Chrome 94+ / Edge / Safari 16.4+ / Firefox 130+).

### 흐름

```
GET /api/v1/music/{id}/stream    재생 가능한 스트림 (DONE 만)
GET /api/v1/chunk/{streamId}     청크 메타 (index · duration_ms · file_hash)
GET /api/v1/chunk/{sid}/{index}  청크 바이트
      ↓
   링버퍼 (8칸)
      ↓ 하나씩 꺼내
   RTP 헤더 제거 → Opus 프레임
      ↓
   AudioDecoder → PCM → AudioBuffer
      ↓
   AudioBufferSourceNode 를 시각에 맞춰 예약
```

청크 길이는 **서버 메타의 `duration_ms`** 로 잡는다.

### 청크 이음새 — 프리롤

청크는 **하나의 연속된 Opus 비트스트림**을 6초씩 자른 조각이다. ffmpeg 의
segment 먹서는 인코더를 끊지 않고 자르기만 한다. 그런데 재생은 Seek 때문에
청크마다 디코더를 새로 만들어 푼다.

그래서 청크마다 디코더가 처음부터 켜지고, 앞머리 수백 샘플이 0 에서 올라오는
워밍업 구간이 된다. 앞 청크의 꼬리는 반대로 잘려 나간다 — 6초마다 딸깍거리는
소리의 정체다.

**앞 청크의 마지막 480ms 를 먼저 먹여 디코더 상태를 만든 뒤 그 출력만
버린다** (`PRE_ROLL_FRAMES`). 얼마나 필요한지는 실측으로 정했다. 같은 구간을
끊지 않고 해독한 파형과의 오차(RMS, 신호 RMS 는 0.02~0.08):

| 프리롤 | 80ms | 160ms | 240ms | 320ms | 480ms |
|---|---|---|---|---|---|
| 오차 | 0.0020 | 0.00014 | 0.0000067 | 0.0000005 | 0 |

RFC 7845 가 Seek 때 권하는 80ms 로는 신호의 5% 까지 어긋나 조용한 대목에서
또렷이 들린다. 480ms 면 비트 단위로 같아진다.

프리스킵(보통 6.5ms)을 따로 보정하지는 않는다. 모든 청크에서 같은 기준으로
창을 잡으면 조각들은 비트스트림 상에서 정확히 맞닿는다 — 어긋나는 것은
타임라인 전체가 6.5ms 밀리는 것뿐이고, 그건 들리지 않는다.

꼬리는 **받는 즉시** 기억해 둔다 (`keepFrames`). 디코딩할 때 기억하면, 여러
청크를 동시에 받는 사이 받는 순서와 푸는 순서가 어긋나 그 이음새만 프리롤
없이 풀린다.

그리고 **푸는 순서는 도착 순서가 아니라 프리롤이 준비된 순서다** (`drain`).
`fill` 은 청크 두셋을 한꺼번에 받는데 응답이 보낸 순서대로 돌아온다는 보장이
없다 — 모바일 회선에서는 뒤 청크가 앞 청크를 앞지르는 일이 잦다. 앞 청크가
아직 오는 중이면 뒤 청크는 기다렸다가 꼬리가 생긴 뒤에 푼다. 다만 기다림에는
한도를 둔다 (`PRE_ROLL_WAIT_SEC`, 2초) — 응답 하나가 매달려 있을 때 잡음
한 번을 피하려고 정적을 몇 초씩 듣는 것은 남는 장사가 아니다.

**Seek 로 건너뛴 자리에서는 앞 청크를 함께 받는다** (`fill`). 그 자리에는
꼬리가 없어 프리롤을 붙일 수 없기 때문이다. 둘을 나란히 요청하므로 기다림이
두 배가 되지는 않고, 받아 온 앞 청크는 버리지 않고 풀어서 들고 있는다 —
조금 되감을 때 바로 들린다. 받기에 실패하면 다시 받지는 않고(`tailTried`)
그 이음새만 프리롤 없이 간다.

그래서 프리롤이 없는 자리는 이제 **곡의 첫 청크뿐**이다. 거기서는 앞 청크가
아예 없고, 워밍업 구간이 곡이 시작하는 순간과 겹쳐 들리지 않는다.

### 버퍼 수명

| 시점 | 동작 |
|---|---|
| 재생이 끝난 청크 | **버리지 않는다.** 뒤로 Seek 하면 바로 쓴다 |
| 곡이 바뀔 때 | 현재 곡의 PCM 을 통째로 비운다 |
| 대기열 곡 | 앞 5초만 미리 받아 둔다 (`setQueue`) |

### 상태

`usePlayer()` 가 돌려주는 `status`:

- `loading` — 스트림·청크 메타를 받는 중
- `buffering` — 재생할 지점의 버퍼가 아직 없다. **화면은 스피너**
- `playing` / `paused` / `ended` / `error` / `idle`

`isBusy` 는 `loading || buffering` 이다. 스피너 조건으로 그대로 쓰면 된다.

### 페이드

Seek 과 곡 전환은 120ms 페이드 아웃 → 전환 → 페이드 인으로 이어진다.
페이드용 GainNode 와 볼륨용 GainNode 를 나눠 둔다. 하나로 합치면 페이드 도중
볼륨을 바꿀 때 서로의 램프를 덮어써 소리가 튄다.

| 시점 | 동작 |
|---|---|
| Seek · 곡 전환 (`seek`, `load`) | 120ms 페이드 아웃 → 자리 이동 → 120ms 페이드 인 |
| 재생 시작 · 일시정지 해제 (`play`) | 120ms 페이드 인 |
| 버퍼가 없어 기다렸다 이어질 때 | 받는 즉시 `play()` 로 이어지며 페이드 인 |
| 일시정지 · 정지 (`pause`, `stop`) | 10ms 로 끊는다 (`STOP_FADE_SEC`) — 즉시 멈추되 절벽은 없게 |
| 곡이 끝까지 울렸을 때 | 페이드 없음. 곡 자신의 끝이라 줄이면 오히려 어색하다 |

페이드 인은 **소리가 실제로 나기 시작하는 시각**부터 올린다 (`fadeIn(at)`).
예약은 `currentTime` 이 아니라 50ms 뒤에 걸리므로, 램프를 지금부터 올리면
첫 소리가 이미 게인 40% 를 넘은 채로 튀어나온다 — 무음에서 -8dB 로 수직으로
뛰는 셈이라 Seek 마다 앞머리가 '툭' 한다.

### AudioContext

`getPlayer()` 는 브라우저에서 처음 불릴 때 AudioContext 와 게인 그래프를
만든다. `usePlayer()` 를 쓰는 컴포넌트가 마운트되는 순간 준비가 끝난다.
브라우저 정책상 **소리는 사용자 제스처가 있어야** 나므로 `play()` 안에서
`resume()` 한다. 컨텍스트를 미리 만들어 두었기 때문에 첫 재생 지연이 없다.

## 사용 예

```tsx
const player = usePlayer();

// 다음 곡들의 앞 5초를 미리 받아 둔다
useEffect(() => player.setQueue([12, 13]), [player]);

<button onClick={() => player.load(11, { autoplay: true })}>재생</button>
{player.isBusy && <Spinner />}
{!player.supported && <p>이 브라우저는 스트리밍 재생을 지원하지 않아요.</p>}
<input type="range" max={player.durationSec} value={player.positionSec}
       onChange={(e) => player.seek(Number(e.target.value))} />
```

## 개발 환경

`vite.config.ts` 가 `/api` 를 `MUPID_API_ORIGIN`(기본 `http://localhost:9900`)
으로 프록시한다. 서버에 CORS 설정이 없고 리프레시 토큰 쿠키가 `SameSite=Strict`
+ `Secure` 라, 브라우저에게 같은 출처로 보이게 하는 것이 가장 단순하다.

운영 배포는 프런트와 API 가 이미 같은 도메인을 쓰므로 추가 설정이 필요 없다.

## 회원가입 이메일 인증

서버가 보내는 것은 **6자리 인증 번호**(`AGGLBE` 같은 영숫자, 15분 유효)다.
메일 링크가 아니라 번호를 화면에 입력하는 흐름이다.

```
1. 이메일 입력 → [이메일 인증하기]
     GET /api/v1/mail/verification?email=...   → verification_id
2. 메일의 번호 입력 → [인증 확인]
     POST /api/v1/mail/verification { verification_id, token }
3. 나머지 정보 입력 → [회원가입]
     POST /api/v1/user { ..., email_verification_id }
```

`verification_id` 는 1번 응답에만 있고 메일 링크에는 들어 있지 않다. 그래서
**메일을 요청한 그 화면에서 번호를 입력해야** 한다. 다른 기기에서 링크를 열면
이어서 진행할 수 없다.

## 남은 제약

1. **곡 목록은 `GET /api/v1/music` 에서 받는다.** `lib/stream-catalog.ts` 의
   `fetchCatalog()` 가 서버 응답을 `Track` 으로 옮긴다. `Track.musicId` 가 있는
   곡만 청크 스트리밍으로 재생되고, 없는 곡(데모 8곡)은 기존 `<audio>` 로
   재생된다. 카탈로그가 채워지면 데모 목록을 지우면 된다.

2. **장르·분위기·구간은 아직 빈 값이다.** 서버 응답에 필드는 있지만
   `codes`·`music_genres`·`music_moods`·`music_section_analysis` 가 전부 비어
   있다. 구간은 임시로 곡을 둘로 나눠 만든다 — 분석 메타 API 가 생기면
   교체한다 (api-todo P2-11).

3. 채널 수는 **Opus 프레임의 TOC 바이트에서 읽는다** (RFC 6716 §3.1, `s` 비트
   `0x04`). 서버 메타에 채널 수가 없어도 비트스트림이 알려주므로 스키마를
   건드릴 필요가 없다. 출력은 항상 스테레오이고 모노는 좌·우에 같은 소리를
   넣는다. 실제 모노 파일로 확인했다 (TOC=1채널 → 출력 2채널, 양쪽 샘플 동일).

   raw Opus 프레임은 채널 매핑 패밀리 0(모노·스테레오)만 담는다. 5.1 등은
   `OpusHead` 가 있어야 하므로 지금 구조로는 재생할 수 없다.

4. **메일 링크는 아직 죽어 있다.** 서버가 넣는 경로는 `/verify/email`
   (`EmailVerificationPurpose.mailUrl`)인데 프런트 라우트는 `/email/verify` 다.
   경로를 맞추더라도 `app/email/verify` 화면은 `emailVerificationService.verify`
   가 늘 `unavailable` 을 돌려주는 껍데기이고, 링크에 `verification_id` 가 없어
   그것만으로는 인증을 끝낼 수 없다. 지금은 번호 입력 흐름으로 대체한다.
