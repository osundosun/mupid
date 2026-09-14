/**
 * 인증 세션 상태.
 *
 * 액세스 토큰은 ApiClient 가 메모리에만 들고 있다. 새로고침하면 사라지므로
 * 앱이 뜰 때 `restoreSession()` 이 HttpOnly 쿠키의 리프레시 토큰으로 한 번
 * 재발급을 시도한다. 실패하면 조용히 비로그인 상태가 된다.
 */

import { ApiClient, ApiError } from './client';
import { createEndpoints, type UserInfo } from './endpoints';
import { createStore } from '../store';

export type SessionStatus =
  /** 아직 복원을 시도하지 않았다. 화면은 인증 버튼을 비활성으로 둔다. */
  | 'unknown'
  /** 복원 시도 중. */
  | 'restoring'
  | 'anonymous'
  | 'authenticated';

export type SessionState = {
  status: SessionStatus;
  user: UserInfo | null;
};

export const sessionStore = createStore<SessionState>({
  status: 'unknown',
  user: null,
});

/**
 * API 서버 주소. 비워 두면 같은 출처로 보낸다.
 *
 * 개발에서는 Vite 프록시가 `/api` 를 nginx(9900)로 넘긴다. 서버에 CORS 설정이
 * 없고 리프레시 토큰 쿠키가 SameSite=Strict 라, 브라우저에게는 같은 출처로
 * 보이게 하는 편이 유일하게 단순한 길이다.
 */
const baseUrl =
  (import.meta as { env?: Record<string, string> }).env?.VITE_MUPID_API_BASE ??
  '';

export const apiClient = new ApiClient({
  baseUrl,
  onSessionExpired: () => {
    sessionStore.set({ status: 'anonymous', user: null });
  },
});

export const api = createEndpoints(apiClient);

/** 여러 화면이 동시에 불러도 복원은 한 번만 돈다. */
let restoring: Promise<SessionState> | null = null;

export function restoreSession(): Promise<SessionState> {
  if (restoring) return restoring;
  if (sessionStore.get().status === 'authenticated') {
    return Promise.resolve(sessionStore.get());
  }

  restoring = (async () => {
    sessionStore.set({ status: 'restoring' });
    try {
      const { access_token } = await api.auth.reissue();
      apiClient.setAccessToken(access_token);
      const user = await api.user.me();
      sessionStore.set({ status: 'authenticated', user });
    } catch {
      // 쿠키가 없거나 만료됐다. 첫 방문자의 정상 경로라 조용히 넘어간다.
      apiClient.setAccessToken(null);
      sessionStore.set({ status: 'anonymous', user: null });
    } finally {
      restoring = null;
    }
    return sessionStore.get();
  })();

  return restoring;
}

export async function login(email: string, password: string) {
  const result = await api.auth.login(email, password);
  apiClient.setAccessToken(result.access_token);
  const user = await api.user.me();
  sessionStore.set({ status: 'authenticated', user });
  return user;
}

export async function logout() {
  try {
    await api.auth.logout();
  } catch (error) {
    // 토큰이 이미 죽었으면 서버는 401 을 준다. 로컬 세션은 어차피 지운다.
    if (!(error instanceof ApiError) || !error.isUnauthorized) throw error;
  } finally {
    apiClient.clearSession();
    sessionStore.set({ status: 'anonymous', user: null });
  }
}
