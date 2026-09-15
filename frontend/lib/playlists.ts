/**
 * 플레이리스트의 서버 연동.
 *
 * 화면은 플레이리스트를 `Playlist { id, name, tracks }` 로 다루고 서버는
 * `playlist_id` · `title` · 수록곡 목록으로 다룬다. 여기서만 옮긴다.
 *
 * **`Playlist.id` 는 서버의 `playlist_id` 다.** 예전에는 `Date.now()` 로 만든
 * 화면 전용 번호였는데, 그 값(≈1.7조)이 청취 이벤트의 `playlist_id`(UInt32)에
 * 들어가지 않아 그 이벤트가 실린 배치가 통째로 거부됐다. 서버 번호를 쓰면서
 * `PLAYLIST_ADD` · `PLAYLIST_REMOVE` 가 "어느 목록인지" 까지 싣게 됐다.
 *
 * 곡은 지금 불러온 카탈로그에서 찾아 붙인다. 카탈로그에 없는 곡은 버린다 —
 * 화면에 그릴 수 없는 번호를 들고 있어 봐야 쓸 데가 없다.
 */

import { api } from './api/session';
import type { Playlist, Track } from './mupid-data';

/** 서버에 보낼 수 있는 곡인가. 서버에 없는 곡은 `musicId` 가 없다. */
export const isServerTrack = (track: Track): track is Track & { musicId: number } =>
  typeof track.musicId === 'number';

/**
 * 계정에 저장된 플레이리스트를 화면이 쓰는 모양으로 바꿔 온다.
 *
 * 목록 API 는 수록곡을 주지 않으므로(곡까지 붙이면 플레이리스트 수만큼 조회가
 * 는다) 목록을 받은 뒤 상세를 나란히 부른다. 플레이리스트 개수만큼 요청이
 * 나가지만 앱을 열 때 한 번뿐이다.
 *
 * 실패하면 `null`. 화면을 막지 않는다 — 플레이리스트를 못 불러왔다고 음악을
 * 못 듣게 할 이유가 없다.
 */
export async function fetchPlaylists(
  tracks: Track[],
  signal?: AbortSignal,
): Promise<Playlist[] | null> {
  try {
    const summaries = await api.playlist.list(signal);
    if (!summaries.length) return [];

    const byMusicId = new Map<number, Track>();
    for (const track of tracks) {
      if (isServerTrack(track)) byMusicId.set(track.musicId, track);
    }

    const details = await Promise.all(
      summaries.map((summary) =>
        api.playlist
          .detail(summary.playlist_id, signal)
          // 하나가 실패해도 나머지는 보여 준다. 그 목록만 곡 없이 나온다.
          .catch(() => null),
      ),
    );

    return summaries.map((summary, index) => ({
      id: summary.playlist_id,
      name: summary.title,
      tracks: (details[index]?.tracks ?? []).flatMap((item) => {
        const track = byMusicId.get(item.music_id);
        return track ? [track] : [];
      }),
    }));
  } catch (error) {
    if (signal?.aborted) return null;
    console.warn('[mupid] 플레이리스트를 불러오지 못했어요.', error);
    return null;
  }
}

/**
 * 플레이리스트를 만든다. **서버가 정한 번호를 돌려준다** — 화면은 이 번호를
 * 그대로 `Playlist.id` 로 쓴다.
 */
export async function createPlaylist(title: string): Promise<number> {
  const created = await api.playlist.create(title.trim());
  return created.playlist_id;
}

/** 제목을 바꾼다. */
export async function renamePlaylist(playlistId: number, title: string): Promise<void> {
  await api.playlist.rename(playlistId, title.trim());
}

/** 플레이리스트를 지운다. 서버는 소프트 삭제라 되살릴 수 있다. */
export async function removePlaylist(playlistId: number): Promise<void> {
  await api.playlist.remove(playlistId);
}

/**
 * 곡을 담는다. 이미 담긴 곡이어도 성공이다(서버가 `duplicated` 로 알려 준다).
 * 서버에 없는 곡이면 아무것도 하지 않고 `false` 를 돌려준다.
 */
export async function addTrack(playlistId: number, track: Track): Promise<boolean> {
  if (!isServerTrack(track)) return false;
  await api.playlist.addTrack(playlistId, track.musicId);
  return true;
}

/**
 * 곡을 뺀다. 여러 곡을 한 번에 골랐어도 서버는 한 곡씩 받으므로 나눠 보낸다.
 *
 * 하나라도 실패하면 던진다 — 화면이 되돌려야 한다. 이미 빠져 있던 곡(404)도
 * 실패로 본다. 화면과 서버가 어긋났다는 뜻이라 다시 불러오는 편이 낫다.
 */
export async function removeTracks(playlistId: number, tracks: Track[]): Promise<void> {
  const removable = tracks.filter(isServerTrack);
  if (!removable.length) return;
  await Promise.all(removable.map((track) => api.playlist.removeTrack(playlistId, track.musicId)));
}

/**
 * 브라우저에만 있던 플레이리스트를 계정으로 옮긴다.
 *
 * 서버 연동 전에는 플레이리스트가 `localStorage` 에만 남았다. 그대로 두면 계정에
 * 플레이리스트가 하나도 없는 상태로 덮여 **사용자가 만들어 둔 목록이 사라진다.**
 * 계정이 비어 있을 때 한 번만 올린다 — 이미 하나라도 있으면 서버가 기준이다.
 *
 * 올린 뒤의 모양을 그대로 돌려준다. 번호가 서버 것으로 바뀌기 때문이다.
 */
export async function migrateLocalPlaylists(local: Playlist[]): Promise<Playlist[]> {
  const migrated: Playlist[] = [];
  for (const playlist of local) {
    const id = await createPlaylist(playlist.name);
    // 순서를 지키려고 한 곡씩 차례로 담는다. 트랙 순서는 담은 순서다.
    const saved: Track[] = [];
    for (const track of playlist.tracks) {
      if (await addTrack(id, track)) saved.push(track);
    }
    migrated.push({ id, name: playlist.name, tracks: saved });
  }
  return migrated;
}
