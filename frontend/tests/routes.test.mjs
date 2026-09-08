import test from 'node:test';
import assert from 'node:assert/strict';
import { guardPath, pageForPath, pagePaths } from '../lib/routes.ts';

test('비로그인은 서비스 및 온보딩에 바로 접근하지 못한다', () => {
  for (const path of ['/home', '/onboarding', '/profile']) assert.equal(guardPath(path, false, true), '/login');
  for (const path of ['/player', '/explore']) assert.equal(guardPath(path, false, false), path);
  for (const path of ['/', '/login', '/signup']) assert.equal(guardPath(path, false, false), path);
});
test('로그인 후 완료 여부로 온보딩을 구분한다', () => {
  assert.equal(guardPath('/home', true, false), '/onboarding');
  assert.equal(guardPath('/login', true, false), '/onboarding');
  assert.equal(guardPath('/onboarding', true, true), '/home');
  assert.equal(guardPath('/player', true, true), '/player');
});
test('기존 화면 키와 경로가 일대일로 연결된다', () => {
  for (const [page, path] of Object.entries(pagePaths)) assert.equal(pageForPath(path), page);
});
