import test from 'node:test';
import assert from 'node:assert/strict';
import { guardPath, pageForPath, pagePaths } from '../lib/routes.ts';

// 로그인하지 않으면 서비스를 쓸 수 없다. 곡 목록도 재생도 로그인한 뒤의 일이다.
test('비로그인은 시작·로그인·가입 말고는 어디에도 들어가지 못한다', () => {
  for (const path of ['/home', '/onboarding', '/profile', '/player', '/explore', '/playlists', '/report', '/moments', '/similar']) {
    assert.equal(guardPath(path, false, true), '/login');
    assert.equal(guardPath(path, false, false), '/login');
  }
  for (const path of ['/', '/login', '/signup']) assert.equal(guardPath(path, false, false), path);
});
test('로그인 후 완료 여부로 온보딩을 구분한다', () => {
  assert.equal(guardPath('/home', true, false), '/onboarding');
  assert.equal(guardPath('/login', true, false), '/onboarding');
  assert.equal(guardPath('/onboarding', true, true), '/home');
  assert.equal(guardPath('/player', true, true), '/player');
});
// 온보딩은 로그인했고 아직 마치지 않은 사람만 본다.
test('온보딩은 미완료 회원에게만 열린다', () => {
  assert.equal(guardPath('/onboarding', true, false), '/onboarding');
  assert.equal(guardPath('/onboarding', true, true), '/home');
  assert.equal(guardPath('/onboarding', false, true), '/login');
  assert.equal(guardPath('/onboarding', false, false), '/login');
});
test('기존 화면 키와 경로가 일대일로 연결된다', () => {
  for (const [page, path] of Object.entries(pagePaths)) assert.equal(pageForPath(path), page);
});
