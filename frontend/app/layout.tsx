import type { Metadata, Viewport } from 'next';
import './globals.css';
import './dark-mode.css';
import { ThemeProvider } from '@/components/mupid/theme';
import { UpdatePrompt } from '@/components/mupid/update-prompt';
import { SkyCursor } from '@/components/mupid/sky-cursor';
import { PageScrollbar } from '@/components/mupid/page-scrollbar';
import { InnerScrollbars } from '@/components/mupid/inner-scrollbars';

const siteUrl = process.env.MUPID_DEPLOY_TARGET === 'ec2'
  ? 'https://j15a204.p.ssafy.io'
  : 'https://mupid-studio.gyuone.chatgpt.site';

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  viewportFit: 'cover',
  /*
   * 키보드가 올라올 때 **레이아웃 뷰포트를 줄이지 않는다** (S15P21A204-389).
   *
   * 기본값(`resizes-content`)이면 브라우저가 레이아웃 뷰포트까지 줄여 준다.
   * 크롬 안드로이드에서는 그게 곧 `100dvh` 가 키보드 위까지 줄어든다는 뜻이라
   * 편해 보이지만, **안드로이드 웹뷰에서는 실제로 그렇게 되지 않는다** — 이 앱의
   * 재생 표시가 그 차이를 이미 기록해 두었다(`hooks/use-keyboard-inset.ts`,
   * `components/mupid/chat.tsx`): 페이지 높이는 그대로인데 키보드가 위에 덮여
   * `--keyboard-inset` 이 양수로 잡혔다.
   *
   * 그 상태에서는 `dvh` 기반 자리 계산이 하나도 줄지 않아, 로그인·회원가입
   * 대화상자가 `max-height: calc(100dvh - 32px)` 로 그대로 서고 아래쪽 입력란이
   * 키보드 뒤로 들어갔다.
   *
   * 그래서 '덮이되 줄지 않는' 쪽을 **명시한다.** 그러면 키보드 높이가
   * `visualViewport` 로 일관되게 드러나고, 그 값을 `--keyboard-inset` 으로 받아
   * 대화상자가 스스로 줄어든다 (`dialog-viewport.css`, `demo-login.css`).
   * 네이티브 쪽은 `AndroidManifest.xml` 의 `windowSoftInputMode="adjustResize"` 가
   * 창 자체를 줄여 그 몫을 인셋으로 넘긴다 — 두 경로가 같은 순간에 울린다.
   */
  interactiveWidget: 'overlays-content',
  themeColor: '#f3f7fe',
};

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl),
  title: '뮤피드',
  description:
    '좋아했던 순간이 다음 음악이 되는 곳, 뮤피드.',
  alternates: { canonical: siteUrl },
  applicationName: 'Mupid',
  manifest: '/manifest.webmanifest?v=mupid-cupid-3',
  appleWebApp: { capable: true, title: 'Mupid', statusBarStyle: 'default' },
  icons: {
    icon: [
      {
        url: '/favicon-16.png?v=mupid-cupid-3',
        type: 'image/png',
        sizes: '16x16',
      },
      {
        url: '/favicon-32.png?v=mupid-cupid-3',
        type: 'image/png',
        sizes: '32x32',
      },
    ],
    shortcut: '/favicon-32.png?v=mupid-cupid-3',
    apple: {
      url: '/apple-touch-icon.png?v=mupid-cupid-3',
      sizes: '180x180',
      type: 'image/png',
    },
  },
  openGraph: {
    title: '뮤피드',
    description:
      '좋아했던 순간이 다음 음악이 되는 곳, 뮤피드.',
    url: siteUrl,
    images: [
      {
        url: '/og-mupid-sky-v8.png',
        alt: '뮤피드 — 좋아했던 순간이 다음 음악이 되는 곳',
      },
    ],
    locale: 'ko_KR',
    type: 'website',
  },
  twitter: {
    card: 'summary_large_image',
    title: '뮤피드',
    description:
      '좋아했던 순간이 다음 음악이 되는 곳, 뮤피드.',
    images: ['/og-mupid-sky-v8.png'],
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ko">
      <body id="page-content" className="font-sans antialiased">
        <ThemeProvider>{children}</ThemeProvider>
        {/*
          업데이트 안내를 **root 에 둔다.** 인증이 필요한 `(music)` 그룹 안에
          두면 로그인 전에는 새 판이 있어도 알 수 없다 — 실기기에서 확인했다
          (랜딩 화면에 머무는 동안 앱의 매니페스트 요청이 0건이었다).

          이 컴포넌트가 확인까지 스스로 한다(`checkForUpdates`). 확인 훅을
          따로 두면 두 곳이 어긋나거나 매니페스트를 중복으로 받는다.

          조건이 안 맞으면 `null` 을 돌려주므로(모바일이 아니거나 새 판이
          없거나 24시간 안에 이미 봤거나) 데스크톱·웹에서는 아무것도 그리지
          않는다.
        */}
        <UpdatePrompt />
        <SkyCursor />
        <PageScrollbar />
        <InnerScrollbars />
      </body>
    </html>
  );
}
