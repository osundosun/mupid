import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { filterExploreTracks, getExploreWindow } from '../lib/explore.ts';
import { tracks } from '../lib/mupid-data.ts';

test('explore search preserves title, artist, genre, mood, tag and hidden-track filtering', () => {
  assert.deepEqual(filterExploreTracks(tracks, '', '전체', []), tracks);
  for (const query of [
    '푸른 오후',
    '  lume  ',
    'Electronic',
    '몽환적인',
    '#슬로우빌드',
  ]) {
    assert.equal(filterExploreTracks(tracks, query, '전체', [])[0].id, 1);
  }
  assert.equal(filterExploreTracks(tracks, 'LUME', '잔잔한', []).length, 0);
  assert.equal(filterExploreTracks(tracks, 'LUME', '전체', [1]).length, 0);
  assert.equal(
    filterExploreTracks(tracks, '존재하지않는음악', '전체', []).length,
    0,
  );
  assert.ok(
    filterExploreTracks(tracks, '', '잔잔한', []).every(
      (t) => t.mood === '잔잔한',
    ),
  );
});

test('circular explorer displays at most seven unique albums and exactly one selection', () => {
  for (let count = 1; count <= tracks.length; count++) {
    const items = tracks.slice(0, count);
    for (let index = 0; index < count; index++) {
      const window = getExploreWindow(items, index);
      assert.equal(window.length, Math.min(7, count));
      assert.equal(
        new Set(window.map(({ item }) => item.id)).size,
        window.length,
      );
      assert.equal(window.filter(({ offset }) => offset === 0).length, 1);
      assert.equal(
        window.find(({ offset }) => offset === 0).item.id,
        items[index].id,
      );
      for (const entry of window) {
        assert.ok(entry.offset >= -3 && entry.offset <= 3);
        assert.equal(items[entry.index], entry.item);
      }
    }
  }
});

test('empty, single-album and boundary selections stay valid', () => {
  assert.deepEqual(getExploreWindow([], 0), []);
  assert.deepEqual(getExploreWindow([tracks[0]], 0), [
    { item: tracks[0], index: 0, offset: 0 },
  ]);
  assert.equal(
    getExploreWindow(tracks, -1).find((t) => t.offset === 0).item.id,
    tracks.at(-1).id,
  );
  assert.equal(
    getExploreWindow(tracks, tracks.length).find((t) => t.offset === 0).item.id,
    tracks[0].id,
  );
  assert.equal(
    getExploreWindow(tracks, 0).find((t) => t.offset === -1).item.id,
    tracks.at(-1).id,
  );
});

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');
const [explore, css, home, globals] = await Promise.all([
  read('components/mupid/explore.tsx'),
  read('app/discovery-design.css'),
  read('components/mupid/home.tsx'),
  read('app/globals.css'),
]);

test('album selection is separate from playback, with an accessible full-list alternative', () => {
  assert.ok(explore.includes('onClick={() => setFocusedId(track.id)}'));
  assert.ok(explore.includes('onClick={() => m.play(track)}'));
  assert.ok(explore.includes('onClick={() => m.openTrack(selected)}'));
  assert.ok(explore.includes('<LikeButton model={m} track={selected} />'));
  assert.ok(explore.includes('aria-pressed={offset === 0}'));
  assert.ok(explore.includes('aria-live="polite"'));
  assert.ok(explore.includes('aria-label="이전 앨범"'));
  assert.ok(explore.includes('aria-label="다음 앨범"'));
  assert.ok(explore.includes('disabled={results.length < 2}'));
  assert.ok(explore.includes('results.map((track) => ('));
  assert.ok(explore.includes('onTouchCancel='));
  assert.ok(explore.includes('Math.abs(dx) > Math.abs(dy) * 1.5'));
});

test('explore adapts to compact widths, enlarged text and reduced motion', () => {
  assert.ok(globals.includes("@import './discovery-design.css'"));
  assert.match(css, /@media \(max-width: 767px\)/);
  assert.match(css, /@container explore-wheel \(max-width: 12rem\)/);
  assert.match(css, /@media \(prefers-reduced-motion: reduce\)/);
  assert.match(css, /touch-action: pan-y;/);
  assert.match(
    css,
    /\.explore-track-copy strong\s*\{[^}]*word-break: keep-all;/,
  );
  for (const selector of [
    '.explore-orbit',
    '.explore-orbit-window',
    '.explore-orbit-items',
    '.explore-orbit-item',
  ]) {
    assert.ok(!rule(selector).includes('overflow: hidden'));
  }
});

function rule(selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = css.match(
    new RegExp('(?:^|\\})\\s*' + escaped + '\\s*\\{([^}]*)\\}'),
  );
  assert.ok(match, selector);
  return match[1];
}

