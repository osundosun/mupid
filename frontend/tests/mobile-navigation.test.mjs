import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');
const [app, css, home, desktop, globals] = await Promise.all([
  read('components/mupid-app.tsx'),
  read('app/mobile-navigation.css'),
  read('components/mupid/home.tsx'),
  read('app/sky-shell.css'),
  read('app/globals.css'),
]);

void test('mobile reuses five desktop destinations and moves profile to the shared header', () => {
  const destinations = app.slice(
    app.indexOf('const mobileNavigation'),
    app.indexOf('export function MupidApp'),
  );
  assert.ok(destinations.includes('navigation[0]'));
  assert.ok(destinations.includes('navigation[1]'));
  assert.ok(destinations.includes('desktopNavigation[2]'));
  assert.ok(destinations.includes('navigation[2]'));
  assert.ok(destinations.includes('navigation[3]'));
  assert.ok(!destinations.includes('navigation[4]'));
  assert.ok(app.includes('mobileNavigation.map'));
  assert.ok(app.includes('const navPage = desktopNavPage'));
  assert.ok(
    app.includes("aria-current={m.page === 'profile' ? 'page' : undefined}"),
  );
  assert.ok(app.includes('aria-label="프로필 및 계정"'));
});

void test('mobile mirrors desktop selected-pill styling without changing desktop rules', () => {
  assert.ok(globals.includes("@import './mobile-navigation.css'"));
  assert.match(css, /@media \(max-width: 767px\)/);
  assert.match(css, /\.mobile-profile-link\s*\{\s*display: none;/);
  for (const token of [
    '#e5efff',
    '#eaf7ff',
    '#deebfd',
    '#396bb6',
    'stroke-width: 1.7',
  ]) {
    assert.ok(css.includes(token));
    assert.ok(desktop.includes(token));
  }
  assert.ok(css.includes("button[aria-current='page'] .mobile-nav-icon"));
  assert.ok(css.includes('fill: none'));
  assert.ok(css.includes('@media (prefers-reduced-motion: reduce)'));
});

void test('menu, mini player and content reserve separate safe-area-aware spaces', () => {
  assert.ok(css.includes('env(safe-area-inset-top, 0px)'));
  assert.ok(css.includes('env(safe-area-inset-bottom, 0px)'));
  assert.ok(
    css.includes(
      'bottom: calc(var(--nav-height) + env(safe-area-inset-bottom, 0px) + 8px)',
    ),
  );
  assert.ok(css.includes('padding-bottom: calc(var(--dock-height) + 24px)'));
  assert.ok(css.includes('min-height: 58px'));
  assert.ok(!home.includes('className="quick-links"'));
  assert.ok(home.includes('<MobileListening model={m} />'));
  assert.ok(home.includes('<AlbumDeck'));
});
