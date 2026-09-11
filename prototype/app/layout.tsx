import type { Metadata } from 'next';
import './globals.css';
const siteUrl = process.env.MUPID_DEPLOY_TARGET === 'ec2'
  ? 'https://j15a204.p.ssafy.io'
  : 'https://www.gyuwon.net/mupid';

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl),
  title: 'MUPID — 좋아한 순간에서 시작되는 음악 추천',
  description: '청취 행동과 좋아한 음악 구간을 분석해 나만의 Listening DNA와 다음 음악을 발견하세요.',
  alternates: { canonical: siteUrl },
  icons: { icon: '/mupid-mark.svg' },
  openGraph: {
    title: 'MUPID — 좋아한 순간에서 시작되는 음악 추천',
    description: '청취 행동과 좋아한 음악 구간을 분석해 나만의 Listening DNA와 다음 음악을 발견하세요.',
    url: siteUrl,
    images: [{ url: '/og-mupid.png', width: 1672, height: 941, alt: 'MUPID — 좋아한 순간에서 시작되는 음악 추천' }],
    locale: 'ko_KR',
    type: 'website',
  },
  twitter: {
    card: 'summary_large_image',
    title: 'MUPID — 좋아한 순간에서 시작되는 음악 추천',
    description: '청취 행동과 좋아한 음악 구간을 분석해 나만의 Listening DNA와 다음 음악을 발견하세요.',
    images: ['/og-mupid.png'],
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ko">
      <body className="antialiased">{children}</body>
    </html>
  );
}
