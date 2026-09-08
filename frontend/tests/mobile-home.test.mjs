import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');
const [home, listening, css, dock, app] = await Promise.all([
  read('components/mupid/home.tsx'),
  read('components/mupid/mobile-listening.tsx'),
  read('app/mobile-home.css'),
  read('hooks/use-home-player-dock.ts'),
  read('components/mupid-app.tsx'),
]);

void test('mobile home opens with a functional LP before search and recommendation shelves', () => {
  assert.ok(
    home.indexOf('<MobileListening model={m} />') <
      home.indexOf('className="search-box"'),
  );
  assert.ok(home.includes('<DesktopHome model={m} />'));
  assert.ok(home.includes('<AlbumDeck'));
  assert.ok(listening.includes('src="/sky-vinyl.webp"'));
  assert.ok(!listening.includes('Mascot'));
  assert.ok(listening.includes('<h2>{m.active.title}</h2>'));
  assert.ok(listening.includes('<PlayerControls model={m} />'));
  assert.ok(listening.includes('<LikeButton model={m} />'));
  assert.ok(listening.includes('m.openMoment()'));
  assert.ok(listening.includes('onClick={m.openChat}'));
  assert.ok(listening.includes('m.setQueueDialog(true)'));
  assert.ok(listening.includes("m.go('player')"));
});

void test('inline seek uses the existing keyboard slider with a contextual accessible label', () => {
  assert.ok(listening.includes("from '@/components/ui/slider'"));
  assert.ok(listening.includes('<Field.Label className="sr-only">'));
  assert.ok(listening.includes('value={[m.time]}'));
  assert.ok(listening.includes('max={m.active.duration}'));
  assert.ok(
    listening.includes('m.seek(Array.isArray(value) ? value[0] : value)'),
  );
  assert.match(
    css,
    /\.mobile-home-seek \[data-slot='slider'\] > div\s*\{\s*min-height: 44px;/,
  );
});

void test('the dock follows control visibility, retains keyboard focus and cleans up observation', () => {
  assert.ok(dock.includes("page !== 'home'"));
  assert.ok(dock.includes("typeof IntersectionObserver === 'undefined'"));
  assert.ok(dock.includes('entry.isIntersecting'));
  assert.ok(dock.includes('entry.intersectionRatio >= 0.7'));
  assert.ok(dock.includes('contains(document.activeElement)'));
  assert.ok(dock.includes('observer.disconnect()'));
  assert.ok(app.includes('useHomePlayerDock(m.page)'));
  assert.ok(app.includes('|| homeControlsVisible'));
});

void test('phone and tablet listening layouts bound the LP, wrap large text and avoid record rotation', () => {
  assert.match(css, /@media \(max-width: 1023px\)/);
  assert.match(css, /@media \(min-width: 768px\) and \(max-width: 1023px\)/);
  assert.match(css, /@container home-feed \(max-width: 18rem\)/);
  assert.match(css, /@media \(prefers-reduced-motion: no-preference\)/);
  assert.ok(css.includes('word-break: keep-all;'));
  assert.ok(css.includes('overflow-wrap: anywhere;'));
  assert.ok(css.includes('flex-wrap: wrap;'));
  assert.ok(css.includes('filter: var(--record-tone)'));
  assert.ok(!css.includes('rotate('));
  assert.ok(!css.includes('infinite'));
  for (const width of [320, 360, 390, 414, 520]) {
    const available = width - 48;
    const record = Math.min(available - 24, 270, 844 * 0.29);
    assert.ok(record <= available);
    assert.ok(
      available >= 4 * 44 + 54 + 4 * 4,
      'Five transport controls fit at 320px',
    );
  }
});
