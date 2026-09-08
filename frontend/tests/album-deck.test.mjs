import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');
const [deck, home, desktop, css, primitive] = await Promise.all([
  read('components/mupid/album-deck.tsx'),
  read('components/mupid/home.tsx'),
  read('components/mupid/desktop-home.tsx'),
  read('app/album-deck.css'),
  read('components/ui/carousel.tsx'),
]);

test('both home layouts share a user-controlled carousel without timers or automatic playback', () => {
  assert.ok(home.includes('<AlbumDeck'));
  assert.ok(desktop.includes('<AlbumDeck'));
  assert.ok(deck.includes("from '@/components/ui/carousel'"));
  assert.ok(!deck.includes('setInterval'));
  assert.ok(!deck.includes('setTimeout'));
  assert.ok(!deck.includes('Autoplay'));
  assert.ok(deck.includes('loop: false'));
  assert.ok(deck.includes('onClick={() => api?.scrollTo(i, reducedMotion)}'));
  assert.ok(deck.includes('onClick={() => m.play(selected)}'));
  assert.ok(deck.includes('onClick={() => m.openTrack(selected)}'));
  assert.ok(!deck.includes('startPlayback'));
});

test('album movement preserves controls, keyboard access and navigation boundaries', () => {
  assert.ok(deck.includes('aria-label="이전 추천 앨범"'));
  assert.ok(deck.includes('aria-label="다음 추천 앨범"'));
  assert.ok(primitive.includes('disabled={!canScrollPrev}'));
  assert.ok(primitive.includes('disabled={!canScrollNext}'));
  assert.ok(primitive.includes("event.key === 'ArrowLeft'"));
  assert.ok(primitive.includes("event.key === 'ArrowRight'"));
  assert.ok(deck.includes('aria-pressed={i === selectedIndex}'));
  assert.ok(deck.includes('aria-live="polite"'));
  assert.ok(deck.includes('<div className="album-deck-detail">'));
  assert.ok(
    !deck.includes('<div className="album-deck-detail" key='),
    'Buttons must not remount during navigation',
  );
  assert.ok(deck.includes('Math.min(index, Math.max(0, items.length - 1))'));
  assert.ok(deck.includes('if (!selected)'));
  assert.ok(deck.includes("api.off('select', update)"));
  assert.ok(deck.includes("api.off('reInit', update)"));
});

test('motion preferences apply to both carousel physics and CSS while touch scrolling remains available', () => {
  assert.ok(deck.includes('duration: reducedMotion ? 0 : 28'));
  assert.ok(deck.includes('api?.scrollPrev(reducedMotion)'));
  assert.ok(deck.includes('api?.scrollNext(reducedMotion)'));
  assert.ok(deck.includes("preference.removeEventListener('change', update)"));
  assert.match(css, /touch-action: pan-y pinch-zoom;/);
  const reduced = css.slice(
    css.indexOf('@media (prefers-reduced-motion: reduce)'),
  );
  assert.match(reduced, /transition: none;/);
  assert.match(reduced, /animation: none;/);
  assert.ok(!css.includes('infinite'));
  assert.match(
    css,
    /\.album-deck-arrow\s*\{[^}]*width: 44px;[^}]*height: 44px;/,
  );
  assert.match(css, /@container home-feed \(max-width: 12rem\)/);
});
