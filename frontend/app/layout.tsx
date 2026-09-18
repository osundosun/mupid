import type { Metadata, Viewport } from 'next';
import './globals.css';
import { UpdatePrompt } from '@/components/mupid/update-prompt';

const siteUrl = process.env.MUPID_DEPLOY_TARGET === 'ec2'
  ? 'https://j15a204.p.ssafy.io'
  : 'https://mupid-studio.gyuone.chatgpt.site';

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  viewportFit: 'cover',
  interactiveWidget: 'resizes-content',
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
      <body className="font-sans antialiased">
        {children}
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
      </body>
    </html>
  );
}
