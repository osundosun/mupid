/**
 * API 서버 주소. 비워 두면 같은 출처로 보낸다.
 *
 * 개발에서는 Vite 프록시가 `/api` 를 nginx 로 넘기고(`vite.config.ts`), 배포된
 * 웹(`ec2`)은 nginx 가 화면과 API 를 같은 출처로 서빙한다 — 둘 다 빈 값이다.
 * 화면과 API 의 출처가 갈리는 배포에서만 값을 넣는다.
 *
 * 앱에서는 비워 둔 채로 둔다. 경로만 받은 요청에 원본 주소를 붙이는 일은 Rust 가
 * 한다 (`app/src/config.rs`) — 주소를 한 곳에서만 바꾸기 위해서다.
 *
 * `session.ts` 가 아니라 여기 있는 것은 **`<img src>` 도 이 값이 필요하기**
 * 때문이다 (`lib/platform/image.ts`). `session.ts` 는 플랫폼 계층을 불러오므로
 * 그쪽에서 되짚어 부르면 순환이 된다.
 */
export const apiBaseUrl: string = (
  (import.meta as { env?: Record<string, string> }).env?.VITE_MUPID_API_BASE ?? ''
).replace(/\/$/, '');
