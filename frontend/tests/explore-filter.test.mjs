import assert from 'node:assert/strict';
import test from 'node:test';
import { exploreFilterQuery, toggleExploreFilter } from '../lib/explore-filter.ts';

test('DB code_name과 보컬 enum 값을 검색 쿼리로 만든다', () => {
  assert.deepEqual(
    exploreFilterQuery(['mood:calm', 'mood:warm', 'genre:poprock', 'vocal:mixed']),
    { mood: 'calm,warm', genre: 'poprock', vocal: 'mixed' },
  );
});

test('인스트루멘탈을 고르면 모든 보컬 성별 선택을 해제한다', () => {
  assert.deepEqual(
    toggleExploreFilter(['mood:calm', 'vocal:female', 'vocal:mixed'], 'vocal:instrumental'),
    ['mood:calm', 'vocal:instrumental'],
  );
});

test('남성·여성·혼성·인스트루멘탈은 서로 하나만 선택한다', () => {
  assert.deepEqual(
    toggleExploreFilter(['genre:poprock', 'vocal:instrumental'], 'vocal:female'),
    ['genre:poprock', 'vocal:female'],
  );
  assert.deepEqual(
    toggleExploreFilter(['vocal:female'], 'vocal:mixed'),
    ['vocal:mixed'],
  );
});
