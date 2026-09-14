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
