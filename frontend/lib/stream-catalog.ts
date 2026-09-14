/**
 * 서버 곡 카탈로그.
 *
 * `GET /api/v1/music` 로 받아 화면이 쓰는 `Track` 으로 옮긴다. 화면에 보이는
 * 곡은 **이것이 전부다** — 데모 곡은 없앴다. 없는 곡을 틀려다 실패하고,
 * 좋아요·기록도 서버에 남길 수 없는 번호를 갖게 되기 때문이다.
 *
 * 재생은 청크 스트리밍 한 경로뿐이다 (`lib/player/`).
 */

import { api } from './api/session';
import type { MusicSummary } from './api/endpoints';
import type { Track } from './mupid-data';
import { toImageSource, withCache } from './platform';

/**
 * 화면용 곡 번호는 서버 번호에 이 값을 더해 만든다.
 *
 * 저장해 둔 예전 데이터(데모 곡 번호 1~8)와 섞이지 않게 하려는 것이다.
 * 서버로 보낼 때는 언제나 `musicId` 를 쓴다.
 */
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
    // 앱에서는 앨범 아트도 Rust 가 받아 온다 (`lib/platform/image.ts`).
    image: toImageSource(music.album.image_url) || '/sky-background.webp',
    tags: tags.length ? tags : ['스트리밍'],
    // 구간은 `GET /api/v1/music/{id}/analysis` 가 준다. 다만 분석 파이프라인이
    // 아직 돌지 않아 대부분 비어 있어서, 여기서는 곡을 둘로 나눠 둔다.
    // 재생 대상이 되면 `use-mupid` 가 분석 결과로 갈아 끼운다.
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
 * **못 받아 오면 예외를 던진다.** 예전에는 빈 배열로 삼켰는데, 데모 곡 위에
 * 얹는 값이라 앱이 열리기만 하면 됐기 때문이다. 이제는 이것이 곡 목록의
 * 전부라, 삼키면 화면이 서버 장애를 "아직 등록된 음악이 없어요" 로 잘못
 * 알린다 — 사용자는 다시 불러올 일이 아니라고 여기고 떠난다. 둘은 다른
 * 사정이니 다르게 알려야 한다.
 *
 * 중간에 취소된 것은 실패가 아니다. 빈 배열로 조용히 끝낸다.
 *
 * 앱에서는 마지막으로 받은 목록을 SQLite 에 남긴다. 서버가 응답하지 않으면 그
 * 값으로 화면을 연다 — 지하철에서 앱을 열었을 때 목록이 통째로 비는 것보다
 * 낫다. **캐시를 먼저 보지는 않는다.** 그러면 새로 올라온 곡이 하루 동안
 * 보이지 않는다. 서버 응답을 저장하는 것은 DTO 그대로다 — 화면용 변환은
 * `toTrack` 한 곳에서만 일어나야 한다.
 */
export async function fetchCatalog(signal?: AbortSignal): Promise<Track[]> {
  try {
    const items = await withCache('catalog', `size:${CATALOG_SIZE}`, async () => {
      const page = await api.music.search({ size: CATALOG_SIZE }, signal);
      return page.items;
    });
    return items.map(toTrack);
  } catch (error) {
    if (signal?.aborted) return [];
    console.warn('[mupid] 곡 카탈로그를 불러오지 못했어요.', error);
    throw error;
  }
}

/** 이 곡을 청크 스트리밍으로 재생해야 하는가. */
export const isStreamed = (
  track: Pick<Track, 'musicId'> | null | undefined,
): track is Track & { musicId: number } => typeof track?.musicId === 'number';
