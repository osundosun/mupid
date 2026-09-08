import test from 'node:test';
import assert from 'node:assert/strict';
import { trackComments, validateComment } from '../lib/track-comments.ts';

test('comment text and song timestamp boundaries', () => {
  assert.equal(validateComment('좋아요', 0, 120), true);
  assert.equal(validateComment('끝부분', 120, 120), true);
  for (const [body, at, duration] of [[' ', 0, 120], ['a'.repeat(501), 0, 120], ['a', -1, 120], ['a', 121, 120], ['a', NaN, 120], ['a', 0, Infinity]]) {
    assert.equal(validateComment(body, at, duration), false);
  }
});

test('local comments persist edits/deletion and reject corrupt storage', async () => {
  let value = null;
  globalThis.localStorage = { getItem: () => value, setItem: (_key, next) => {value = next;} };
  assert.deepEqual(await trackComments.list(), []);
  const first = {id:'1', trackId:1, at:42, body:'이 부분 좋아요', author:'나', createdAt:1};
  const second = {...first, id:'2', trackId:2};
  await trackComments.save([first, second]);
  assert.deepEqual(await trackComments.list(), [first, second]);
  await trackComments.save([{...first, body:'수정'}]);
  assert.equal((await trackComments.list())[0].body, '수정');
  await trackComments.save([]);
  assert.deepEqual(await trackComments.list(), []);
  value = JSON.stringify([{...first, createdAt:1e20}]);
  assert.deepEqual(await trackComments.list(), []);
  value = '{';
  await assert.rejects(trackComments.list());
  delete globalThis.localStorage;
});
