import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const source = (path) =>
  readFile(new URL('../' + path, import.meta.url), 'utf8');

test('chat and the rest of the app share Pretendard without loading handwriting', async () => {
  const css = await source('app/globals.css');
  assert.doesNotMatch(css, /Griun|Gyuwon/);
  assert.match(css, /--font-chat: var\(--font-mupid\)/);
  assert.match(
    css,
    /\.chat-handwriting::placeholder\s*\{\s*font-family: var\(--font-chat\) !important/,
  );
});

test('handwriting is limited to conversation, suggestions, and composer', async () => {
  const chat = await source('components/mupid/chat.tsx');
  assert.match(chat, /<p className="chat-handwriting">\{message.text\}/);
  assert.match(chat, /<input[\s\S]*?className="chat-handwriting"/);
  assert.equal(
    (chat.match(/className="(?:thinking )?chat-handwriting"/g) ?? []).length,
    4,
  );
  assert.ok(!chat.includes('className="chat-panel chat-handwriting"'));
});

test('mobile menu labels preserve full accessible names and wrap enlarged text', async () => {
  const app = await source('components/mupid-app.tsx');
  const css = await source('app/mobile-navigation.css');
  assert.match(app, /aria-label=\{label\}/);
  assert.match(app, /mobileLabel: '플리'/);
  assert.match(css, /\.mobile-nav-label\s*\{[^}]*word-break: keep-all;/);
  assert.match(css, /repeat\(5, minmax\(0, 1fr\)\)/);
  assert.ok(!css.includes('overflow: hidden'));
  assert.ok(!css.includes('text-overflow: ellipsis'));
});

test('chat handwriting uses compact sizes without shrinking input below 16px', async () => {
  const css = await source('app/mupid.css');
  assert.match(
    css,
    /\.message-body > p\s*\{[^}]*font-size: 0\.9375rem;[^}]*line-height: 1\.6;/,
  );
  assert.match(css, /\.chat-suggestions button\s*\{[^}]*font-size: 0\.875rem;/);
  assert.match(css, /\.chat-composer input\s*\{[^}]*font-size: 1rem;/);
  assert.match(
    css,
    /\.chat-composer input::placeholder\s*\{[^}]*font-size: 0\.875rem;/,
  );
});
