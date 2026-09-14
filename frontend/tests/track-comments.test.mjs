import test from 'node:test';
import assert from 'node:assert/strict';
import { COMMENT_MAX_LENGTH, commentable, validateComment } from '../lib/comment-rules.ts';

test('comment text and song timestamp boundaries', () => {
  assert.equal(validateComment('좋아요', 0, 120), true);
  assert.equal(validateComment('끝부분', 120, 120), true);
  assert.equal(validateComment('a'.repeat(COMMENT_MAX_LENGTH), 0, 120), true);
  for (const [body, at, duration] of [
    [' ', 0, 120],
    ['a'.repeat(COMMENT_MAX_LENGTH + 1), 0, 120],
    ['a', -1, 120],
    ['a', 121, 120],
    ['a', NaN, 120],
    ['a', 0, Infinity],
  ]) {
    assert.equal(validateComment(body, at, duration), false);
  }
});

// 서버에 없는 곡(데모 곡)에는 댓글을 붙일 데가 없다.
test('only server songs take comments', () => {
  assert.equal(commentable({ musicId: 2 }), true);
  assert.equal(commentable({ musicId: 0 }), true);
  assert.equal(commentable({}), false);
  assert.equal(commentable(null), false);
  assert.equal(commentable(undefined), false);
});
