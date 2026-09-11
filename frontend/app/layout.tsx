import type { Metadata, Viewport } from 'next';
import './globals.css';

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
      <body className="font-sans antialiased">{children}</body>
    </html>
  );
}
