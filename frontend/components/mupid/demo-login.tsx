'use client';
import { useRef, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { LogIn, LogOut } from 'lucide-react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import type { MupidModel } from '@/hooks/use-mupid';
import { MupidMascot } from './mascot';
import { mupidService } from '@/lib/services';
import type { SignupInput } from '@/lib/services/contracts';

export function DemoLoginDialog({ model: m }: { model: MupidModel }) {
  const [name, setName] = useState('');
  const router = useRouter();
  const signup = usePathname() === '/signup';
  const setSignup = (value: boolean) => router.push(value ? '/signup' : '/login');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const title = useRef<HTMLHeadingElement>(null);
  const form = useRef<HTMLFormElement>(null);
  return (
    <Dialog
      open={m.loginDialog}
      onOpenChange={(open) => {
        m.setLoginDialog(open);
        if (!open) {
          router.push('/');
          setMessage('');
          setName('');
          form.current?.reset();
        }
      }}
    >
      <DialogContent
        className={`sky-dialog demo-login-dialog ${signup ? 'is-signup' : ''}`}
        initialFocus={title}
        finalFocus={m.loginTriggerRef}
      >
        <div className="demo-login-art" aria-hidden="true">
          <MupidMascot size={100} />
        </div>
        <DialogHeader>
          <DialogTitle ref={title} tabIndex={-1}>{signup ? '뮤피드 회원가입' : '뮤피드에 오신 걸 환영해요'}</DialogTitle>
          <DialogDescription className="sr-only">
            이메일과 비밀번호를 입력하세요.
          </DialogDescription>
        </DialogHeader>
        <form
          ref={form}
          onSubmit={async (event) => {
            event.preventDefault();
            if (busy) return;
            const passwordValue = form.current?.querySelector<HTMLInputElement>('#demo-login-password')?.value || '';
            setMessage('');
            if (signup) {
              const password = form.current?.querySelector<HTMLInputElement>('#demo-login-password');
              const confirm = form.current?.querySelector<HTMLInputElement>('#signup-confirm');
              if (password?.value !== confirm?.value) {
                setMessage('비밀번호가 일치하지 않아요.');
                confirm?.focus();
                return;
              }
              const fields = new FormData(form.current!);
              setBusy(true);
              try {
                await mupidService.signup({ email: name, password: passwordValue,
                  nickname: (form.current?.querySelector<HTMLInputElement>('#signup-nickname')?.value || '').trim(), birthYear: Number(fields.get('birth_year')),
                  gender: fields.get('gender') as SignupInput['gender'] });
                setSignup(false); form.current?.reset(); setName(''); setMessage('회원가입이 완료됐어요. 로그인해 주세요.');
              } catch (error) { setMessage(error instanceof Error ? error.message : '회원가입하지 못했어요. 다시 시도해 주세요.'); }
              finally { setBusy(false); }
              return;
            }
            setBusy(true);
            try {
              await m.signInDemo(name, passwordValue);
              form.current?.reset(); setName('');
            } catch { setMessage(mupidService.mode === 'demo' ? '데모 이메일 또는 비밀번호를 확인해 주세요.' : '로그인하지 못했어요. 입력 정보와 연결 상태를 확인해 주세요.'); }
            finally { setBusy(false); }
          }}
        >
          {!signup && mupidService.mode === 'demo' && <p className="comment-local-note">체험 계정: demo@naver.com / 1234<br />실제 회원 계정이 아니며 활동은 이 브라우저에만 저장돼요.</p>}
          <label htmlFor="demo-login-name" className="field-label">
            이메일
          </label>
          <input
            ref={input}
            id="demo-login-name"
            type="email"
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="이메일을 입력하세요"
            autoComplete="off"
            maxLength={254}
            required
          />
          {signup && <div className="signup-verification">
            <button type="button" className="secondary-button" disabled={busy || !name.trim()} onClick={async () => {
              if (!input.current?.reportValidity()) return;
              setBusy(true);
              try { await mupidService.requestEmailVerification(name); setMessage('인증 메일을 확인해 주세요.'); }
              catch (error) { setMessage(error instanceof Error ? error.message : '인증 메일을 요청하지 못했어요.'); }
              finally { setBusy(false); }
            }}>이메일 인증하기</button>
            <p>이메일 인증 후 회원가입을 완료할 수 있어요.</p>
          </div>}
          <label htmlFor="demo-login-password" className="field-label">
            비밀번호
          </label>
          <input
            id="demo-login-password"
            type="password"
            placeholder="비밀번호를 입력하세요"
            autoComplete="off"
            required
            maxLength={128}
          />
          {signup && <>
            <label htmlFor="signup-confirm" className="field-label">비밀번호 확인</label>
            <input id="signup-confirm" type="password" placeholder="비밀번호를 다시 입력하세요"
              autoComplete="off" required maxLength={128} />
            <label htmlFor="signup-nickname" className="field-label">닉네임</label>
            <input id="signup-nickname" name="nickname" type="text"
              placeholder="닉네임을 입력하세요 (최대 10자)"
              autoComplete="nickname" required maxLength={10}
              onChange={(event) => event.currentTarget.setCustomValidity(event.currentTarget.value.trim() ? '' : '닉네임을 입력해 주세요.')} />
            <label htmlFor="signup-birth-year" className="field-label">출생 연도</label>
            <input id="signup-birth-year" name="birth_year" type="number"
              placeholder="태어난 해를 입력하세요 (예: 2000)"
              inputMode="numeric" autoComplete="bday-year" required
              min={1900} max={new Date().getFullYear()} step={1} />
            <fieldset className="signup-gender">
              <legend className="field-label">성별</legend>
              <div className="signup-gender-options">
                {[['MALE', '남성'], ['FEMALE', '여성'], ['ETC', '기타']].map(([value, label]) => (
                  <label key={value}><input type="radio" name="gender" value={value} required />{label}</label>
                ))}
              </div>
            </fieldset>
          </>}
          {message && <output>{message}</output>}
          <div className={`auth-submit-actions ${signup ? 'is-signup' : ''}`}>
          <button
            className="primary-button wide"
            type="submit"
            disabled={busy || !name.trim()}
          >
            <LogIn /> {busy ? '처리 중…' : signup ? '회원가입' : '로그인'}
          </button>
          {!signup && <button type="button" className="secondary-button" onClick={() => {
            setSignup(true); setMessage(''); setName(''); form.current?.reset(); title.current?.focus({ preventScroll: true }); title.current?.closest('[role="dialog"]')?.scrollTo(0, 0);
          }}>회원가입</button>}
          </div>
        </form>
        {signup && <button type="button" className="text-link auth-mode-switch" onClick={() => {
          setSignup(!signup); setMessage(''); setName(''); form.current?.reset(); title.current?.focus({ preventScroll: true }); title.current?.closest('[role="dialog"]')?.scrollTo(0, 0);
        }}>이미 계정이 있나요? 로그인</button>}
      </DialogContent>
    </Dialog>
  );
}

export function DemoAccount({ model: m }: { model: MupidModel }) {
  return (
    <section className="demo-account" aria-label="계정">
      <div>
        <h2>
          {m.demoSession
            ? `${m.nickname}님, 반가워요`
            : '뮤피드에 오신 걸 환영해요'}
        </h2>
      </div>
      <button
        className="secondary-button"
        disabled={!m.authReady}
        onClick={m.demoSession ? m.signOutDemo : m.openLogin}
      >
        {m.demoSession ? <LogOut /> : <LogIn />}
        {m.demoSession ? '로그아웃' : '로그인'}
      </button>
    </section>
  );
}
