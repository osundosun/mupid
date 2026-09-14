/**
 * 곡 시점 댓글.
 *
 * 서버에 저장된다. 로그인해야 읽고 쓸 수 있고, 다른 사람의 댓글도 보인다.
 * 데모 곡(`musicId` 없음)에는 댓글을 달 수 없다 — 서버에 그 곡이 없다.
 *
 * 화면이 쓰는 모양(`TrackComment`)과 서버 모양은 다르다. 여기서만 옮긴다.
 */

import { api } from './api/session';
import type { MusicComment } from './api/endpoints';
import { commentable } from './comment-rules';
import type { Track } from './mupid-data';

export type TrackComment = {
  /** 서버 댓글 번호. */
  id: number;
  trackId: number;
  /** 곡 내 시점(초). 서버는 ms 로 다룬다. */
  at: number;
  body: string;
  author: string;
  /** 이 댓글이 내 것인가. 서버가 판정한 값이다. */
  mine: boolean;
  createdAt: number;
};

/** 한 번에 받아 올 댓글 수. 넘치면 페이징으로 바꾼다. */
const PAGE_SIZE = 100;

const toComment = (track: Track, comment: MusicComment): TrackComment => ({
  id: comment.comment_id,
  trackId: track.id,
  at: comment.timestamp_ms / 1000,
  body: comment.content,
  author: comment.nickname,
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
  async update(track: Track, id: number, body: string): Promise<TrackComment> {
    const saved = await api.comment.update(id, body.trim());
    return toComment(track, saved);
  },
  async remove(id: number): Promise<void> {
    await api.comment.remove(id);
  },
};
