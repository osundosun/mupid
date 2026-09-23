/**
 * 카탈로그에 없는 곡을 곡 번호로 채워 온다 (S15P21A204-443).
 *
 * 앱이 처음 받는 카탈로그는 앞 100곡뿐이다(`lib/stream-catalog.ts`). 그런데 좋아요·
 * 플레이리스트·저장한 순간에는 검색이나 추천으로 만난 **그 밖의 곡**도 담긴다.
 * 예전에는 새로고침 때 카탈로그에서만 곡을 찾아 붙여서, 못 찾은 곡은 보관함에서
 * 조용히 사라졌다. 저장한 순간은 그 빈 목록이 다시 저장되며 기기에서도 지워졌다.
 *
 * 그래서 되살릴 때 카탈로그에 없는 번호만 골라 곡 하나씩 받아 온다. 이 파일은
 * 가져오는 방법(`get`)을 주입받는다 — 서버 호출 없이 시험하려는 것이다.
 */

/** 곡 하나를 받아 오는 방법. 없는 곡이면 `null`, 받지 못했으면 던진다. */
export type TrackGetter<T> = (musicId: number, signal?: AbortSignal) => Promise<T | null>;

/** 한 번에 띄우는 요청 수. 좋아요가 수백 곡이어도 서버를 한꺼번에 두드리지 않는다. */
const CONCURRENCY = 6;

export function createTrackResolver<T extends { musicId?: number }>(get: TrackGetter<T>) {
  /*
   * 받아 온 곡과 받는 중인 곡. 좋아요와 플레이리스트가 같은 곡을 나란히 물어도
   * 요청은 한 번이다. 실패한 것은 남기지 않는다 — 다음에 다시 물어야 한다.
   */
  const cache = new Map<number, Promise<T | null>>();
  const fetchOne = (musicId: number, signal?: AbortSignal) => {
    const found = cache.get(musicId);
    if (found) return found;
    const pending = get(musicId, signal).catch((error: unknown) => {
      cache.delete(musicId);
      throw error;
    });
    cache.set(musicId, pending);
    return pending;
  };

  /**
   * 곡 번호마다 곡을 찾는다. `known` 에 있으면 그것을, 없으면 받아 온다.
   *
   * 받지 못한 곡(삭제됐거나 요청이 실패한 곡)은 결과에 없다. 한 곡 때문에 나머지를
   * 버리지 않는다.
   */
  async function resolve(
    musicIds: number[],
    known: T[],
    signal?: AbortSignal,
  ): Promise<Map<number, T>> {
    const found = new Map<number, T>();
    for (const track of known) {
      if (typeof track.musicId === 'number') found.set(track.musicId, track);
    }
    const missing = [...new Set(musicIds)].filter((id) => !found.has(id));
    const result = new Map<number, T>();
    for (const id of musicIds) {
      const track = found.get(id);
      if (track) result.set(id, track);
    }
    let next = 0;
    const worker = async () => {
      while (next < missing.length && !signal?.aborted) {
        const id = missing[next++];
        try {
          const track = await fetchOne(id, signal);
          if (track) result.set(id, track);
        } catch {
          // 이 곡만 빠진다.
        }
      }
    };
    await Promise.all(Array.from({ length: Math.min(CONCURRENCY, missing.length) }, worker));
    return result;
  }

  return { resolve };
}
