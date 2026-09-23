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
import { musicIdOfTrackId } from './stream-catalog';
import { trackResolver } from './catalog-lookup';

/** 화면이 "최근 감상" 에 보여 주는 곡 수. 홈은 4개, 프로필은 더 본다. */
const HISTORY_SIZE = 20;

/**
 * 서버에 보낼 곡 번호. 데모 곡은 `musicId` 가 없다.
 *
 * 카탈로그에 없는 곡(검색·추천으로 만난 곡)도 화면 번호만으로 서버 번호를 안다.
 * 예전에는 카탈로그에서만 찾아서 그런 곡의 좋아요가 서버에 남지 않았다.
 */
const musicIdOf = (tracks: Track[], trackId: number): number | undefined =>
  tracks.find((t) => t.id === trackId)?.musicId ?? musicIdOfTrackId(trackId);

/** 서버 번호 목록을 받은 순서대로 곡으로 옮긴다. 찾지 못한 곡은 버린다. */
const toTracks = (musicIds: number[], found: Map<number, Track>): Track[] =>
  musicIds.flatMap((id) => {
    const track = found.get(id);
    return track ? [track] : [];
  });

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
 * 계정에 저장된 최근 감상을 곡으로 바꿔 온다.
 *
 * 실패하면 `null`. 편의 기능이라 화면을 막지 않는다.
 */
export async function fetchHistory(
  tracks: Track[],
  signal?: AbortSignal,
): Promise<Track[] | null> {
  try {
    const page = await api.history.list({ size: HISTORY_SIZE }, signal);
    const musicIds = (page?.items ?? []).map((item) => item.music_id);
    return toTracks(musicIds, await trackResolver.resolve(musicIds, tracks, signal));
  } catch (error) {
    if (signal?.aborted) return null;
    console.warn('[mupid] 최근 감상을 불러오지 못했어요.', error);
    return null;
  }
}

/**
 * 계정에 저장된 반응을 곡으로 바꿔 온다.
 *
 * 카탈로그에 없는 곡은 곡 번호로 받아 온다. 예전에는 버려서, 검색으로 찾아 좋아한
 * 곡이 새로고침하면 보관함에서 사라졌다 (S15P21A204-443). 지워진 곡만 빠진다.
 */
export async function fetchReactions(
  tracks: Track[],
  signal?: AbortSignal,
): Promise<{ liked: Track[]; ignored: Track[] } | null> {
  try {
    const reactions = await api.reaction.list(signal);
    const like = reactions?.like ?? [];
    const dislike = reactions?.dislike ?? [];
    const found = await trackResolver.resolve([...like, ...dislike], tracks, signal);
    return { liked: toTracks(like, found), ignored: toTracks(dislike, found) };
  } catch (error) {
    if (signal?.aborted) return null;
    console.warn('[mupid] 좋아요 목록을 불러오지 못했어요.', error);
    return null;
  }
}
