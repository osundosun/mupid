import test from 'node:test';
import assert from 'node:assert/strict';
import { squarify } from '../lib/treemap.ts';

/**
 * 트리맵의 계약은 하나다 — **면적이 곧 값이다.**
 *
 * 예전 화면은 모든 타일을 같은 크기로 그리고 "면적이 시간을 나타내지 않는다" 고
 * 주석에 적어 두었다. 이제는 나타내므로, 그게 실제로 맞는지 검사한다.
 */

const area = (rect) => rect.w * rect.h;
const overlaps = (a, b) =>
  a.x < b.x + b.w - 1e-9 &&
  b.x < a.x + a.w - 1e-9 &&
  a.y < b.y + b.h - 1e-9 &&
  b.y < a.y + a.h - 1e-9;

test('면적이 값에 비례한다', () => {
  const values = [5000, 3000, 2000, 1000, 500];
  const rects = squarify(values);
  const total = values.reduce((sum, v) => sum + v, 0);
  rects.forEach((rect, index) => {
    // 100×100 영역이므로 면적 비율 × 10000 이 기대 면적이다.
    assert.ok(
      Math.abs(area(rect) - (values[index] / total) * 10000) < 0.5,
      `${index}번 타일의 면적이 값과 어긋난다`,
    );
  });
});

test('타일이 영역을 빈틈없이 덮는다', () => {
  const rects = squarify([9, 7, 5, 4, 3, 2, 1, 1]);
  const covered = rects.reduce((sum, rect) => sum + area(rect), 0);
  assert.ok(Math.abs(covered - 10000) < 1, '덮인 면적이 100×100 과 다르다');
  for (const rect of rects) {
    assert.ok(rect.x >= -1e-9 && rect.y >= -1e-9, '영역 밖으로 나갔다');
    assert.ok(rect.x + rect.w <= 100 + 1e-6, '오른쪽으로 넘쳤다');
    assert.ok(rect.y + rect.h <= 100 + 1e-6, '아래로 넘쳤다');
  }
});

test('타일끼리 겹치지 않는다', () => {
  const rects = squarify([40, 25, 15, 10, 6, 4]);
  for (let i = 0; i < rects.length; i++) {
    for (let j = i + 1; j < rects.length; j++) {
      assert.ok(!overlaps(rects[i], rects[j]), `${i}번과 ${j}번 타일이 겹친다`);
    }
  }
});

test('한 곡만 들었으면 한 타일이 전체를 채운다', () => {
  const rects = squarify([1234]);
  assert.equal(rects.length, 1);
  assert.ok(Math.abs(area(rects[0]) - 10000) < 1e-6);
});

test('곡이 없으면 빈 배열이다', () => {
  assert.deepEqual(squarify([]), []);
});

test('0 인 값도 클릭할 수 있는 타일을 받는다', () => {
  // 면적 0 인 타일은 눌 수 없어 화면에서 사라진 것과 같다. 1ms 로 올려 잡는다.
  const rects = squarify([1000, 0]);
  assert.equal(rects.length, 2);
  assert.ok(area(rects[1]) > 0, '0 인 값의 타일이 사라졌다');
});

test('길쭉한 띠를 만들지 않는다 (squarify 의 목적)', () => {
  // 균등한 값 16개는 4×4 에 가깝게 놓여야 한다. 단순히 잘라 나가면 1:16 띠가 된다.
  const rects = squarify(Array.from({ length: 16 }, () => 100));
  for (const rect of rects) {
    const ratio = Math.max(rect.w / rect.h, rect.h / rect.w);
    assert.ok(ratio < 3, `종횡비 ${ratio.toFixed(2)} 는 너무 납작하다`);
  }
});
