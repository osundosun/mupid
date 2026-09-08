import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const css = await readFile(
  new URL('../app/mupid.css', import.meta.url),
  'utf8',
);
const rule = (selector) => {
  const start = css.indexOf(selector + ' {');
  assert.notEqual(start, -1, selector);
  return css.slice(start, css.indexOf('}', start) + 1);
};

test('music cards step down from four columns when the feed becomes too narrow', async () => {
  const home = await readFile(
    new URL('../components/mupid/home.tsx', import.meta.url),
    'utf8',
  );
  assert.ok(home.includes('className="home-feed"'));
  assert.match(rule('.home-feed'), /container-type: inline-size;/);
  assert.match(rule('.home-feed'), /container-name: home-feed;/);
  assert.match(
    css,
    /@container home-feed \(max-width: 19rem\)\s*\{\s*\.mix-grid\s*\{\s*grid-template-columns: repeat\(2, minmax\(0, 1fr\)\);/,
  );
  assert.match(
    css,
    /@container home-feed \(max-width: 12rem\)\s*\{\s*\.mix-grid\s*\{\s*grid-template-columns: minmax\(0, 1fr\);/,
  );
});

test('covers and titles stay top aligned without truncating normal words', () => {
  assert.match(rule('.mix-card'), /display: flex;/);
  assert.match(rule('.mix-card'), /justify-content: flex-start;/);
  assert.match(rule('.mix-cover'), /aspect-ratio: 1;/);
  assert.match(rule('.mix-card strong'), /word-break: keep-all;/);
  assert.ok(!rule('.mix-card strong').includes('line-clamp'));
  assert.ok(!rule('.mix-card strong').includes('overflow: hidden'));
  assert.match(rule('.section-heading'), /flex-wrap: wrap;/);
  assert.match(rule('.section-heading > .text-link'), /flex-shrink: 0;/);
});
