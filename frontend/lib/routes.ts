// 화면 키는 기존 컴포넌트 호환용이며, 브라우저 주소는 이 표 한 곳에서 관리한다.
export const pagePaths = {
  welcome: '/', home: '/home', search: '/explore', player: '/player',
  library: '/playlists', report: '/report', profile: '/profile',
  moment: '/moments', similar: '/similar',
} as const;

/** 로그인 없이 열 수 있는 유일한 경로들. 나머지는 전부 막는다. */
export const publicPaths = ['/', '/login', '/signup'] as const;

/**
 * 이 주소를 지금 열어도 되는가. 열 수 없으면 보내야 할 주소를 돌려준다.
 *
 * **로그인하지 않으면 서비스를 쓸 수 없다.** 곡 목록도 재생도 로그인한 뒤의
 * 일이다. 재생·스트리밍 API 자체가 인증을 요구하므로 화면만 열어 두면
 * 빈 화면과 401 만 보게 된다.
 *
 * 여기서 막는 것은 화면 이동일 뿐이고 **실제 접근 권한 검증은 서버 책임**이다.
 */
export function guardPath(path: string, authenticated: boolean, completed: boolean) {
  if (!authenticated) return (publicPaths as readonly string[]).includes(path) ? path : '/login';
  // 로그인은 했지만 온보딩을 마치지 않았으면 설문부터 끝낸다.
  if (!completed) return '/onboarding';
  return [...publicPaths, '/onboarding'].includes(path) ? '/home' : path;
}

export function pageForPath(path: string): keyof typeof pagePaths {
  return (Object.entries(pagePaths).find(([, value]) => value === path)?.[0] as keyof typeof pagePaths) ?? 'welcome';
}
