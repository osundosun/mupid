import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');
const [
  mascot,
  primitives,
  home,
  desktop,
  player,
  chat,
  collection,
  css,
  base,
  globals,
] = await Promise.all([
  read('components/mupid/mascot.tsx'),
  read('components/mupid/primitives.tsx'),
  read('components/mupid/home.tsx'),
  read('components/mupid/desktop-home.tsx'),
  read('components/mupid/discovery.tsx'),
  read('components/mupid/chat.tsx'),
  read('components/mupid/collection.tsx'),
  read('app/mascot-details.css'),
  read('app/mupid.css'),
  read('app/globals.css'),
]);

void test('decorative companions reuse the transparent asset and do not intercept controls', () => {
  assert.ok(mascot.includes('src="/mupid-cupid-side.webp"'));
  const accent = mascot.slice(mascot.indexOf('export function MascotAccent'));
  assert.ok(accent.includes('aria-hidden="true"'));
  assert.ok(accent.includes('<MupidMascot size={size} />'));
  assert.ok(!accent.includes('onClick'));
  assert.ok(!accent.includes('tabIndex'));
  assert.match(
    css,
    /\.mascot-accent\s*\{[^}]*aspect-ratio: 1;[^}]*pointer-events: none;/,
  );
  assert.ok(!css.includes('position: fixed'));
  assert.ok(!css.includes('overflow: hidden'));
  assert.ok(!css.includes('mask-image'));
  assert.ok(globals.includes("@import './mascot-details.css'"));
});

void test('page titles retain heading semantics, back buttons and optional actions', () => {
  const header = primitives.slice(
    primitives.indexOf('export function PageHeader'),
    primitives.indexOf('export function TrackRow'),
  );
  assert.ok(header.includes('<h1>{title}</h1>'));
  assert.ok(header.includes('aria-label="뒤로 가기" onClick={onBack}'));
  assert.ok(header.includes('<div>{aside}</div>'));
  assert.ok(header.includes('mascot = true'));
  assert.ok(header.includes('className="page-title-group"'));
  assert.ok(
    collection.includes('mascot={false}'),
    'Profile already has its own large mascot',
  );
});

void test('home and AI retain companions while LP artwork stays free of characters', () => {
  assert.ok(home.includes('className="greeting-companion"'));
  assert.ok(home.includes('<HomeNotifications model={m} />'));
  assert.ok(!desktop.includes('className="desktop-record-companion"'));
  assert.ok(desktop.includes('className="desktop-record-caption"'));
  assert.ok(desktop.includes('<PlayerControls model={m} />'));
  assert.ok(!player.includes('className="player-cupid"'));
  assert.ok(player.includes('m.seek(Number(e.target.value))'));
  const record = desktop.slice(
    desktop.indexOf('className="desktop-record-stage"'),
    desktop.indexOf('<section className="desktop-song"'),
  );
  const playerArt = player.slice(
    player.indexOf('className="player-art-area"'),
    player.indexOf('className="player-detail"'),
  );
  assert.ok(!record.includes('Mascot'));
  assert.ok(!playerArt.includes('Mascot'));
  assert.ok(!css.includes('player-cupid'));
});

void test('the character AI button preserves its accessible name, focus target and mobile touch area', () => {
  const launcher = chat.slice(
    chat.indexOf('if (!m.chatOpen)'),
    chat.indexOf('<aside'),
  );
  assert.ok(launcher.includes('aria-label="Mupid AI 음악 탐색 열기"'));
  assert.ok(launcher.includes('ref={chatButtonRef}'));
  assert.ok(launcher.includes('onClick={m.openChat}'));
  assert.ok(launcher.includes('className="chat-launcher-mascot"'));
  assert.match(
    base,
    /\.chat-launcher\s*\{[^}]*min-height: 46px;[^}]*width: 46px;/,
  );
  assert.match(
    css,
    /\.chat-launcher \.chat-launcher-mascot\s*\{\s*width: 40px;\s*height: 40px;/,
  );
});

void test('small screens and enlarged text give space back to content and honor reduced motion', () => {
  assert.match(css, /@media \(max-width: 767px\)/);
  assert.match(
    css,
    /@container mascot-title \(max-width: 12rem\)\s*\{\s*\.page-title-mascot\s*\{\s*display: none;/,
  );
  assert.match(css, /@container home-feed \(max-width: 12rem\)/);
  assert.match(
    css,
    /@media \(prefers-reduced-motion: reduce\)[\s\S]*transition: none;/,
  );
  assert.ok(!css.includes('infinite'));
  for (const viewport of [320, 360, 375, 390, 414, 520]) {
    const gutter =
      viewport < 359 ? 14 : Math.max(18, Math.min(24, viewport * 0.046));
    const contentWidth = viewport - 2 * gutter;
    const titleWidth = contentWidth - 2 * 44 - 2 * 6;
    for (const fontSize of [16, 32]) {
      const hideMascot = titleWidth <= 12 * fontSize;
      const textWidth = titleWidth - (hideMascot ? 0 : 40 + 8);
      assert.ok(
        textWidth >= 4 * fontSize,
        'Title retains usable wrapping width',
      );
      if (fontSize === 32) assert.equal(hideMascot, true);
    }
  }
});
