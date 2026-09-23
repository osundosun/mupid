/**
 * API 서버에 닿는가 (S15P21A204-444).
 *
 * 음악 스트리밍 서비스라 서버가 없으면 쓸 수 있는 기능이 없다. 그래서 웹이든
 * 앱이든 처음 켜질 때 한 번 묻고, 닿지 않으면 서비스를 열지 않는다
 * (`components/mupid/server-gate.tsx`). 기기에 남겨 둔 응답으로 화면을 채우는
 * 길도 없앴다 — 곡 목록만 뜨고 재생은 안 되는 화면은 고장으로 보인다.
 *
 * 묻는 방법(`get`)을 주입받는다. 서버 없이 판정 규칙만 시험하려는 것이다.
 */

export type HealthGetter = (signal?: AbortSignal) => Promise<{ status?: string } | null | undefined>;

/**
 * `UP` 일 때만 참이다. 응답이 없거나(네트워크·시간 초과), 503(DB·Redis 장애)이거나,
 * 모르는 상태면 모두 거짓이다. 던지지 않는다.
 */
export async function isServerUp(get: HealthGetter, signal?: AbortSignal): Promise<boolean> {
  try {
    const health = await get(signal);
    return health?.status === 'UP';
  } catch {
    return false;
  }
}
