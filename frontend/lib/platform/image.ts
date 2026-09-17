/**
 * 앨범 아트 주소 변환.
 *
 * 앱에서는 웹뷰가 직접 인터넷에 나가지 않는다. 원격 이미지도 Rust 가 받아
 * `mupidimg` 스킴으로 돌려준다 (`app/src/lib.rs`). 그래야 CSP 를 원격 도메인에
 * 열지 않아도 되고, 이미지가 인증 뒤로 옮겨가도 고칠 곳이 한 군데뿐이다.
 *
 * 스킴의 실제 주소 모양은 플랫폼마다 다르다 — 안드로이드·윈도우는
 * `http://mupidimg.localhost/…`, macOS·리눅스는 `mupidimg://localhost/…` 다.
 * 그래서 직접 문자열을 만들지 않고 Tauri 가 웹뷰에 심어 둔 변환기를 쓴다.
 *
 * 웹에서는 주소를 그대로 둔다 — 화면과 API 가 같은 출처이기 때문이다. 둘이
 * 갈리는 배포에서는 `VITE_MUPID_API_BASE` 가 채워지고, 그때만 경로 앞에 그 주소를
 * 붙인다. `fetch` 는 `ApiClient` 가 같은 값으로 붙여 주지만 `<img src>` 는 아무도
 * 붙여 주지 않아 그대로 두면 화면 쪽 출처에서 엉뚱한 응답을 받아 온다.
 */

import { apiBaseUrl } from '../api/base-url';
import { isTauri } from './bridge';

type Internals = { convertFileSrc?: (path: string, protocol: string) => string };

const SCHEME = 'mupidimg';

/** URL 안전 base64. 주소의 경로 자리에 그대로 들어가야 한다. */
const encode = (value: string): string =>
  btoa(String.fromCharCode(...new TextEncoder().encode(value)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');

/**
 * 서버가 주는 이미지인가.
 *
 * 서버는 `/api/v1/storage/image/ALBUM/...` 처럼 **경로로** 준다. 웹에서는 같은
 * 출처라 그대로 열리지만, 앱의 출처는 `http://tauri.localhost` 라 그 경로에는
 * 아무것도 없다 — 앨범 커버가 전부 깨지던 것이 이 때문이다.
 *
 * 앱이 들고 있는 파일(`/sky-background.webp`)과 data URL 은 건드리지 않는다.
 */
const isServerImage = (url: string) =>
  /^https?:\/\//i.test(url) || url.startsWith('/api/');

export function toImageSource(url: string | null | undefined): string {
  if (!url) return '';
  if (!isTauri()) {
    // 같은 출처면 `apiBaseUrl` 이 빈 문자열이라 주소가 그대로 남는다.
    return url.startsWith('/api/') ? `${apiBaseUrl}${url}` : url;
  }
  if (!isServerImage(url)) return url;

  const internals = (window as unknown as { __TAURI_INTERNALS__?: Internals })
    .__TAURI_INTERNALS__;
  const convert = internals?.convertFileSrc;
  // 변환기가 없을 만한 상황은 Tauri 가 규약을 바꾸는 경우뿐이다. 그때는 이미지
  // 하나 때문에 화면을 비우기보다 원래 주소를 그대로 쓰게 둔다.
  if (typeof convert !== 'function') return url;
  return convert(encode(url), SCHEME);
}
