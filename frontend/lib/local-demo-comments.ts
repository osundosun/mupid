/**
 * 로컬 데모 곡의 시점 댓글.
 *
 * 서버에 없는 곡이라(`musicId` 없음) API 로 보낼 데가 없다. 그래서 이 브라우저의
 * `localStorage` 에만 남는다 — 다른 사람에게 보이지 않고 기기 간에도 따라가지
 * 않는다. 화면 흐름(작성·수정·삭제·하트 배치)을 서버 없이 확인하려는 용도다.
 *
 * 저장소가 막혀 있거나(사생활 보호 모드) 내용이 깨져 있어도 화면은 떠야 한다.
 * 읽기는 빈 목록으로, 쓰기는 조용히 넘어간다.
 */

import type { Track } from './mupid-data';
import type { TrackComment } from './track-comments';

const KEY = 'mupid.local-demo.comments.v1';
/** 서버 댓글 번호와 섞이지 않게 접두사를 붙인다. `remove(id)` 가 이걸로 갈린다. */
const PREFIX = 'local:';

export const isLocalDemoCommentId = (id: string) => id.startsWith(PREFIX);

type Stored = { id: string; trackId: number; at: number; body: string; createdAt: number };

const read = (): Stored[] => {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(KEY) || '[]');
    if (!Array.isArray(value)) return [];
    return value.filter((v: unknown): v is Stored =>
      !!v && typeof v === 'object' &&
      typeof (v as Stored).id === 'string' &&
      typeof (v as Stored).trackId === 'number' &&
      typeof (v as Stored).at === 'number' && Number.isFinite((v as Stored).at) &&
      typeof (v as Stored).body === 'string' &&
      typeof (v as Stored).createdAt === 'number');
  } catch { return []; }
};

const write = (items: Stored[]) => {
  try { localStorage.setItem(KEY, JSON.stringify(items)); } catch { /* 저장이 막혀도 화면은 이어 간다. */ }
};

// 데모 계정이 쓴 것으로만 둔다. 남의 댓글을 흉내 내면 수정·삭제 권한 화면을
// 실제와 다르게 보게 된다.
const toComment = (stored: Stored): TrackComment => ({
  id: stored.id,
  trackId: stored.trackId,
  at: stored.at,
  body: stored.body,
  author: '데모',
  mine: true,
  createdAt: stored.createdAt,
});

export const localDemoComments = {
  list(track: Track): TrackComment[] {
    return read()
      .filter(v => v.trackId === track.id)
      .sort((a, b) => a.at - b.at || a.createdAt - b.createdAt)
      .map(toComment);
  },
  add(track: Track, at: number, body: string): TrackComment {
    const stored: Stored = {
      id: PREFIX + Date.now().toString(36) + Math.random().toString(36).slice(2, 7),
      trackId: track.id,
      at,
      body: body.trim(),
      createdAt: Date.now(),
    };
    write([...read(), stored]);
    return toComment(stored);
  },
  update(id: string, body: string): TrackComment {
    const items = read();
    const found = items.find(v => v.id === id);
    if (!found) throw new Error('댓글을 찾지 못했어요.');
    found.body = body.trim();
    write(items);
    return toComment(found);
  },
  remove(id: string): void {
    write(read().filter(v => v.id !== id));
  },
};
