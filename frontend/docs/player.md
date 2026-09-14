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

청크 길이는 **서버 메타의 `duration_ms`** 로 잡는다. Opus 인코더가 세그먼트
앞에 넣는 프리스킵(보통 6.5ms)은 디코딩 후 앞에서 잘라낸다. 그대로 두면
청크를 이어붙인 자리마다 몇 ms 씩 밀려 소리가 튄다.

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
