'use client';
import { useRef, useState } from 'react';
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
import { api } from '@/lib/api/session';
import { ApiError } from '@/lib/api/client';

const genderOf = (value: string): 'M' | 'F' | 'ETC' =>
  value === 'MALE' ? 'M' : value === 'FEMALE' ? 'F' : 'ETC';

/**
 * 키보드가 올라온 뒤 입력란을 다시 보이게 한다 (S15P21A204-389).
 *
 * 브라우저는 **포커스를 받은 순간** 입력란을 보이려고 하지만, 그 시점에는 아직
 * 키보드가 올라오지 않았다. 키보드는 몇백 ms 뒤에 뜨고, 그때는 이미 스크롤이
 * 끝나 있다 — 그래서 대화상자의 아래쪽 입력란(「비밀번호 확인」·「출생 연도」)이
 * 키보드 뒤로 들어가 보이지 않았다.
 *
 * 대화상자 높이는 `--keyboard-inset` 만큼 이미 줄어 있으므로
 * (`dialog-viewport.css`) 그 아래 끝으로 한 번 더 내려 주기만 하면 된다.
 * `block: 'nearest'` 라 이미 보이는 입력란에서는 아무 일도 일어나지 않는다 —
 * 키보드가 없는 안드로이드 밖 환경이나 위쪽 입력란에서 화면이 튀지 않는다.
 *
 * 지연을 두는 이유는 `visualViewport` 의 `resize` 가 기기마다 다른 순간에
 * 오기 때문이다. 두 번 부르면 늦게 줄어든 높이까지 따라잡는다.
 */
const revealFocused = (target: EventTarget | null) => {
  const field = target as HTMLElement | null;
  if (!field?.scrollIntoView) return;
  const down = () => field.scrollIntoView({ block: 'nearest' });
  down();
  window.setTimeout(down, 260);
};

/**
 * 서버가 준 메시지를 그대로 보여준다.
 *
 * 필드 검증 오류는 하나만 고르지 않고 전부 보여준다. 서버가 Map 으로 주기
 * 때문에 순서가 보장되지 않아, 첫 항목만 쓰면 엉뚱한 필드가 뽑힐 수 있다.
 *
 * `instanceof` 대신 모양으로 판별한다. 개발 서버가 모듈을 두 번 넘겨주면
 * 클래스가 갈려 `instanceof` 가 어긋날 수 있는데, 그때 메시지를 통째로
 * 잃는 것보다는 낫다.
 */
const describe = (error: unknown, fallback: string) => {
  const api = error as Partial<ApiError> | null;
  if (api?.details) {
    const fields = Object.values(api.details).filter(Boolean);
    if (fields.length) return fields.join('\n');
  }
  return error instanceof Error && error.message ? error.message : fallback;
};

