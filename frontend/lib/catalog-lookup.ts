/**
 * 카탈로그 밖의 곡을 서버에서 곡 번호로 받아 온다 (S15P21A204-443).
 *
 * 규칙은 `track-lookup.ts` 에 있고, 여기서는 서버 호출과 곡 변환만 붙인다.
 * `stream-catalog.ts` 에 두지 않은 것은 그 모듈만 떼어 검사하는 시험들이 import 를
 * 하나하나 바꿔 끼우기 때문이다.
 */

import { api } from './api/session';
import { ApiError } from './api/client';
import { localDemoEnabled } from './api/local-demo';
import type { Track } from './mupid-data';
import { musicIdOfTrackId, toTrack } from './stream-catalog';
import { createTrackResolver } from './track-lookup';

/** 지워진 곡(404)은 없는 곡으로 친다. 그 밖의 실패는 던져서 다음에 다시 묻게 한다. */
export const trackResolver = createTrackResolver<Track>(async (musicId, signal) => {
  if (localDemoEnabled()) return null;
  try {
    return toTrack(await api.music.get(musicId, signal));
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null;
    throw error;
  }
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
