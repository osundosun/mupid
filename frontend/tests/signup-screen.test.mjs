import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

/**
 * 회원가입 화면 — S15P21A204-390(스팸 메일함 안내).
 *
 * 화면 코드는 `components/mupid/demo-login.tsx` 하나다. `/signup` 라우트
 * (`app/(music)/signup/page.tsx`)는 화면을 그리지 않는 빈 껍데기이고 — 주석 그대로
 * "화면과 재생 상태는 상위 공통 레이아웃에서 유지한다" — 실제 화면은 상위가 띄우는
 * 이 다이얼로그가 그린다(`hooks/use-mupid.ts` 의
 * `setLoginDialog(target === '/login' || target === '/signup')`).
 */

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');
const dialog = await read('components/mupid/demo-login.tsx');

void test('390 인증 메일을 보낸 직후 스팸 메일함도 확인하라고 안내한다', () => {
  // 발송에 성공한 바로 그 자리에서 알린다 — 번호를 기다리는 사람이 가장 먼저
  // 읽는 문장이고, 메일함을 안 열어 본 사람은 여기서만 알 수 있다.
  assert.match(dialog, /인증 번호를 입력해 주세요[^']*스팸 메일함/);
  // 안내는 덧붙이는 것이지 바꾸는 것이 아니다. 기존 문구는 그대로 둔다.
  assert.ok(dialog.includes('15분 안에 입력해야 해요'));
  assert.ok(dialog.includes('이메일 인증 후 회원가입을 완료할 수 있어요.'));
});

void test('390 문구를 넣어도 인증 흐름은 그대로다', () => {
  // 이 티켓은 문구다. 요청·응답 처리를 건드리지 않았는지 확인한다.
  assert.ok(dialog.includes('await api.mail.sendVerification(name)'));
  assert.ok(dialog.includes('setVerificationId(result.verification_id)'));
  assert.ok(dialog.includes('await api.mail.submitCode(verificationId, code)'));
  assert.ok(dialog.includes('setVerified(false)'));
});
