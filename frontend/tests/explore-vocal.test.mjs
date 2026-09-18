import test from 'node:test';
import assert from 'node:assert/strict';
import { vocalFilterValues, vocalQueryValue } from '../lib/explore-vocal.ts';

test('화면 문구를 서버 값으로 옮긴다', () => {
  assert.deepEqual(
    vocalFilterValues(['보컬·악기/보컬/여성 보컬']),
    ['female'],
  );
  assert.deepEqual(
    vocalFilterValues(['보컬·악기/보컬/남성 보컬']),
    ['male'],
  );
  assert.deepEqual(
    vocalFilterValues(['보컬·악기/보컬/인스트루멘탈']),
    ['instrumental'],
  );
});

test('보컬이 아닌 선택은 서버로 보내지 않는다', () => {
  // 보컬·악기 탭의 기타/건반/리듬, 분위기, 장르는 아직 서버 계약이 없다.
  // 보내면 서버가 400 을 주므로 여기서 걸러야 한다.
  assert.deepEqual(
    vocalFilterValues([
      '보컬·악기/기타/어쿠스틱 기타',
      '보컬·악기/건반/피아노',
      '보컬·악기/리듬/드럼',
      '분위기/무드/행복한',
      '분위기/템포/빠른',
      '분위기/에너지/강렬한',
      '장르/장르/Pop',
    ]),
    [],
  );
  assert.equal(
    vocalQueryValue(['보컬·악기/기타/어쿠스틱 기타']),
    undefined,
    '보컬이 아니면 vocal 파라미터를 만들지 않는다',
  );
});

test('여러 개를 고르면 쉼표로 잇고 중복을 없앤다', () => {
  assert.equal(
    vocalQueryValue(['보컬·악기/보컬/여성 보컬', '보컬·악기/보컬/남성 보컬']),
    'female,male',
  );
  assert.equal(
    vocalQueryValue([
      '보컬·악기/보컬/여성 보컬',
      '보컬·악기/보컬/여성 보컬',
      '보컬·악기/보컬/인스트루멘탈',
    ]),
    'female,instrumental',
  );
});

test('아무것도 안 고르면 vocal 파라미터가 없다 (하위 호환)', () => {
  // undefined 라야 주소에 `vocal=` 이 붙지 않는다. 서버도 빈 값을 걸지 않는다.
  assert.equal(vocalQueryValue([]), undefined);
  assert.equal(vocalQueryValue(['보컬·악기/보컬/여성 보컬'].slice(1)), undefined);
});
