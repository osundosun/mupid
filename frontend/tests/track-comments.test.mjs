import test from 'node:test';
import assert from 'node:assert/strict';
import {
  COMMENT_MAX_LENGTH,
  captureCommentTime,
  commentUserId,
  commentable,
  ownsComment,
  validateComment,
} from '../lib/comment-rules.ts';

// 소유권은 서버가 준 `mine` 이 정한다. 닉네임이 같다고 같은 사람이 아니고,
// 체험용 계정의 번호를 서버 회원 번호와 섞지도 않는다.
test('댓글 소유권은 서버 판정과 인증된 회원 ID로만 판단한다', () => {
  for (const session of [null, {kind:'demo', userId:1}, {kind:'authenticated'}, {kind:'authenticated', userId:0}]) assert.equal(commentUserId(session), null);
  assert.equal(commentUserId({kind:'authenticated', userId:1}), 1);
  assert.equal(ownsComment({mine:true}, 1), true);
  assert.equal(ownsComment({mine:false}, 1), false);
  assert.equal(ownsComment({mine:true}, null), false);
  assert.equal(ownsComment({authorId:1}, 1), false);
  assert.equal(ownsComment({author:'동일 닉네임'}, 1), false);
});

test('댓글 시점은 화면과 같은 초로 고정하고 등록 후 새로 캡처한다', () => {
  let position = 16.9999;
  const draftAt = captureCommentTime(position, 180);
  position = 24.7;
  assert.equal(draftAt, 16);
  assert.equal(captureCommentTime(position, 180), 24);
  assert.equal(captureCommentTime(59.9999, 180), 59);
  assert.equal(captureCommentTime(60, 180), 60);
  assert.equal(captureCommentTime(-1, 180), 0);
  assert.equal(captureCommentTime(190, 180), 180);
  assert.equal(captureCommentTime(NaN, 180), 0);
});

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
