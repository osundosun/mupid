/**
 * 곡 번호로 곡을 찾는다. 모르는 번호만 서버에 묶어서 묻는다.
 *
 * 화면은 좋아요·최근 감상·저장한 순간·마지막 재생 곡을 **곡 번호**로 들고 있다.
 * 예전에는 앱을 열 때 앞 100곡(카탈로그)을 받아 그 안에서 찾았는데, 그 밖의 곡은
 * 조용히 버려졌다(S15P21A204-441·443). 이제 카탈로그는 없고, 이미 받아 둔 곡
 * (`known`)에 없으면 곡 일괄 조회(`GET /api/v1/music/bulk`, S15P21A204-445)로 받는다.
 *
 * 가져오는 방법(`get`)을 주입받는다 — 서버 호출 없이 시험하려는 것이다.
 */

/**
 * 곡 여러 개를 받아 오는 방법. **없는 곡(지워진 곡 포함)은 결과에서 빠진다.**
 * 받지 못했으면 던진다.
 */
export type TracksGetter<T> = (musicIds: number[], signal?: AbortSignal) => Promise<T[]>;

/** 한 번에 묻는 곡 수. 서버 상한(`MusicBulkQuery.MAX_SIZE`)과 같다. */
export const BATCH_SIZE = 100;

export function createTrackResolver<T extends { musicId?: number }>(get: TracksGetter<T>) {
  /*
   * 곡마다 "받은 결과" 를 약속으로 들고 있는다. 좋아요와 플레이리스트가 같은 곡을
   * 나란히 물어도 요청은 한 번이다. 없는 곡은 `null` 로 남아 다시 묻지 않는다.
   * 요청이 실패한 곡은 지운다 — 다음에 다시 물어야 한다.
   */
  const cache = new Map<number, Promise<T | null>>();

  const request = (musicIds: number[], signal?: AbortSignal) => {
    const pending = get(musicIds, signal).then((tracks) => {
      const byId = new Map<number, T>();
      for (const track of tracks) {
        if (typeof track.musicId === 'number') byId.set(track.musicId, track);
      }
      return byId;
    });
    for (const id of musicIds) {
      cache.set(id, pending.then((byId) => byId.get(id) ?? null));
    }
    pending.catch(() => {
      for (const id of musicIds) cache.delete(id);
    });
  };

  /**
   * 곡 번호마다 곡을 찾는다. `known` 에 있으면 그것을, 없으면 받아 온다.
   *
   * 받지 못한 곡(없는 곡이거나 요청이 실패한 곡)은 결과에 없다. 한 묶음이 실패해도
   * 나머지 묶음은 돌려준다.
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
    const unasked = missing.filter((id) => !cache.has(id));
    for (let start = 0; start < unasked.length; start += BATCH_SIZE) {
      request(unasked.slice(start, start + BATCH_SIZE), signal);
    }
    const fetched = await Promise.all(
      missing.map((id) => (cache.get(id) ?? Promise.resolve(null)).catch(() => null)),
    );
    missing.forEach((id, index) => {
      const track = fetched[index];
      if (track) found.set(id, track);
    });
    const result = new Map<number, T>();
    for (const id of musicIds) {
      const track = found.get(id);
      if (track) result.set(id, track);
    }
    return result;
  }

  return { resolve };
}
