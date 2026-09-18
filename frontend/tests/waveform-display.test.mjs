import test from 'node:test';
import assert from 'node:assert/strict';
import { resampleWaveform } from '../lib/waveform-display.ts';
test('실제 강약과 시간 순서를 보존하고 곡별 차이를 표시한다', () => {
  const a = resampleWaveform([0, .5, 1, .5, 0], 5);
  assert.deepEqual(a, [4, 26, 92, 26, 4]);
  assert.notDeepEqual(a, resampleWaveform([0, 1, .5, 1, 0], 5));
});
test('무음과 일정한 소리에 가짜 굴곡을 만들지 않는다', () => {
  assert.deepEqual(resampleWaveform([0, 0], 5), [4, 4, 4, 4, 4]);
  assert.deepEqual(resampleWaveform([.5, .5], 3), [26, 26, 26]);
});
test('축소·보간·잘못된 입력에서도 높이 범위를 지킨다', () => {
  assert.deepEqual(resampleWaveform([0, 1], 1), [26]);
  assert.deepEqual(resampleWaveform([0, 1], 3), [4, 26, 92]);
  assert.deepEqual(resampleWaveform([NaN, -1, 2], 3), [4, 4, 92]);
  assert.deepEqual(resampleWaveform([], 160), []);
});
