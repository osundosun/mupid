/**
 * 서버 곡 카탈로그.
 *
 * `GET /api/v1/music` 로 받아 화면이 쓰는 `Track` 으로 옮긴다.
 * `musicId` 가 있는 곡은 `<audio>` 데모 음원이 아니라 청크 스트리밍으로
 * 재생된다 (`lib/player/`).
 *
 * 데모 곡 8개는 아직 함께 보여준다. 서버 카탈로그에 시연용 곡 몇 개뿐이라
 * 화면이 비어 보이기 때문이다. 카탈로그가 채워지면 `lib/mupid-data.ts` 의
 * 데모 목록을 지우면 된다.
 */

import { api } from './api/session';
import type { MusicSummary } from './api/endpoints';
import type { Track } from './mupid-data';

/** 데모 곡(1~8)과 겹치지 않는 번호대. */
const ID_OFFSET = 1000;

/** 한 번에 받아 올 곡 수. 카탈로그가 커지면 화면별 페이징으로 바꾼다. */
const CATALOG_SIZE = 100;

const toTrack = (music: MusicSummary): Track => {
  const duration = (music.duration_ms ?? 0) / 1000;
  const tags = [...music.genres, ...music.moods];
  return {
    id: ID_OFFSET + music.music_id,
    musicId: music.music_id,
    title: music.title,
    artist: music.artist.name,
    album: music.album.title,
    genre: music.genres[0] ?? '',
    mood: music.moods[0] ?? '',
    // 구간 UI 가 duration 안쪽 값을 기대한다. 곡 길이에 맞춰 잡는다.
    moment: Math.max(0, duration * 0.3),
    duration,
    // 추천 점수·추천 이유는 아직 서버가 주지 않는다 (api-todo P3).
    match: 100,
    reason: '',
    image: music.album.image_url ?? '/sky-background.webp',
    // 스트리밍 곡은 이 URL 을 쓰지 않는다. <audio> 경로로 새지 않도록 비워 둔다.
    audio: '',
    tags: tags.length ? tags : ['스트리밍'],
    // 구간 분석은 아직 API 가 없다 (api-todo P2-11). 임시로 곡을 둘로 나눈다.
    sections: duration
      ? [
          { label: '도입', start: 0, end: duration * 0.4 },
          { label: '전개', start: duration * 0.4, end: duration },
        ]
      : [],
  };
};

/**
 * 서버 곡 목록을 가져온다.
 *
 * 실패해도 예외를 던지지 않는다. 카탈로그는 데모 곡 위에 얹는 값이라,
 * 못 받아 왔다고 앱이 열리지 않으면 안 된다. 대신 콘솔에 남긴다.
 */
export async function fetchCatalog(signal?: AbortSignal): Promise<Track[]> {
  try {
    const page = await api.music.search({ size: CATALOG_SIZE }, signal);
    return page.items.map(toTrack);
  } catch (error) {
    if (signal?.aborted) return [];
    console.warn('[mupid] 곡 카탈로그를 불러오지 못했어요.', error);
    return [];
  }
}

/** 이 곡을 청크 스트리밍으로 재생해야 하는가. */
export const isStreamed = (
  track: Pick<Track, 'musicId'> | null | undefined,
): track is Track & { musicId: number } => typeof track?.musicId === 'number';
