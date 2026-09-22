import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const hook = await readFile(new URL('../hooks/use-mupid.ts', import.meta.url), 'utf8');
const effect = hook.match(/const previousPlaybackPath = useRef\(pathname\);\s*useEffect\(\(\) => \{([\s\S]*?)\}, \[pathname\]\);/);
assert.ok(effect, 'Playback navigation effect must exist');
const navigate = new Function('previousPlaybackPath', 'pathname', 'pagePaths', 'previewEnd', 'momentSequence', 'setSavedMomentPlayback', effect[1]);

test('leaving moment editor removes preview stop conditions without stopping audio', () => {
  const previous = { current: '/moments' };
  const end = { current: 67 };
  const sequence = { current: { ranges: [{ start: 41, end: 67 }] } };
  let saved = 'preview';
  navigate(previous, '/player', { moment: '/moments' }, end, sequence, (value) => { saved = value; });
  assert.equal(end.current, null);
  assert.equal(sequence.current, null);
  assert.equal(saved, null);
  assert.equal(previous.current, '/player');
});

test('remaining in moment editor preserves intentional preview boundaries', () => {
  const end = { current: 67 };
  const ranges = { ranges: [{ start: 41, end: 67 }] };
  const sequence = { current: ranges };
  navigate({ current: '/moments' }, '/moments', { moment: '/moments' }, end, sequence, () => assert.fail('Preview must remain active'));
  assert.equal(end.current, 67);
  assert.equal(sequence.current, ranges);
});
