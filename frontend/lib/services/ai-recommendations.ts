/**
 * AI 추천 서비스(FastAPI `/internal`) 응답을 프론트에서 쓰는 형태로 옮기는 **유일한 지점**.
 *
 * 계약은 숫자 ID 와 정수 밀리초다 — `music_id`, `music_section_id`,
 * `start_time_ms`, `end_time_ms`. 재생기는 `HTMLAudioElement.currentTime` 때문에 초를 쓴다.
 * 그 변환을 화면마다 흩어 놓으면 어디선가 1000 을 빠뜨린다. 그래서 초 변환은 이 파일의
 * `toSeconds()` 한 곳에서만 한다.
 *
 * 서버가 아직 붙지 않았으므로 이 모듈은 호출되지 않는다. 실제 연결 시 여기만 쓰면 된다.
 */

/** `/internal/recommendations/*` 응답 한 건. 필드명은 서버 계약 그대로 둔다. */
export type AiRecommendationItem = {
  music_id: number;
  music_section_id: number;
  start_time_ms: number;
  end_time_ms: number;
  rank_order: number;
  score: number;
  reason_code: string;
};

export type AiRecommendationResponse = {
  recommendation_id: number;
  items: AiRecommendationItem[];
  shortfall: number;
  model_version: string;
};

/** 밀리초 → 초. 재생기에 넘기기 직전에만 쓴다. */
export const toSeconds = (ms: number): number => ms / 1000;

/**
 * 숫자여야 하는 ID 를 확인한다.
 *
 * 문자열 ID 가 섞여 들어오면 `===` 비교가 전부 빗나가 목록이 조용히 비거나 중복된다.
 * 그래서 **버리지 않고 경고를 남긴 뒤 숫자로 바꿔 본다.** 숫자로 바꿀 수 없으면 그때만
 * 제외하며, 그 사실도 경고로 남긴다.
 */
export function coerceId(value: unknown, field: string): number | null {
  if (typeof value === 'number' && Number.isInteger(value)) return value;
  if (typeof value === 'string' && /^\d+$/.test(value)) {
    console.warn(`[ai] ${field} 가 문자열로 왔습니다 ("${value}"). 숫자로 변환합니다.`);
    return Number(value);
  }
  console.warn(`[ai] ${field} 를 숫자로 읽을 수 없습니다:`, value);
  return null;
}

/** 화면이 쓰는 추천 한 건. 구간 시간은 밀리초를 그대로 들고 다닌다. */
export type Recommendation = {
  musicId: number;
  musicSectionId: number;
  startTimeMs: number;
  endTimeMs: number;
  rankOrder: number;
  score: number;
  reasonCode: string;
};

/** 응답을 화면 형태로 옮기면서 잘못된 건을 경고와 함께 걸러낸다. */
export function toRecommendations(res: AiRecommendationResponse): Recommendation[] {
  const out: Recommendation[] = [];
  for (const it of res.items ?? []) {
    const musicId = coerceId(it.music_id, 'music_id');
    const musicSectionId = coerceId(it.music_section_id, 'music_section_id');
    if (musicId === null || musicSectionId === null) continue;
    if (!(it.end_time_ms > it.start_time_ms)) {
      console.warn(`[ai] music_id ${musicId} 구간 시간이 뒤집혔습니다`,
        it.start_time_ms, it.end_time_ms);
      continue;
    }
    out.push({
      musicId, musicSectionId,
      startTimeMs: it.start_time_ms, endTimeMs: it.end_time_ms,
      rankOrder: it.rank_order, score: it.score, reasonCode: it.reason_code,
    });
  }
  return out;
}

/**
 * 이미 가진 목록에 새 추천을 덧붙인다. **같은 `music_id` 는 다시 넣지 않는다.**
 *
 * 서버도 응답 직전에 곡 중복을 제거하지만, 화면은 여러 응답을 누적하므로 이전 응답과
 * 겹치는 곡은 여기서 막아야 한다. 제목이나 문자열 ID 가 아니라 숫자 ID 로만 판단한다.
 */
export function mergeRecommendations(
  existing: readonly Recommendation[],
  incoming: readonly Recommendation[],
): Recommendation[] {
  const seen = new Set(existing.map((r) => r.musicId));
  const merged = [...existing];
  for (const r of incoming) {
    if (seen.has(r.musicId)) continue;
    seen.add(r.musicId);
    merged.push(r);
  }
  return merged;
}
