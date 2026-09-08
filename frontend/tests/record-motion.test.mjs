import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
import test from 'node:test';

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8');

void test('mobile filters wrap instead of clipping or horizontally scrolling', () => {
  const css = read('app/discovery-design.css').split('@media (max-width: 767px)')[1];
  const filters = css.match(/\.explore-filters \{([^}]+)\}/)[1];
  assert.match(filters, /flex-wrap: wrap/);
  assert.match(filters, /overflow: visible/);
  assert.match(filters, /margin-right: 0/);
  assert.match(css, /overflow-wrap: anywhere/);
  assert.match(read('components/mupid/explore.tsx'), /\['전체', \.\.\.moodOptions\]/);
});

void test('all LP placements follow actual playback state without remounting the animation', () => {
  for (const name of ['home', 'desktop-home', 'mobile-listening', 'discovery']) {
    assert.match(read(`components/mupid/${name}.tsx`), /<VinylRecord\s+playing=\{m.playing\}/);
  }
  const component = read('components/mupid/vinyl-record.tsx');
  assert.match(component, /data-playing=\{playing\}/);
  assert.doesNotMatch(component.slice(component.indexOf('  return (')), /key=|playing &&/);
});

void test('only the cropped label rotates at 33⅓ rpm, with paused angle and reduced-motion support', () => {
  const css = read('app/record-motion.css');
  const component = read('components/mupid/vinyl-record.tsx');
  assert.match(component, /playing \? 30 : 0/);
  assert.match(component, /Math.exp/);
  assert.match(component, /prefers-reduced-motion: reduce/);
  assert.match(component, /cancelAnimationFrame/);
  assert.match(component, /state.speed < 0.5/);
  assert.doesNotMatch(css.match(/\.record-motion > \.record-photo \{([^}]+)\}/)[1], /animation|transform/);
  // Source crop geometry: the label and stationary pin share the spindle.
  assert.equal(417 + 380 / 2, 581 + 52 / 2);
  assert.equal(411 + 380 / 2, 575 + 52 / 2);
});