test('compact arc tilts bounded labels instead of whole rows and retains full track names', () => {
  assert.match(rule('.explore-orbit'), /width: min\(100%, 360px\);/);
  assert.match(
    rule('.explore-orbit-item'),
    /transform: translate\(var\(--arc-shift\), -50%\);/,
  );
  assert.ok(!rule('.explore-orbit-item').includes('rotate('));
  assert.match(rule('.explore-track-copy'), /transform-origin: center;/);
  assert.match(css, /text-overflow: ellipsis;\s*white-space: nowrap;/);
  assert.ok(explore.includes('title={`${track.title} · ${track.artist}`}'));
  assert.ok(explore.includes('<h2>{selected.title}</h2>'));
  assert.ok(explore.includes('className="explore-orbit-play"'));
  assert.ok(!explore.includes('className="icon-button explore-orbit-play"'));
  assert.match(rule('.explore-orbit-play'), /min-height: 44px;/);
  const fallback = css.slice(css.indexOf('@container explore-wheel'));
  assert.match(fallback, /\.explore-track-copy\s*\{[^}]*transform: none;/);
  assert.match(fallback, /white-space: normal;/);
  assert.match(fallback, /\.explore-orbit-items\s*\{[^}]*height: auto;/);
});

test('arc geometry leaves clear space between labels, covers and the selected row', () => {
  const windowRule = rule('.explore-orbit-window');
  const value = (body, property) => {
    const match = body.match(new RegExp(property + ': ([\\d.]+)'));
    assert.ok(match, property);
    return Number(match[1]);
  };
  const stepRem = value(windowRule, '--orbit-step');
  const coverRem = value(windowRule, '--orbit-cover');
  const rowRem = value(windowRule, '--orbit-row-height');
  const titleRem = value(rule('.explore-track-copy strong'), 'font-size');
  const artistRem = value(rule('.explore-track-copy small'), 'font-size');
  const lineHeight = value(rule('.explore-track-copy strong'), 'line-height');

  for (const railWidth of [280, 292, 320, 344, 360]) {
    for (const rootFont of [16, 20, 24, 32]) {
      if (railWidth <= 12 * rootFont) continue; // Unrotated flow at enlarged text sizes.
      const step = stepRem * rootFont;
      const cover = coverRem * rootFont;
      const rowHeight = rowRem * rootFont;
      const labelHeight = (titleRem + artistRem) * rootFont * lineHeight + 4;
      const halfHeights = [rowHeight / 2];
      for (let distance = 1; distance <= 3; distance++) {
        const body = rule(`.explore-orbit-item[data-distance='${distance}']`);
        const shift = value(body, '--arc-shift');
        const angle = (value(body, '--arc-angle') * Math.PI) / 180;
        const copyWidth = Math.min(
          value(body, '--copy-max') * rootFont,
          railWidth - shift - 8 - cover - 12,
        );
        assert.ok(copyWidth > 0);
        const rotatedHeight =
          copyWidth * Math.sin(angle) + labelHeight * Math.cos(angle);
        const halfHeight = Math.max(rowHeight, rotatedHeight) / 2;
        assert.ok(
          step - halfHeights[distance - 1] - halfHeight >= 4,
          `Clear gap at width ${railWidth}, font ${rootFont}, distance ${distance}`,
        );
        halfHeights.push(halfHeight);
      }

      // One to seven results use only as much height as they need, including even counts.
      for (let count = 1; count <= 7; count++) {
        const items = getExploreWindow(tracks.slice(0, count), 0);
        const before = -items[0].offset;
        const height = (count - 1) * step + rowHeight + 20;
        for (const { offset } of items) {
          const center = (before + offset) * step + rowHeight / 2 + 10;
          assert.ok(center - halfHeights[Math.abs(offset)] >= 0);
          assert.ok(center + halfHeights[Math.abs(offset)] <= height);
        }
      }
    }
  }
  assert.ok(explore.includes("'--orbit-count': visibleAlbums.length"));
  assert.ok(explore.includes("'--orbit-before': Math.max("));
  assert.ok(windowRule.includes('var(--orbit-count) - 1'));
  assert.ok(
    rule('.explore-orbit-item').includes('var(--orbit-before) + var(--offset)'),
  );
});

test('desktop removes repeated card shells while mobile retains its listening entry', () => {
  const desktop = css.slice(css.indexOf('@media (min-width: 768px)'));
  assert.match(
    desktop,
    /\.main-content \.track-row\s*\{[^}]*background: transparent;[^}]*border: 0;[^}]*border-bottom:/,
  );
  assert.match(
    desktop,
    /\.now-card,\s*\.ai-invitation\s*\{[^}]*background: none;[^}]*border: 0;/,
  );
  assert.match(desktop, /\.quick-link\s*\{[^}]*flex-direction: row;/);
  assert.ok(home.includes('<MobileListening model={m} />'));
  assert.ok(!home.includes('className="quick-links"'));
  assert.ok(!home.includes('오늘의 하늘에는 어떤 음악이 어울릴까요?'));
  assert.ok(home.includes('좋아하는 곡에서, 새로운 발견.'));
});
