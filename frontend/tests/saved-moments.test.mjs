import test from 'node:test';
import assert from 'node:assert/strict';
import {
  groupSavedMoments,
  mergeMomentRanges,
  momentPlaybackRanges,
} from '../lib/saved-moments.ts';

const track = (id, title) => ({ id, title, duration: 200 });
const moment = (id, owner, start, end, section) => ({
  id,
  track: owner,
  start,
  end,
  section,
});

test('저장 행은 유지하면서 같은 곡의 순간을 시간순으로 묶는다', () => {
  const first = track(1, '첫 곡');
  const second = track(2, '둘째 곡');
  const groups = groupSavedMoments([
    moment(11, first, 50, 60, '코러스'),
    moment(21, second, 5, 10, '인트로'),
    moment(12, first, 0, 12, '인트로'),
  ]);

  assert.equal(groups.length, 2);
  assert.deepEqual(groups[0].moments.map(({ id }) => id), [12, 11]);
  assert.deepEqual(groups[1].moments.map(({ id }) => id), [21]);
});

test('겹치거나 맞닿은 순간만 합치고 떨어진 순간은 다음 재생 지점으로 남긴다', () => {
  const owner = track(1, '첫 곡');
  const ranges = mergeMomentRanges([
    moment(1, owner, 20, 30, '벌스'),
    moment(2, owner, 0, 10, '인트로'),
    moment(3, owner, 10, 15, '프리코러스'),
    moment(4, owner, 28, 35, '코러스'),
  ]);

  assert.deepEqual(ranges.map(({ start, end }) => [start, end]), [[0, 15], [20, 35]]);
});

test('연속된 두 번째 순간을 누르면 병합 구간의 처음이 아니라 클릭 지점부터 재생한다', () => {
  const owner = track(1, '첫 곡');
  const intro = moment(1, owner, 0, 10, '인트로');
  const verse = moment(2, owner, 10, 20, '벌스');
  const chorus = moment(3, owner, 30, 40, '코러스');

  const ranges = momentPlaybackRanges([intro, verse, chorus], verse);

  assert.deepEqual(ranges.map(({ start, end }) => [start, end]), [[10, 20], [30, 40]]);
});

test('뒤쪽 순간을 듣다가 도입을 누르면 재생 범위가 다시 0초부터 만들어진다', () => {
  const owner = track(1, '첫 곡');
  const intro = moment(1, owner, 0, 10, '도입');
  const development = moment(2, owner, 10, 20, '전개');

  const developmentRanges = momentPlaybackRanges([intro, development], development);
  const introRanges = momentPlaybackRanges([intro, development], intro);

  assert.equal(developmentRanges[0].start, 10);
  assert.equal(introRanges[0].start, 0);
});
