/**
 * 서버 응답 캐시 (앱 전용).
 *
 * 지하철에서 앱을 열면 네트워크가 없거나 느리다. 마지막으로 받아 둔 곡 목록을
 * 보여 주는 편이 빈 화면보다 낫다.
 *
 * **캐시는 네트워크를 대신하지 않는다.** 언제나 서버를 먼저 부르고, 실패했을
 * 때만 묵은 값으로 내려앉는다. 반대로 하면 새로 올라온 곡이 하루 동안 보이지
 * 않는다.
 */

import { invoke, isTauri } from './bridge';

type CacheEntry<T> = { payload: T; fetchedAt: number; stale: boolean };

/** 곡 목록. 하루 지난 것도 쓴다 — 없는 것보다 낫다. */
export const CATALOG_MAX_AGE_MS = 24 * 60 * 60 * 1000;

async function read<T>(
  scope: string,
  key: string,
  maxAgeMs?: number,
): Promise<CacheEntry<T> | null> {
  try {
    return await invoke<CacheEntry<T> | null>('cache_get', {
      scope,
      key,
      maxAgeMs: maxAgeMs ?? null,
    });
  } catch (error) {
    console.warn('[mupid] 캐시를 읽지 못했어요.', error);
    return null;
  }
}

async function write(scope: string, key: string, payload: unknown): Promise<void> {
  try {
    await invoke('cache_put', { scope, key, payload });
  } catch (error) {
    // 캐시를 못 적는 것은 화면이 알 일이 아니다. 다음 실행에서 다시 받으면 된다.
    console.warn('[mupid] 캐시를 저장하지 못했어요.', error);
  }
}

export type CacheOptions<T> = {
  /** 이보다 묵은 값은 쓰지 않는다. */
  maxAgeMs?: number;
  /**
   * 이 응답을 남겨 둘 만한가. 기본은 전부 남긴다.
   *
   * "아직 분석하지 않았다" 같은 응답을 저장하면, 나중에 분석이 끝나도 앱은
   * 저장해 둔 빈 답을 계속 내놓는다.
   */
  cacheIf?: (value: T) => boolean;
};

/**
 * 서버를 먼저 부르고, 실패하면 저장해 둔 값을 돌려준다.
 *
 * 웹에서는 캐시가 없으므로 `load()` 를 그대로 통과시킨다 — 호출하는 쪽이
 * 플랫폼을 신경 쓰지 않게 하려는 것이다.
 */
export async function withCache<T>(
  scope: string,
  key: string,
  load: () => Promise<T>,
  options: CacheOptions<T> = {},
): Promise<T> {
  if (!isTauri()) return load();
  try {
    const fresh = await load();
    if (options.cacheIf?.(fresh) ?? true) void write(scope, key, fresh);
    return fresh;
  } catch (error) {
    const cached = await read<T>(scope, key, options.maxAgeMs ?? CATALOG_MAX_AGE_MS);
    if (!cached) throw error;
    console.warn('[mupid] 서버 응답이 없어 저장해 둔 값을 씁니다.', error);
    return cached.payload;
  }
}

/** 로그아웃처럼 계정이 바뀔 때 남은 캐시를 비운다. */
export async function clearCache(scope?: string): Promise<void> {
  if (!isTauri()) return;
  try {
    await invoke('cache_clear', { scope: scope ?? null });
  } catch (error) {
    console.warn('[mupid] 캐시를 비우지 못했어요.', error);
  }
}
