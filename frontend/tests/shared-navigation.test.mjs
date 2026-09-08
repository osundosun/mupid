import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const read = (file) => readFile(new URL('../' + file, import.meta.url), 'utf8');
const [app, css, globals] = await Promise.all([
  read('components/mupid-app.tsx'),
  read('app/sky-shell.css'),
  read('app/globals.css'),
]);
const header = app.slice(
  app.indexOf('<header className="app-header">'),
  app.indexOf('</header>'),
);

test('one desktop header is shared by main and all working pages', () => {
  assert.ok(header.includes('className="desktop-app-nav"'));
  assert.ok(header.includes('desktopNavigation.map'));
  assert.ok(!header.includes("m.page === 'home'"));
  assert.ok(header.includes('onClick={() => m.go(id)}'));
  assert.ok(header.includes('aria-current={desktopNavPage === id'));
  assert.equal((app.match(/className="desktop-app-nav"/g) || []).length, 1);
});

test('Main is explicit on desktop and mobile, with current-page state for subpages', () => {
  assert.ok(app.includes("id: 'home', label: '메인', mobileLabel: '메인'"));
  assert.ok(app.includes("id: 'player', label: '플레이어'"));
  assert.ok(
    app.includes(
      "m.page === 'moment' ? 'player' : m.page === 'similar' ? 'search' : m.page",
    ),
  );
  assert.ok(header.includes('<Icon aria-hidden="true" />'));
  assert.ok(header.includes('님의 프로필'));
});

test('shared desktop shell removes the sidebar and centers the player without touching mobile', () => {
  const desktopStart = css.indexOf('@media (min-width: 768px)');
  assert.ok(desktopStart > 0);
  assert.match(css.slice(0, desktopStart), /display: none;/);
  assert.match(css, /\.mupid-shell \.side-nav\s*\{\s*display: none;/);
  assert.match(
    css,
    /\.mupid-shell \.mini-player\s*\{[^}]*left: 50%;[^}]*right: auto;[^}]*translateX\(-50%\)/,
  );
  assert.match(css, /\.desktop-app-nav\s*\{[^}]*flex-wrap: wrap;/);
  assert.match(css, /bottom: calc\(var\(--player-height\) \+ 40px\)/);
});

test('sky and pink accents reuse existing art without replacing fonts or logos', () => {
  assert.ok(globals.includes("@import './sky-shell.css'"));
  assert.ok(globals.includes('--sky-pink:'));
  assert.ok(globals.includes("'Pretendard'"));
  assert.ok(globals.includes('--font-chat: var(--font-mupid)'));
  assert.ok(css.includes("url('/sky-background.webp')"));
  assert.ok(css.includes('var(--sky-pink)'));
  assert.ok(!css.includes('mupid-logo'));
  assert.ok(!css.includes('font-family'));
});
