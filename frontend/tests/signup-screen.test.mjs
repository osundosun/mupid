import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

/**
 * 회원가입 화면 — S15P21A204-390(스팸 메일함 안내) · 391(로고·제목 가운데 정렬).
 *
 * 화면 코드는 `components/mupid/demo-login.tsx` 하나다. `/signup` 라우트
 * (`app/(music)/signup/page.tsx`)는 화면을 그리지 않는 빈 껍데기이고 — 주석 그대로
 * "화면과 재생 상태는 상위 공통 레이아웃에서 유지한다" — 실제 화면은 상위가 띄우는
 * 이 다이얼로그가 그린다(`hooks/use-mupid.ts` 의
 * `setLoginDialog(target === '/login' || target === '/signup')`).
 */

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');
const [dialog, css] = await Promise.all([
  read('components/mupid/demo-login.tsx'),
  read('app/demo-login.css'),
]);

/** CSS 에서 선택자 하나의 첫 규칙 본문을 뽑는다 (`responsive-cards.test.mjs` 와 같은 방식). */
const rule = (selector) => {
  const start = css.indexOf(selector + ' {');
  assert.notEqual(start, -1, `규칙을 찾지 못했다: ${selector}`);
  return css.slice(start, css.indexOf('}', start) + 1);
};

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

void test('391 로고·제목은 다이얼로그 가운데에 온다 — 헤더가 한쪽만 비우지 않는다', () => {
  // 치우침의 원인을 되돌린다.
  //
  // `app/dialog-viewport.css` 가 `.sky-dialog [data-slot='dialog-header']` 에
  // 오른쪽 52px 만 준다 — 닫기 버튼 자리를 비우는 규칙이다. 이 화면은 내용을
  // 가운데로 모으므로, 한쪽만 비우면 남은 폭의 중심이 그 절반(26px)만큼 왼쪽으로
  // 간다. 제목만 `text-align: center` 로 좁아진 상자 안에서 가운데가 되어 밀렸다.
  //
  // `dialog-viewport.css` 의 규칙도 클래스 + 속성(0,2,0)이라 특이도가 같고,
  // 그 파일은 `globals.css` 에서 이 파일 **뒤에** import 된다 — 나중에 오는
  // 쪽이 이긴다. 그래서 클래스를 하나 더 붙여 올린다(0,3,0 > 0,2,0).
  // (실측: 이 한 단계가 없으면 계산된 값이 `0px / 52px` 로 남아 제목이 그대로
  // 26px 밀린다.)
  const header = rule(".sky-dialog.demo-login-dialog [data-slot='dialog-header']");
  assert.match(header, /padding-inline:\s*0;/);
  assert.doesNotMatch(header, /padding-right:\s*52px;/);
  // 가운데 정렬 자체는 유지한다.
  assert.match(header, /align-items:\s*center;/);
  assert.match(header, /text-align:\s*center;/);

  // 로고는 원래 자기 컨테이너 안에서 가운데였다 — 그 정렬을 깨지 않는다.
  assert.match(rule('.demo-login-art'), /justify-content:\s*center;/);

  // 제목 좌우 여백은 대칭이어야 한다. 한쪽만 주면 같은 치우침이 다시 생긴다.
  const title = rule(".demo-login-dialog [data-slot='dialog-title']");
  assert.match(title, /padding-inline:\s*12px;/);
  assert.doesNotMatch(title, /padding-(?:left|right):/);
});

void test('391 정렬은 로그인·회원가입 두 화면에 함께 걸린다', () => {
  // 헤더 규칙을 화면 모드 클래스에 묶지 않는다. 묶으면 로그인 화면에만 치우침이
  // 남는다 — 같은 원인이 같은 요소에 걸리는데 `is-signup` 은 글자만 바꾼다.
  assert.ok(
    !/\.demo-login-dialog\.is-signup\s+\[data-slot='dialog-header'\]/.test(css),
    '헤더 정렬을 is-signup 에만 걸면 로그인 화면이 그대로 치우친다',
  );
  assert.ok(dialog.includes("${signup ? 'is-signup' : ''}"));
  assert.ok(dialog.includes("{signup ? '뮤피드 회원가입' : '뮤피드에 오신 걸 환영해요'}"));
});
