import type { Metadata, Viewport } from 'next';
import './globals.css';
import './dark-mode.css';
import { ThemeProvider } from '@/components/mupid/theme';
import { UpdatePrompt } from '@/components/mupid/update-prompt';
import { ServerGate } from '@/components/mupid/server-gate';
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
   * ## 389 가 여기에 `overlays-content` 를 박았고, 그것이 계측을 껐다
   * (S15P21A204-389 → 411 재발)
   *
   * 389 는 이렇게 적었다: "`overlays-content` 로 명시하면 키보드 높이가
   * `visualViewport` 로 **일관되게** 드러난다."
   *
   * **정확히 반대다.** `overlays-content` 는 스펙상 **"어떤 뷰포트도 줄이지 않는다"**
   * 는 선언이라, `visualViewport.height` 가 줄어드는 기본 동작(`resizes-visual`)까지
   * 스스로 껐다. 재는 유일한 통로를 막아 놓고 그 값으로 재려 한 셈이다.
   *
   * 앱에서는 그것과 무관하게 0 이었다. `MainActivity` 가 `enableEdgeToEdge()` 를
   * 부르므로 `decorFitsSystemWindows=false` 이고, 그러면 프레임워크가 창을 줄여
   * 주지 않는다 — 매니페스트의 `adjustResize` 가 실기기에서
   * `STATE_UNSPECIFIED|ADJUST_RESIZE` 로 **적용되는 것까지 확인했는데도** 그렇다.
   *
   * ## 지금 구조 — 두 환경이 **다른 방법**으로 같은 결과에 닿는다
   *
   * - **앱**: 키보드가 뜨면 `SafeArea.kt` 가 웹뷰를 담은 컨테이너를 IME 높이만큼
   *   줄인다. 웹뷰의 뷰포트가 키보드 위에서 끝나므로 `100dvh` 가 저절로 맞고,
   *   `--keyboard-inset` 은 0 이다. 이 선언은 앱에서 **아무 역할도 하지 않는다**
   *   — 뷰 크기가 곧 뷰포트라 브라우저의 IME 처리가 끼어들 자리가 없다.
   * - **브라우저**: 뷰를 줄여 줄 네이티브가 없다. `visualViewport` 로 재어
   *   `--keyboard-inset` 에 싣고 CSS 가 자리를 옮긴다(`dialog-viewport.css`).
   *
   * 그래서 값을 **기본값인 `resizes-visual` 로 되돌린다.** 브라우저 쪽 계측이
   * 살아나야 하고, `dvh`(레이아웃 뷰포트)는 어느 값에서도 키보드로 줄지 않으므로
   * `100dvh - var(--keyboard-inset)` 라는 CSS 계약은 그대로다.
   *
   * 실기기 실측(2026-09-21, Galaxy S23 / Android 16 / WebView 152) — 앱 경로:
   *
   *     WebView        [0,0][1080,2316] → [0,0][1080,1308]
   *     비밀번호 입력란  [109,1490][970,1628] → [109,1099][942,1240]   (IME 상단 1308)
   */
  interactiveWidget: 'resizes-visual',
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
    <html lang="ko" suppressHydrationWarning>
      <head>
        {/* 다크 모드를 첫 화면 그리기 전에 적용한다. 없으면 로딩 화면이 밝게 번쩍인다. */}
        <script dangerouslySetInnerHTML={{ __html: "try{if(localStorage.getItem('mupid-theme')==='dark'){document.documentElement.classList.add('dark');document.documentElement.style.colorScheme='dark'}}catch(e){}" }} />
      </head>
      <body id="page-content" className="font-sans antialiased">
        {/*
          서버에 닿기 전에는 화면을 열지 않는다 (S15P21A204-444). 로그인 화면도
          이 안쪽이다 — 로그인도 서버가 한다. 업데이트 안내는 바깥에 둔다.
        */}
        <ThemeProvider><ServerGate>{children}</ServerGate></ThemeProvider>
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
