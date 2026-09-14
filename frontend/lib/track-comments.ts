/**
 * 곡 시점 댓글.
 *
 * 서버에 저장된다. 로그인해야 읽고 쓸 수 있고, 다른 사람의 댓글도 보인다.
 * 데모 곡(`musicId` 없음)에는 댓글을 달 수 없다 — 서버에 그 곡이 없다.
 *
 * 화면이 쓰는 모양(`TrackComment`)과 서버 모양은 다르다. 여기서만 옮긴다.
 * 댓글 번호는 서버에서 숫자로 오지만 화면은 문자열로 다루므로 여기서 맞춘다.
 */

import { api } from './api/session';
import type { MusicComment } from './api/endpoints';
import { commentable } from './comment-rules';
import type { Track } from './mupid-data';

export type TrackComment = {
  /** 서버 댓글 번호를 문자열로 옮긴 값. */
  id: string;
  trackId: number;
  /** 곡 내 시점(초). 서버는 ms 로 다룬다. */
  at: number;
  body: string;
  author: string;
  authorId?: number;
  /** 내 댓글인가. **서버가 판정한 값이다.** */
  mine: boolean;
  createdAt: number;
};

// 통신 없이 쓸 수 있는 규칙은 `comment-rules` 에 있다. 화면이 한곳에서
// 가져다 쓰도록 여기서 다시 내보낸다.
export {
  COMMENT_MAX_LENGTH,
  commentable,
  commentUserId,
  ownsComment,
  validateComment,
} from './comment-rules';

/** 한 번에 받아 올 댓글 수. 넘치면 페이징으로 바꾼다. */
const PAGE_SIZE = 100;

const toComment = (track: Track, comment: MusicComment): TrackComment => ({
  id: String(comment.comment_id),
  trackId: track.id,
  at: comment.timestamp_ms / 1000,
  body: comment.content,
  author: comment.nickname,
  authorId: comment.user_id,
  mine: comment.mine,
  createdAt: Date.parse(comment.created_at) || Date.now(),
});

export const trackComments = {
  async list(track: Track, signal?: AbortSignal): Promise<TrackComment[]> {
    if (!commentable(track)) return [];
    const page = await api.comment.list(track.musicId, { size: PAGE_SIZE }, signal);
    return (page?.items ?? []).map((item) => toComment(track, item));
  },
  async add(track: Track, at: number, body: string): Promise<TrackComment> {
    if (!commentable(track)) throw new Error('이 곡에는 댓글을 남길 수 없어요.');
    const saved = await api.comment.add(track.musicId, {
      timestamp_ms: Math.round(at * 1000),
      content: body.trim(),
    });
    return toComment(track, saved);
  },
  async update(track: Track, id: string, body: string): Promise<TrackComment> {
    const saved = await api.comment.update(Number(id), body.trim());
    return toComment(track, saved);
  },
  async remove(id: string): Promise<void> {
    await api.comment.remove(Number(id));
  },
};
