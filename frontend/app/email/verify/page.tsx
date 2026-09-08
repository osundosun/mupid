import type { Metadata } from 'next';
import { EmailVerificationPage } from '@/components/mupid/email-verification';
import './verify.css';

export const metadata: Metadata = {
  title: '이메일 인증 | 뮤피드',
  robots: { index: false, follow: false },
  referrer: 'no-referrer',
  alternates: { canonical: '/email/verify' },
};

export default function VerifyPage() {
  return <EmailVerificationPage />;
}
