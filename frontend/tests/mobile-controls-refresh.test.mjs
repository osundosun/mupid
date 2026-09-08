import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const read = (path) => readFileSync(new URL('../' + path, import.meta.url), 'utf8');

void test('mobile home shortcuts are hidden without removing the bottom navigation', () => {
  const css = read('app/playback-details.css').split('@media (max-width: 767px)')[1];
  assert.match(css, /\.home-shortcuts\s*\{\s*display: none/);
  assert.match(read('components/mupid-app.tsx'), /mobileNavigation.map/);
});

void test('compact player exposes previous, play, next with 44px mobile targets', () => {
  const controls = read('components/mupid/primitives.tsx');
  assert.match(controls, /aria-label="이전 곡"[\s\S]*?onClick=\{\(\) => m.next\(-1\)\}/);
  const css = read('app/mupid.css');
  for (const match of css.matchAll(/\.mini-player \.previous-control\s*\{([^}]+)\}/g)) {
    assert.match(match[1], /display: inline-flex/);
  }
  assert.match(read('app/playback-details.css'), /\.mini-player \.player-controls \.icon-button\s*\{[^}]*min-width: 44px/);
});
