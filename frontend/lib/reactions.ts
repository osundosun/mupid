/**
 * 좋아요·관심없음의 서버 연동.
 *
 * 화면은 곡을 `Track.id` 로 다루고 서버는 `music_id` 로 다룬다. 데모 곡(1~8)은
 * 서버에 없어서 `musicId` 가 없고, 그런 곡의 반응은 브라우저에만 남는다.
 *
 * 반응은 **한 곡에 하나**다. 좋아요를 누르면 관심없음이 풀리고, 그 반대도 같다.
 * 서버가 그렇게 동작하므로 화면 상태도 같이 맞춰야 한다.
 */

import { api } from './api/session';
import type { ReactionType } from './api/endpoints';
import type { Track } from './mupid-data';

/** 서버에 보낼 수 있는 곡인가. 데모 곡은 `musicId` 가 없다. */
const musicIdOf = (tracks: Track[], trackId: number): number | undefined =>
  tracks.find((t) => t.id === trackId)?.musicId;

/**
 * 반응을 서버에 반영한다.
 *
 * 서버에 없는 곡이거나 비로그인이면 아무것도 하지 않고 `false` 를 돌려준다.
 * 실패하면 던진다 — 화면이 눌린 상태를 되돌려야 한다.
 */
export async function pushReaction(
  tracks: Track[],
  trackId: number,
  type: ReactionType | null,
): Promise<boolean> {
  const musicId = musicIdOf(tracks, trackId);
  if (typeof musicId !== 'number') return false;
  if (type === null) await api.reaction.clear(musicId);
  else await api.reaction.set(musicId, type);
  return true;
}

/**
 * 계정에 저장된 반응을 화면이 쓰는 `Track.id` 로 바꿔 온다.
 *
 * 지금 불러온 카탈로그에 없는 곡은 버린다 — 화면에 그릴 수 없는 번호를 들고
 * 있어 봐야 쓸 데가 없다.
 */
export async function fetchReactions(
  tracks: Track[],
  signal?: AbortSignal,
): Promise<{ liked: number[]; ignored: number[] } | null> {
  try {
    const reactions = await api.reaction.list(signal);
    const byMusicId = new Map<number, number>();
    for (const track of tracks) {
      if (typeof track.musicId === 'number') byMusicId.set(track.musicId, track.id);
    }
    const toTrackIds = (ids: number[]) =>
      ids.map((id) => byMusicId.get(id)).filter((id): id is number => typeof id === 'number');
    return {
      liked: toTrackIds(reactions?.like ?? []),
      ignored: toTrackIds(reactions?.dislike ?? []),
    };
  } catch (error) {
    if (signal?.aborted) return null;
    console.warn('[mupid] 좋아요 목록을 불러오지 못했어요.', error);
    return null;
  }
}
