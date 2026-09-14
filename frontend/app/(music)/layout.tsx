import { MupidApp } from '@/components/mupid-app';

// App Router가 유지하는 공통 레이아웃: 페이지 이동 시 오디오와 공유 상태를 재생성하지 않는다.
// 이메일 인증은 이 그룹 밖에 두어 인증 링크가 로그인 가드에 막히지 않도록 한다.
export default function MusicLayout({ children }: { children: React.ReactNode }) {
  return <><MupidApp />{children}</>;
}
