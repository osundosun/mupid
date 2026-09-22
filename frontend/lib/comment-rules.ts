/**
 * 곡 시점 댓글의 제약.
 *
 * 서버와 같은 값을 화면에서도 막는다. 통신 없이 판정할 수 있는 것만 여기 둔다 —
 * `lib/track-comments.ts` 는 API 를 부르므로 이 규칙만 따로 쓰고 싶은 곳
 * (입력 검증, 테스트)이 그쪽을 끌어오지 않게 하려는 것이다.
 */

import type { Track } from './mupid-data';

/** `music_comments.content` 가 varchar(100) 이다. 서버도 같은 값으로 막는다. */
export const COMMENT_MAX_LENGTH = 100;

/** 화면에 표시한 초와 저장하는 시점을 일치시킨다. */
export function captureCommentTime(position: number, duration: number): number {
  if (!Number.isFinite(position) || !Number.isFinite(duration)) return 0;
  return Math.floor(Math.min(Math.max(0, duration), Math.max(0, position)));
}

export function validateComment(body: string, at: number, duration: number) {
  const trimmed = body.trim();
  return (
    !!trimmed &&
    trimmed.length <= COMMENT_MAX_LENGTH &&
    Number.isFinite(duration) &&
    Number.isFinite(at) &&
    at >= 0 &&
    at <= duration
  );
}

/** 이 곡에 댓글을 달 수 있는가. 데모 곡은 서버에 없어 붙일 데가 없다. */
export const commentable = (
  track: Pick<Track, 'musicId'> | null | undefined,
): track is Track & { musicId: number } => typeof track?.musicId === 'number';

/**
 * 지금 로그인한 사람의 번호.
 *
 * 화면이 "로그인했는가" 를 묻는 데 쓴다. 닉네임이 같다고 같은 사람으로 보면
 * 안 되고, 체험용 계정의 번호를 서버 회원 번호와 섞어서도 안 된다.
 */
export function commentUserId(
  session: { kind: string; userId?: number } | null | undefined,
): number | null {
  return session?.kind === 'authenticated'
    && Number.isSafeInteger(session.userId)
    && (session.userId as number) > 0
    ? (session.userId as number)
    : null;
}

/**
 * 이 댓글을 고치고 지울 수 있는가.
 *
 * **서버가 판정한 `mine` 만 본다.** 화면이 `user_id` 를 비교하게 두면 비교를
 * 빠뜨렸을 때 남의 댓글에 수정 버튼이 뜬다. `userId` 는 비로그인 상태에서
 * 버튼이 뜨지 않게 하는 안전장치로만 쓴다.
 */
export function ownsComment(comment: { mine?: boolean }, userId: number | null) {
  return userId !== null && comment.mine === true;
}
