import type { UserInfo } from './endpoints';
import type { Transport } from './transport';

export function localDemoEnabled() {
  const env = (import.meta as { env?: Record<string, unknown> }).env;
  return env?.DEV === true && env?.VITE_MUPID_LOCAL_DEMO === 'true' &&
    typeof window !== 'undefined' &&
    ['localhost', '127.0.0.1', '[::1]'].includes(window.location.hostname);
}

/** 데모 요청을 실제 계정 서버에 보내지 않는다. */
export function withLocalDemo(transport: Transport): Transport {
  return { async send(request) {
    if (!localDemoEnabled()) return transport.send(request);
    const path = new URL(request.url, window.location.origin).pathname;
    if (path === '/api/v1/user' && request.method === 'GET') {
      return { status: 200, headers: {}, data: { data: { ...user } } };
    }
    return { status: 503, headers: {}, data: {
      code: 'MUPID_LOCAL_DEMO',
      message: '로컬 데모에서는 음악 탐색과 재생을 사용할 수 있어요. 이 기능은 서버 연결이 필요해요.',
    } };
  } };
}

const sessionKey = 'mupid.local-demo.session';
const user: UserInfo = {
  // 양수여야 한다. `commentUserId` 가 0 이하를 비로그인으로 보므로 -1 이면
  // 데모에서 댓글 수정·삭제 권한이 영영 열리지 않는다.
  user_id: 1,
  email: 'demo@naver.com',
  nickname: '데모',
  image_url: '',
  birth_year: 2000,
  gender: 'ETC',
  role: 'ROLE_USER',
  status: 'ACTIVE',
  onboarding_submitted: true,
};

/** 로컬 화면 개발 전용. 서버 계정이나 서버 인증 토큰을 만들지 않는다. */
export function loginLocalDemo(email: string, password: string): UserInfo {
  if (email.trim().toLowerCase() !== user.email || password !== '1234') {
    throw new Error('이메일 또는 비밀번호가 올바르지 않아요.');
  }
  localStorage.setItem(sessionKey, 'active');
  return { ...user };
}

export function restoreLocalDemo(): UserInfo | null {
  return localStorage.getItem(sessionKey) === 'active' ? { ...user } : null;
}

export function logoutLocalDemo() {
  localStorage.removeItem(sessionKey);
}
