// 화면 키는 기존 컴포넌트 호환용이며, 브라우저 주소는 이 표 한 곳에서 관리한다.
export const pagePaths = {
  welcome: '/', home: '/home', search: '/explore', player: '/player',
  library: '/playlists', report: '/report', profile: '/profile',
  moment: '/moments', similar: '/similar',
} as const;

export function guardPath(path: string, authenticated: boolean, completed: boolean) {
  // 곡과 댓글 열람은 로그인/온보딩 상태와 무관하게 공개한다.
  if (path === '/player' || path === '/explore') return path;
  if (!authenticated) return ['/', '/login', '/signup'].includes(path) ? path : '/login';
  if (!completed) return '/onboarding';
  return ['/', '/login', '/signup', '/onboarding'].includes(path) ? '/home' : path;
}

export function pageForPath(path: string): keyof typeof pagePaths {
  return (Object.entries(pagePaths).find(([, value]) => value === path)?.[0] as keyof typeof pagePaths) ?? 'welcome';
}
