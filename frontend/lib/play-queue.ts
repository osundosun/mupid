/**
 * 재생 큐의 서버 연동. 저장소는 Redis 이고 규칙은 decision/0022 에 있다.
 *
 * 화면은 큐를 `Track[]` 로 다루고 서버는 곡 번호 목록으로 다룬다. 여기서만 옮긴다.
 * **화면 번호(`Track.id`)와 서버 번호(`Track.musicId`)는 다르다** — 카탈로그가
 * `ID_OFFSET` 을 더해 화면 번호를 만든다(`lib/stream-catalog.ts`). 서버로 나가는
 * 것은 언제나 `musicId` 다.
 *
 * 카탈로그에 없는 곡 번호는 버린다. 플레이리스트(`lib/playlists.ts`)와 같은 규칙이다 —
 * 화면에 그릴 수 없는 번호를 들고 있어 봐야 쓸 데가 없다.
 */

import { api } from './api/session';
import { ApiError } from './api/client';
import type { Track } from './mupid-data';

/** 화면이 들고 있는 큐 한 벌. */
export type QueueSnapshot = {
  /** 화면 번호(`Track.id`) 목록. 위가 앞이다. */
  ids: number[];
  /** 저장할 때 서버에 되돌려 줄 판본. */
  revision: number;
};

/** 서버에 보낼 수 있는 곡인가. 서버에 없는 곡은 `musicId` 가 없다. */
const isServerTrack = (track: Track): track is Track & { musicId: number } =>
  typeof track.musicId === 'number';

/** 순서를 지킨 채 서버 번호 → 화면 번호로 옮긴다. 카탈로그에 없는 곡은 빠진다. */
function toScreenIds(musicIds: number[], tracks: Track[]): number[] {
  const byMusicId = new Map<number, Track>();
  for (const track of tracks) {
    if (isServerTrack(track)) byMusicId.set(track.musicId, track);
  }
  return musicIds.flatMap((musicId) => {
    const track = byMusicId.get(musicId);
    return track ? [track.id] : [];
  });
}

/** 화면 번호 → 서버 번호. 서버에 없는 곡은 빠진다. */
export function toMusicIds(ids: number[], tracks: Track[]): number[] {
  const byId = new Map<number, Track>();
  for (const track of tracks) byId.set(track.id, track);
  return ids.flatMap((id) => {
    const track = byId.get(id);
    return track && isServerTrack(track) ? [track.musicId] : [];
  });
}

/** 서버 응답 한 벌을 화면이 쓰는 모양으로 바꾼다. */
const toSnapshot = (
  state: { revision: number; tracks: { music_id: number }[] },
  tracks: Track[],
): QueueSnapshot => ({
  ids: toScreenIds(
    state.tracks.map((it) => it.music_id),
    tracks,
  ),
  revision: state.revision,
});

/**
 * 계정에 저장된 큐를 가져온다.
 *
 * 실패하면 `null`. 화면을 막지 않는다 — 큐를 못 불러왔다고 음악을 못 듣게 할
 * 이유가 없다. 그때는 화면이 들고 있던 큐를 그대로 쓴다.
 */
export async function fetchQueue(
  tracks: Track[],
  signal?: AbortSignal,
): Promise<QueueSnapshot | null> {
  try {
    const state = await api.queue.get(signal);
    return state ? toSnapshot(state, tracks) : null;
  } catch (error) {
    if (signal?.aborted) return null;
    console.warn('[mupid] 재생 목록을 불러오지 못했어요.', error);
    return null;
  }
}

/**
 * 큐 전체를 저장한다. 순서 바꾸기·재생 위치 이동이 모두 이 경로다.
 *
 * 판본이 어긋나면(다른 기기가 먼저 고쳤다) 던지지 않고 **서버 큐를 돌려준다.**
 * 호출부는 그것으로 화면을 덮으면 된다 — 어느 쪽이 맞는지 화면이 판단할 일이 아니다.
 */
export async function saveQueue(
  ids: number[],
  currentId: number | null,
  revision: number,
  tracks: Track[],
): Promise<QueueSnapshot | null> {
  const musicIds = toMusicIds(ids, tracks);
  const currentMusicId = currentId === null ? null : (toMusicIds([currentId], tracks)[0] ?? null);

  try {
    const state = await api.queue.replace(musicIds, currentMusicId, revision);
    return state ? toSnapshot(state, tracks) : null;
  } catch (error) {
    if (error instanceof ApiError && error.code === 'MUPID_QUEUE_REVISION_CONFLICT') {
      // 서버가 이겼다. 지금 저장된 것을 그대로 가져와 화면을 맞춘다.
      return fetchQueue(tracks);
    }
    console.warn('[mupid] 재생 목록을 저장하지 못했어요.', error);
    return null;
  }
}

/** 곡 담기 결과. 자리와 퇴출은 서버가 정한 것을 그대로 따른다. */
export type QueueAddResult = {
  snapshot: QueueSnapshot;
  duplicated: boolean;
  /** 밀려난 곡의 **화면 번호**. 카탈로그에 없으면 null. */
  evictedId: number | null;
};

/**
 * 한 곡을 담는다.
 *
 * 자리(재생 중인 곡 바로 아래)와 100곡 퇴출은 서버가 정한다. 화면도 같은 규칙으로
 * 미리 반영하지만 기준은 서버다 — 두 곳이 어긋나면 서버 응답으로 덮는다.
 */
export async function addToQueue(
  track: Track,
  tracks: Track[],
): Promise<QueueAddResult | null> {
  if (!isServerTrack(track)) return null;

  try {
    const result = await api.queue.addTrack(track.musicId);
    if (!result) return null;
    return {
      snapshot: toSnapshot(result.queue, tracks),
      duplicated: result.duplicated,
      evictedId:
        result.evicted_music_id === null
          ? null
          : (toScreenIds([result.evicted_music_id], tracks)[0] ?? null),
    };
  } catch (error) {
    console.warn('[mupid] 재생 목록에 담지 못했어요.', error);
    return null;
  }
}

/**
 * 한 곡을 뺀다.
 *
 * 담겨 있지 않으면 서버가 404 다. 그것도 `null` 로 삼킨다 — 화면에서 이미 사라진
 * 곡을 또 빼려 한 것이라 사용자에게 알릴 일이 아니다.
 */
export async function removeFromQueue(
  track: Track,
  tracks: Track[],
): Promise<QueueSnapshot | null> {
  if (!isServerTrack(track)) return null;

  try {
    const state = await api.queue.removeTrack(track.musicId);
    return state ? toSnapshot(state, tracks) : null;
  } catch (error) {
    console.warn('[mupid] 재생 목록에서 빼지 못했어요.', error);
    return null;
  }
}

/**
 * 플레이리스트 수록곡으로 큐를 통째로 바꾼다.
 *
 * 곡 목록을 화면이 만들어 보내지 않고 **플레이리스트 번호만 넘긴다.** 수록곡은
 * 서버가 이미 알고 있고, 화면이 들고 있는 사본이 낡았을 수 있기 때문이다.
 */
export async function playPlaylistOnServer(
  playlistId: number,
  tracks: Track[],
): Promise<QueueSnapshot | null> {
  try {
    const state = await api.queue.fromPlaylist(playlistId);
    return state ? toSnapshot(state, tracks) : null;
  } catch (error) {
    console.warn('[mupid] 재생 목록을 플레이리스트로 바꾸지 못했어요.', error);
    return null;
  }
}