export function DemoLoginDialog({ model: m }: { model: MupidModel }) {
  const [name, setName] = useState('');
  const [signup, setSignup] = useState(false);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  // 인증 메일 발송 응답의 번호. 회원가입 요청에 그대로 실어야 한다.
  const [verificationId, setVerificationId] = useState<number | null>(null);
  // 메일로 온 6자리 인증 번호. 서버가 확인해 줘야 회원가입이 통과한다.
  const [code, setCode] = useState('');
  const [verified, setVerified] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const title = useRef<HTMLHeadingElement>(null);
  const form = useRef<HTMLFormElement>(null);
  return (
    <Dialog
      open={m.loginDialog}
      onOpenChange={(open) => {
        m.setLoginDialog(open);
        if (!open) {
          setSignup(false);
          setMessage('');
          setName('');
          setVerificationId(null);
          setCode('');
          setVerified(false);
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
          noValidate
          // 어느 입력란에 커서가 가든 그 자리를 다시 보이게 한다 (S15P21A204-389).
          // 입력란마다 `onFocus` 를 다는 대신 폼 하나에서 받는다 — 회원가입으로
          // 넘어가면 입력란이 한꺼번에 생기는데, 그때마다 손으로 붙이면 하나를
          // 빠뜨리기 쉽고 빠뜨린 자리만 조용히 키보드 뒤로 들어간다.
          onFocusCapture={(event) => revealFocused(event.target)}
          onSubmit={async (event) => {
            event.preventDefault();
            if (busy) return;
            setMessage('');
            const invalid = Array.from(event.currentTarget.querySelectorAll<HTMLInputElement>('input'))
              .find((field) => !field.validity.valid || (field.required && field.type !== 'password' && !field.value.trim()));
            if (invalid) {
              const missingMessages: Record<string, string> = {
                'demo-login-name': '이메일을 입력해 주세요.',
                'demo-login-password': '비밀번호를 입력해 주세요.',
                'signup-confirm': '비밀번호 확인을 입력해 주세요.',
                'signup-nickname': '닉네임을 입력해 주세요.',
                'signup-birth-year': '출생 연도를 입력해 주세요.',
              };
              const missing = invalid.validity.valueMissing || !invalid.value.trim();
              const message = invalid.name === 'gender'
                ? '성별을 선택해 주세요.'
                : missing
                  ? missingMessages[invalid.id] || '필수 항목을 입력해 주세요.'
                  : invalid.type === 'email'
                    ? '올바른 이메일 형식으로 입력해 주세요.'
                    : invalid.validationMessage;
              setMessage(message);
              window.alert(message);
              invalid.focus();
              return;
            }
            const passwordValue = form.current?.querySelector<HTMLInputElement>('#demo-login-password')?.value || '';
            if (signup) {
              const password = form.current?.querySelector<HTMLInputElement>('#demo-login-password');
              const confirm = form.current?.querySelector<HTMLInputElement>('#signup-confirm');
              if (password?.value !== confirm?.value) {
                setMessage('비밀번호가 일치하지 않아요.');
                window.alert('비밀번호가 일치하지 않아요.');
                confirm?.focus();
                return;
              }
              const fields = new FormData(form.current!);
              if (verificationId === null || !verified) {
                setMessage('이메일 인증을 먼저 완료해 주세요.');
                window.alert('이메일 인증을 먼저 완료해 주세요.');
                input.current?.focus();
                return;
              }
              setBusy(true);
              try {
                await api.user.signup({
                  email: name,
                  email_verification_id: verificationId,
                  password: passwordValue,
                  nickname: (form.current?.querySelector<HTMLInputElement>('#signup-nickname')?.value || '').trim(),
                  birth_year: Number(fields.get('birth_year')),
                  // 화면 값(MALE/FEMALE)과 서버 enum(M/F)이 다르다.
                  gender: genderOf(String(fields.get('gender') || '')),
                });
                setSignup(false); form.current?.reset(); setName(''); setVerificationId(null); setCode(''); setVerified(false);
                setMessage('회원가입이 완료됐어요. 로그인해 주세요.');
              } catch (error) { setMessage(describe(error, '회원가입하지 못했어요. 다시 시도해 주세요.')); }
              finally { setBusy(false); }
              return;
            }
            setBusy(true);
            try {
              await m.signInDemo(name, passwordValue);
              form.current?.reset(); setName('');
            } catch (error) { setMessage(describe(error, '로그인하지 못했어요. 입력 정보와 연결 상태를 확인해 주세요.')); }
            finally { setBusy(false); }
          }}
        >
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
            <button type="button" className="secondary-button" disabled={busy || !name.trim() || verified} onClick={async () => {
              if (!input.current?.reportValidity()) return;
              setBusy(true);
              try {
                const result = await api.mail.sendVerification(name);
                setVerificationId(result.verification_id);
                setCode('');
                setVerified(false);
                setMessage('메일로 보낸 인증 번호를 입력해 주세요. 15분 안에 입력해야 해요. 메일이 안 보이면 스팸 메일함도 확인해 주세요.');
              }
              catch (error) { setMessage(describe(error, '인증 메일을 요청하지 못했어요.')); }
              finally { setBusy(false); }
            }}>{verificationId === null ? '이메일 인증하기' : '인증 메일 다시 보내기'}</button>
            {verificationId !== null && !verified && <div className="signup-verification-code">
              <label htmlFor="signup-code" className="field-label">인증 번호</label>
              <input id="signup-code" type="text" value={code} inputMode="text"
                placeholder="메일로 받은 6자리"
                autoComplete="one-time-code" maxLength={6}
                onChange={(event) => setCode(event.target.value.toUpperCase())} />
              <button type="button" className="secondary-button" disabled={busy || code.length < 6} onClick={async () => {
                setBusy(true);
                try {
                  await api.mail.submitCode(verificationId, code);
                  setVerified(true);
                  setMessage('이메일 인증이 완료됐어요. 나머지 정보를 입력해 주세요.');
                } catch (error) { setMessage(describe(error, '인증 번호를 확인하지 못했어요.')); }
                finally { setBusy(false); }
              }}>인증 확인</button>
            </div>}
            <p>{verified ? '이메일 인증이 완료됐어요.' : '이메일 인증 후 회원가입을 완료할 수 있어요.'}</p>
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
          {message && <p role="status" className="auth-message">{message}</p>}
          <div className={`auth-submit-actions ${signup ? 'is-signup' : ''}`}>
          <button
            className="primary-button wide"
            type="submit"
            disabled={busy}
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
