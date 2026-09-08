import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, access } from 'node:fs/promises';

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');
const [desktop, home, shell, css, globals, shared] = await Promise.all([
  read('components/mupid/desktop-home.tsx'),
  read('components/mupid/home.tsx'),
  read('components/mupid-app.tsx'),
  read('app/desktop-home.css'),
  read('app/globals.css'),
  read('app/sky-shell.css'),
]);

test('desktop home uses the existing LP asset in a three-part listening layout', async () => {
  await access(new URL('../public/sky-vinyl.webp', import.meta.url));
  assert.ok(desktop.includes('className="desktop-listening-room"'));
  assert.ok(desktop.includes('className="desktop-record-stage"'));
  assert.ok(desktop.includes('className="desktop-song"'));
  assert.ok(desktop.includes('className="desktop-playlists"'));
  assert.match(desktop, /src="\/sky-vinyl.webp"/);
  assert.ok(desktop.includes('<h1>{m.active.title}</h1>'));
  assert.match(css, /width: clamp\(400px, 39vw, 520px\)/);
});

test('main playback keeps transport, seek, volume, likes, moments and queue access', () => {
  assert.ok(desktop.includes('<PlayerControls model={m} />'));
  assert.ok(desktop.includes('<LikeButton model={m} />'));
  assert.ok(desktop.includes('m.seek(Number(e.target.value))'));
  assert.ok(desktop.includes('m.setVolume(Number(e.target.value))'));
  assert.ok(desktop.includes('m.setQueueDialog(true)'));
  assert.ok(desktop.includes('m.openMoment()'));
  assert.ok(desktop.includes("m.go('player')"));
  assert.ok(desktop.includes('aria-valuetext='));
});

test('playlist highlights represent the current queue and keep details and play separate', () => {
  assert.ok(
    desktop.includes('playable.some((track) => track.id === m.active.id)'),
  );
  assert.ok(desktop.includes('playable.length === m.queueTracks.length'));
  assert.ok(desktop.includes('track.id === m.queueTracks[i].id'));
  assert.ok(desktop.includes('queued ? m.play() : m.playPlaylist(playlist)'));
  assert.ok(desktop.includes('onClick={() => openLibrary(playlist.id)}'));
  assert.ok(desktop.includes('disabled={!playable.length}'));
  assert.ok(desktop.includes('!m.playlists.length'));
});

test('desktop home uses the shared menu while replacing its duplicate dock at its breakpoint', () => {
  assert.ok(globals.includes("@import './desktop-home.css'"));
  const desktopRules = css.indexOf('@media (min-width: 1024px)');
  assert.ok(desktopRules > 0);
  assert.match(
    css.slice(0, desktopRules),
    /\.desktop-home\s*\{\s*display: none;/,
  );
  assert.ok(shared.includes('.mupid-shell .side-nav'));
  assert.ok(!css.slice(desktopRules).includes('.page-home .mini-player'));
  assert.ok(css.slice(desktopRules).includes('.page-home .home-grid {'));
  assert.ok(shell.includes('aria-label="PC 메인 메뉴"'));
  assert.ok(shell.includes('className="desktop-app-notification"'));
  assert.ok(home.includes('<DesktopHome model={m} />'));
  assert.ok(home.includes('className="home-grid"'));
  assert.ok(home.includes('<MobileListening model={m} />'));
  assert.ok(!home.includes('className="quick-links"'));
});

test('search, recommendations, history and AI remain available on the new home', () => {
  assert.ok(desktop.includes('className="desktop-search"'));
  assert.ok(desktop.includes('m.setQuery(e.target.value)'));
  assert.ok(desktop.includes("m.go('search')"));
  assert.ok(desktop.includes('className="desktop-recommendation-list"'));
  assert.ok(desktop.includes('className="desktop-recent-list"'));
  assert.ok(desktop.includes('onClick={m.openChat}'));
});

test('large text uses a two-column fallback without changing mobile visibility', () => {
  assert.match(css, /@container desktop-room \(max-width: 52rem\)/);
  assert.match(css, /grid-template-columns: minmax\(0, 1fr\) minmax\(0, 1fr\)/);
  assert.match(shared, /\.desktop-app-nav\s*\{[^}]*flex-wrap: wrap;/);
  assert.match(
    css,
    /\.desktop-song h1\s*\{[^}]*word-break: keep-all;[^}]*overflow-wrap: anywhere;/,
  );
  for (const viewport of [1024, 1280, 1440, 1920]) {
    const width = Math.min(1440, viewport - 64);
    assert.ok(width > 52 * 16, 'Regular desktop shows the three-part layout');
    assert.ok(width <= 52 * 32, '200% text uses the roomy two-column layout');
  }
});
