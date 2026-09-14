/**
 * 플랫폼 차이를 한 곳에 모은다.
 *
 * 화면 코드는 웹인지 앱인지 묻지 않는다. 여기서 고른 구현을 받아 쓸 뿐이다.
 */

import { createFetchTransport, type Transport } from '../api/transport';
import { isTauri } from './bridge';
import { createTauriTransport } from './transport';

export { isTauri } from './bridge';
export { clearNativeCookies } from './transport';
export { createSqliteService, setStoredAccount } from './service';
export {
  clearCache,
  withCache,
  CATALOG_MAX_AGE_MS,
  type CacheOptions,
} from './cache';
export { toImageSource } from './image';

/**
 * 이 환경에 맞는 전송 계층.
 *
 * 앱에서는 HTTP 가 전부 Rust 에서 돈다 — CORS 도, `SameSite=Strict` 쿠키
 * 문제도 거기서는 없는 이야기다. 웹에서는 지금까지처럼 `fetch` 다.
 */
export const createPlatformTransport = (): Transport =>
  isTauri() ? createTauriTransport() : createFetchTransport();
