import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');
const [css, home, discovery, app, mobileNav] = await Promise.all([
  read('app/mupid.css'),
  read('components/mupid/home.tsx'),
  read('components/mupid/discovery.tsx'),
  read('components/mupid-app.tsx'),
  read('app/mobile-navigation.css'),
]);

test('mobile uses one compact menu with the desktop line-icon style', () => {
  assert.ok(!home.includes('className="quick-links"'));
  assert.ok(app.includes('mobileNavigation.map'));
  assert.match(mobileNav, /\.mobile-nav-icon\s*\{[\s\S]*?height: 32px;/);
  assert.match(mobileNav, /stroke-width: 1.7;/);
  assert.ok(app.includes("mobileLabel: '플리'"));
  assert.ok(app.includes('aria-label={label}'));
});

test('player keeps the title, seeking and actions while secondary information is expandable', () => {
  const player = discovery.slice(
    discovery.indexOf('export function PlayerView'),
    discovery.indexOf('export function MomentView'),
  );
  assert.match(player, /<header className="player-heading">/);
  assert.equal((player.match(/<h1>/g) || []).length, 1);
  assert.match(
    player,
    /<div className="player-waveform">[\s\S]*?<Waveform[\s\S]*?<input/,
  );
  assert.ok(player.includes('m.seek(Number(e.target.value))'));
  assert.ok(player.includes('aria-valuetext='));
  assert.ok(player.includes('<PlayerControls model={m} />'));
  assert.ok(player.includes('m.openMoment()'));
  assert.ok(player.includes("m.go('similar')"));
  assert.match(
    player,
    /<details className="player-insights">[\s\S]*?<summary>/,
  );
  assert.match(css, /\.player-waveform:focus-within\s*\{[^}]*outline:/);
});

test('player keeps shared navigation without duplicating the mini player', () => {
  assert.ok(app.includes("m.page === 'welcome' || (!desktopDock && (m.page === 'player' || homeControlsVisible))"));
  assert.match(mobileNav, /\.mupid-shell \.mobile-nav\s*\{\s*display: grid;/);
  assert.match(mobileNav, /--nav-height: max\(76px, calc\(4rem \+ 12px\)\)/);
  assert.match(css, /width: min\(100%, 280px, 36svh\)/);
  assert.match(css, /--nav-height: max\(62px, calc\(3rem \+ 14px\)\)/);
  assert.match(css, /\.moment-adjustments > summary\s*\{/);
});

test('five menu controls fit 320–520px screens and reserve height for enlarged labels', () => {
  for (const viewport of [320, 360, 375, 390, 414, 520]) {
    const content = viewport - 16;
    for (const rootFont of [16, 32]) {
      const font =
        content <= 18 * rootFont ? rootFont * 0.75 : rootFont * 0.875;
      const width = (content - 4 * 2) / 5;
      const lines = Math.ceil((4 * font) / width);
      const neededHeight = 32 + 3 + lines * font * 1.4;
      const availableHeight = Math.max(76, rootFont * 4 + 12) - 16;
      assert.ok(width >= 44);
      assert.ok(neededHeight <= availableHeight);
      if (rootFont === 16) assert.equal(lines, 1);
    }
  }
});
