'use client';
import { useEffect, useState } from 'react';
import { Mail, KeyRound, ArrowLeft } from 'lucide-react';
import { MupidMascot } from './mascot';
import { emailVerificationService, parseEmailLink, type EmailAction } from '@/lib/email-verification';

type State = 'loading' | 'invalid' | 'expired' | 'unavailable' | 'error';
export function EmailVerificationPage() {
  const [state, setState] = useState<State>('loading');
  const [action, setAction] = useState<EmailAction | null>(null);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let live = true;
    const run = async () => {
      const link = parseEmailLink(new URLSearchParams(window.location.search));
      if (!live) return;
      if (!link) { setState('invalid'); return; }
      setAction(link.action);
      try {
        const result = await emailVerificationService.verify(link);
        if (live) setState(result.status);
      } catch { if (live) setState('error'); }
    };
    void run();
    return () => { live = false; };
  }, [attempt]);
  const reset = action === 'PW_RESET';
  const content = {
    loading: ['링크를 확인하고 있어요', '잠시만 기다려 주세요.'],
    invalid: ['올바르지 않은 인증 링크예요', '이메일에 있는 버튼을 다시 눌러 주세요. 링크가 잘렸거나 필수 정보가 빠져 있을 수 있어요.'],
    expired: ['인증 링크가 만료됐어요', '새 인증 메일을 요청한 뒤 다시 접속해 주세요.'],
    unavailable: ['인증 서버 연결 준비 중이에요', reset
      ? '비밀번호 재설정 링크를 받았어요. 아직 인증 확인 및 비밀번호 변경 기능은 연결되지 않았습니다.'
      : '회원가입 인증 링크를 받았어요. 아직 이메일 인증은 완료되지 않았습니다.'],
    error: ['연결하지 못했어요', '잠시 후 다시 시도해 주세요. 인증은 완료되지 않았습니다.'],
  }[state];
  return <main className="email-verify-page">
    <section className="email-verify-panel" aria-labelledby="verify-title" aria-busy={state === 'loading'}>
      <a className="email-verify-back" href="/" aria-label="뮤피드 홈으로"><ArrowLeft size={20} /> 뮤피드</a>
      <MupidMascot size={132} />
      <div className="email-verify-label">{reset ? <KeyRound size={20} /> : <Mail size={20} />}{reset ? '비밀번호 재설정' : '이메일 인증'}</div>
      <h1 id="verify-title">{content[0]}</h1>
      <p role={state === 'error' || state === 'invalid' ? 'alert' : 'status'}>{content[1]}</p>
      {state === 'unavailable' && <p className="email-verify-note">서비스 준비가 완료된 후 다시 이용해 주세요.</p>}
      <div className="email-verify-actions">
        {(state === 'error' || state === 'unavailable') && <button onClick={() => { setState('loading'); setAttempt(v => v + 1); }}>다시 확인하기</button>}
        <a href="/">홈으로 돌아가기</a>
      </div>
    </section>
  </main>;
}
