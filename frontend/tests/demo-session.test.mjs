import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createDemoSession } from '../lib/demo-session.ts';

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');
const [hook, dialog, app, profile, css, state] = await Promise.all([
  read('hooks/use-mupid.ts'),
  read('components/mupid/demo-login.tsx'),
  read('components/mupid-app.tsx'),
  read('components/mupid/collection.tsx'),
  read('app/demo-login.css'),
  read('lib/demo-session.ts'),
]);

void test('UI preview normalizes display names, rejects blanks and limits their length', () => {
  assert.equal(createDemoSession('   '), null);
  assert.deepEqual(createDemoSession('  규원   뮤피드  '), {
    kind: 'demo',
    nickname: '규원 뮤피드',
  });
  assert.equal(createDemoSession('가'.repeat(50)).nickname.length, 20);
});

void test('login stays in memory and never restores a browser login session', () => {
  assert.ok(hook.includes('useState<AccountSession | null>(null)'));
  assert.ok(hook.includes("displayName: demoSession ? nickname : '게스트'"));
  // 브라우저 저장으로 세션을 되살리지 않는다.
  assert.ok(!hook.includes('bootstrap.session'));
  assert.ok(!hook.includes('sessionStorage'));
  assert.ok(!hook.includes('readDemoSession'));
  assert.ok(!state.includes('Storage'));
  assert.ok(!state.includes('SESSION_KEY'));
  assert.ok(
    hook.includes('mupidService.savePreferences'),
    'Music preferences are preserved through the service',
  );
});

void test('account UI passes credentials only to the service and resets the form', () => {
  assert.ok(!dialog.includes('UI PREVIEW'));
  assert.ok(!dialog.includes('계정 인증은 추후 백엔드와 연결됩니다.'));
  assert.ok(!dialog.includes('id="demo-login-note"'));
  assert.ok(!dialog.includes('게스트로 계속 둘러보기'));
  assert.ok(dialog.includes('type="password"'));
  assert.ok(dialog.includes('await m.signInDemo(name, passwordValue)'));
  assert.ok(!dialog.includes('localStorage'));
  assert.ok(!dialog.includes('fetch('));
  assert.ok(!dialog.includes('m.signInDemo(name)'));
  assert.ok(dialog.includes('form.current?.reset()'));
  assert.ok(dialog.includes('type="email"'));
});

// 로그인해야 쓸 수 있는 서비스다. 로그인은 화면 상태만 건드리고, 로그아웃은
// 재생을 멈추고 계정에서 끌어온 값을 모두 비운다.
void test('sign-in only changes UI state and sign-out clears the account', () => {
  const signIn = hook.slice(
    hook.indexOf('const signInDemo'),
    hook.indexOf('const signOutDemo'),
  );
  const signOut = hook.slice(
    hook.indexOf('const signOutDemo'),
    hook.indexOf('const loadTrack'),
  );
  assert.ok(signIn.includes('setDemoSession({') || signIn.includes('setDemoSession(session)'));
  assert.ok(signOut.includes('setDemoSession(null)'));
  // 로그인은 저장소나 재생기를 직접 건드리지 않는다.
  for (const prohibited of ['fetch(', 'sessionStorage', 'localStorage', 'audioRef', 'setPlaying']) {
    assert.ok(!signIn.includes(prohibited), prohibited);
  }
  // 로그아웃은 두 재생 경로를 모두 멈추고 계정에서 끌어온 값을 비운다.
  for (const required of ['audioRef.current?.pause()', 'getPlayer()?.stop()', 'setPlaying(false)', 'setLiked([])', 'setIgnored([])', 'setHistory([])']) {
    assert.ok(signOut.includes(required), required);
  }
  for (const prohibited of ['fetch(', 'sessionStorage', 'localStorage']) {
    assert.ok(!signOut.includes(prohibited), prohibited);
  }
});

void test('account preview keeps modal focus and remains reachable through profile', () => {
  assert.ok(dialog.includes("from '@/components/ui/dialog'"));
  assert.ok(dialog.includes('initialFocus={title}'));
  assert.ok(dialog.includes('finalFocus={m.loginTriggerRef}'));
  assert.ok(app.includes('<DemoLoginDialog model={m} />'));
  assert.ok(app.includes("m.go('profile') : m.openLogin()"));
  assert.ok(app.includes('className="mobile-profile-link"'));
  assert.ok(app.includes('aria-label="프로필 및 계정"'));
  assert.ok(profile.includes('<DemoAccount model={m} />'));
  assert.ok(profile.includes('<NicknameForm key={m.nickname} model={m} />'));
  assert.ok(dialog.includes('m.demoSession ? m.signOutDemo : m.openLogin'));
  assert.match(css, /max-height: calc\(100dvh - 32px\);\s*overflow-y: auto;/);
});
