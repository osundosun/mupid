/**
 * 서버에서 곡을 받아 오는 두 갈래 (S15P21A204-446).
 *
 * 예전에는 앱을 열 때 앞 100곡(카탈로그)을 받아 **곡 번호 → 곡 사전**과 **대체 목록**을
 * 한 벌로 썼다. 사전이 100곡에 갇혀 그 밖의 곡이 좋아요·큐·보관함에서 사라지는
 * 버그가 되풀이됐다(S15P21A204-441·443). 그래서 둘을 떼었다.
 *
 * - **곡 찾기** (`trackResolver`·`resolveTrackIds`) — 곡 번호로 곡을 찾는다. 이미 받아
 *   둔 곡에 없으면 곡 일괄 조회로 묻는다. 목록에 없는 곡이라고 버리지 않는다.
 * - **인기곡** (`fetchPopularTracks`) — 추천이 아직 없을 때 홈·비슷한 순간 화면을 채우고,
 *   처음 쓰는 사람의 재생 목록을 채운다. **곡을 찾거나 저장된 값을 거르는 데 쓰지
 *   않는다.** 그러면 카탈로그가 되살아난다.
 *
 * 규칙은 `track-lookup.ts` 에 있고, 여기서는 서버 호출과 곡 변환만 붙인다.
 * `stream-catalog.ts` 에 두지 않은 것은 그 모듈만 떼어 검사하는 시험들이 import 를
 * 하나하나 바꿔 끼우기 때문이다.
 */

import { api } from './api/session';
import { localDemoEnabled } from './api/local-demo';
import type { Track } from './mupid-data';
import { musicIdOfTrackId, toTrack } from './stream-catalog';
import { createTrackResolver } from './track-lookup';

export const trackResolver = createTrackResolver<Track>(async (musicIds, signal) => {
  if (localDemoEnabled()) return [];
  const result = await api.music.bulk(musicIds, signal);
  return (result?.items ?? []).map(toTrack);
});

/** 화면 번호로 곡을 찾는다. `known` 에 없는 서버 곡만 받아 온다. */
export async function resolveTrackIds(
  trackIds: number[],
  known: Track[],
  signal?: AbortSignal,
): Promise<Track[]> {
  const musicIds = trackIds.flatMap((id) => {
    const musicId = musicIdOfTrackId(id);
    return musicId === undefined ? [] : [musicId];
  });
  return [...(await trackResolver.resolve(musicIds, known, signal)).values()];
}

/** 인기곡 수. 처음 쓰는 사람의 재생 목록이 이만큼 찬다(큐 상한과 같다). */
export const POPULAR_SIZE = 100;

/**
 * 인기곡. 곡 목록 API 의 기본 정렬이 재생수 순이다(S15P21A204-396).
 *
 * 로컬 데모에는 서버가 없어서 데모 곡 파일을 그대로 쓴다.
 * 못 받으면 던진다 — 부르는 쪽이 빈 목록으로 물러난다.
 */
export async function fetchPopularTracks(signal?: AbortSignal): Promise<Track[]> {
  if (localDemoEnabled()) {
    const response = await fetch('/local-demo/catalog.json', { signal });
    if (!response.ok) throw new Error('로컬 음악 데이터를 찾지 못했어요.');
    return response.json();
  }
  const page = await api.music.search({ size: POPULAR_SIZE }, signal);
  return page.items.map(toTrack);
}
