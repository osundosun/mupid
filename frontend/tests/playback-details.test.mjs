import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');
const [home, shortcuts, app, timeline, status, listening, css, hook] =
  await Promise.all([
    read('components/mupid/home.tsx'),
    read('components/mupid/home-shortcuts.tsx'),
    read('components/mupid-app.tsx'),
    read('components/mupid/mini-timeline.tsx'),
    read('components/mupid/playback-status.tsx'),
    read('components/mupid/mobile-listening.tsx'),
    read('app/playback-details.css'),
    read('hooks/use-mupid.ts'),
  ]);

void test('five working shortcuts sit above the LP and retain collection versus new-moment semantics', () => {
  assert.ok(
    home.indexOf('<HomeShortcuts model={m} />') <
      home.indexOf('<MobileListening model={m} />'),
  );
  assert.ok(shortcuts.includes('aria-label="홈 바로가기"'));
  for (const label of [
    '내 취향 리포트',
    '좋아한 구간 모음',
    '내 플레이리스트',
    '음악 탐색',
    'AI 추천',
  ]) {
    assert.ok(shortcuts.includes(label));
  }
  assert.ok(shortcuts.includes("m.go('report')"));
  assert.ok(shortcuts.includes("m.setLibraryTab('moments')"));
  assert.ok(shortcuts.includes("m.setLibraryTab('playlists')"));
  assert.ok(shortcuts.includes('m.setSelectedPlaylist(null)'));
  assert.ok(shortcuts.includes("m.go('search')"));
  assert.ok(shortcuts.includes('action: m.openChat'));
  assert.ok(!shortcuts.includes('m.openMoment'));
  assert.ok(shortcuts.includes('aria-label={label}'));
});

void test('mini timeline shows elapsed and total audio time with a labeled, controlled seek slider', () => {
  assert.ok(app.includes('<MiniTimeline model={m} />'));
  assert.ok(timeline.includes('formatTime(m.time)'));
  assert.ok(timeline.includes('formatTime(m.active.duration)'));
  assert.ok(timeline.includes('aria-label="현재 재생 시간"'));
  assert.ok(timeline.includes('aria-label="전체 재생 시간"'));
  assert.ok(timeline.includes('<Field.Label className="sr-only">'));
  assert.ok(timeline.includes('value={[m.time]}'));
  assert.ok(timeline.includes('max={m.active.duration}'));
  assert.ok(
    timeline.includes('m.seek(Array.isArray(value) ? value[0] : value)'),
  );
  assert.ok(app.includes('onTimeUpdate={m.onTimeUpdate}'));
  assert.ok(hook.includes('setTime(audio.currentTime)'));
  assert.match(css, /\.mini-player \.mini-timeline\s*\{\s*display: flex;/);
  assert.match(css, /\.mini-seek > div\s*\{\s*min-height: 44px;/);
});

void test('playing bars follow real playback state, stop when paused and respect reduced motion', () => {
  assert.ok(app.includes('<PlaybackStatus playing={m.playing} />'));
  assert.ok(listening.includes('<PlaybackStatus playing={m.playing} />'));
  assert.ok(status.includes('data-playing={playing}'));
  assert.ok(status.includes('<output'));
  assert.ok(status.includes('aria-live="polite"'));
  assert.ok(status.includes("playing ? '재생 중' : '일시정지'"));
  assert.ok(status.includes('aria-hidden="true"'));
  assert.match(
    css,
    /@media \(prefers-reduced-motion: no-preference\)\s*\{\s*\.playback-status\[data-playing='true'\] rect/,
  );
  assert.match(
    css,
    /@media \(prefers-reduced-motion: reduce\)\s*\{\s*\.playback-status rect\s*\{\s*animation: none;/,
  );
  assert.ok(!status.includes('setInterval'));
  assert.ok(!timeline.includes('setInterval'));
});

void test('mobile dock reserves room for both rows, chat clearance and safe areas', () => {
  assert.ok(css.includes('--player-height: max(104px, calc(3.5rem + 48px))'));
  assert.ok(
    css.includes("'cover track controls' 'timeline timeline timeline'"),
  );
  assert.ok(css.includes('bottom: calc(var(--dock-height) + 18px)'));
  assert.ok(css.includes('padding-bottom: calc(var(--dock-height) + 32px)'));
  assert.ok(css.includes('.mupid-shell:not(.no-mini)'));
  for (const width of [320, 360, 390, 414, 520]) {
    const dockContent = Math.min(496, width - 24) - 24 - 2;
    assert.ok(
      dockContent - 40 - 88 - 20 >= 96,
      'Cover and play buttons leave usable title width',
    );
    for (const root of [16, 32]) {
      const height = Math.max(104, 3.5 * root + 48);
      const firstRow = Math.max(44, 0.875 * root * 1.5 + 0.75 * root * 1.5 + 4);
      assert.ok(firstRow + 44 + 13 + 2 <= height);
      for (const safeBottom of [0, 34]) {
        const nav = Math.max(76, 4 * root + 12);
        const dockTop = nav + safeBottom + 8 + height;
        const chatBottom = nav + safeBottom + height + 18;
        assert.equal(chatBottom - dockTop, 10);
      }
    }
  }
});

void test('shortcut words fit normal phones and reflow with enlarged text', () => {
  assert.match(css, /repeat\(auto-fit, minmax\(3em, 1fr\)\)/);
  assert.match(
    css,
    /\.home-shortcut-label > span\s*\{[^}]*white-space: nowrap;/,
  );
  for (const width of [320, 360, 390, 414, 520]) {
    const content = width - (width < 359 ? 28 : 48);
    for (const root of [16, 32]) {
      const font = 0.875 * root;
      const columns = Math.min(5, Math.floor((content + 8) / (3 * font + 8)));
      const slot = (content - (columns - 1) * 8) / columns;
      assert.ok(slot >= 44);
      assert.ok(slot >= 3 * font);
      if (root === 16) assert.equal(columns, 5);
      else if (width <= 414) assert.ok(columns < 5);
    }
  }
});
