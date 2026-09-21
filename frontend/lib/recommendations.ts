/**
 * 서버 추천을 화면의 곡으로 옮긴다.
 *
 * **곡 변환은 카탈로그와 같은 것을 쓴다**(`stream-catalog.toTrack`). 두 벌이 되면
 * `id` 규칙이나 커버 고르는 규칙이 한쪽만 고쳐져, 같은 곡이 목록에 따라 다르게
 * 보이고 대기열·제외 목록과도 어긋난다.
 *
 * 여기서 더하는 것은 **서버가 고른 구간과 그 근거** 뿐이다.
 */
import type {
  DailyRecommendationResult,
  RecommendedSection,
  SimilarSectionResult,
} from './api/endpoints';
import { api } from './api/session';
import { moodLabel, type Track } from './mupid-data';
import { toTrack } from './stream-catalog';

/** 서버 enum → 화면 이름. 모르는 값은 그대로 보여준다 (코드는 늘 수 있다). */
const SECTION_LABELS: Record<string, string> = {
  INTRO: '인트로',
  VERSE: '벌스',
  PRE_CHORUS: '프리코러스',
  CHORUS: '코러스',
  BRIDGE: '브리지',
  BREAK: '브레이크',
  INST: '간주',
  SOLO: '솔로',
  OUTRO: '아웃트로',
};

export const sectionName = (label: string | null | undefined): string =>
  (label && SECTION_LABELS[label]) || label || '구간';

/**
 * 서버가 준 구간 하나 → 화면의 곡.
 *
 * **밀리초 → 초 변환을 여기 한 곳에서 한다.** 화면 여러 곳이 각자 나누면 한 곳만
 * 고쳐져 같은 곡이 다른 지점에서 시작한다.
 */
function withSection(
  item: RecommendedSection,
  extra: { recommendationId?: number; match?: number } = {},
): Track {
  const base = toTrack(item.music);
  const start = item.start_time_ms / 1000;
  const end = item.end_time_ms / 1000;
  // 곡 길이를 모르는 곡이 있다(`streams` 에 행이 없으면 `duration_ms` 가 null).
  // 그때는 길이로 거르지 않는다 — 거르면 멀쩡한 추천이 통째로 0초가 된다.
  const usable =
    Number.isFinite(start) && start >= 0 && (!base.duration || start < base.duration);
  return {
    ...base,
    // 곡 자체가 아니라 "이 구간" 을 권한 것이다.
    moment: usable ? start : base.moment,
    match: extra.match ?? base.match,
    reason: item.reason ?? '',
    recommended: {
      sectionLabel: sectionName(item.section_label),
      start: usable ? start : 0,
      // 끝이 시작보다 앞서면 믿지 않는다. 모르면 곡 끝까지 튼다.
      end: Number.isFinite(end) && end > start ? end : base.duration,
      reasonCode: item.reason_code ?? '',
      reason: item.reason ?? '',
      recommendationId: extra.recommendationId,
      rank: item.rank_order,
    },
  };
}

/**
 * 오늘의 모먼트. 배치가 하루 한 번 만들어 둔 것이라 하루 동안 같다.
 *
 * **곡 목록만 온다.** 오늘의 추천에 구간을 저장하지 않기로 했기 때문이다
 * (`open-questions.md` B7). 그래서 `recommended` 를 붙이지 않고, 화면은 분석 결과에서
 * 위치로 구간을 찾는다 — 서버가 고른 그 구간은 아니다.
 */
export async function fetchTodayMoments(
  signal?: AbortSignal,
): Promise<{ date: string; title: string; note: string; tracks: Track[] }> {
  const result: DailyRecommendationResult | undefined =
    await api.recommendation.daily(undefined, signal);
  const group = result?.groups?.[0];
  if (!group?.tracks?.length) {
    return { date: result?.date ?? '', title: '', note: '', tracks: [] };
  }
  return {
    date: result.date,
    title: group.title,
    note: group.reason,
    tracks: group.tracks.map((row) => ({
      ...toTrack(row.music),
      // 묶음의 무드도 코드 이름(영문)으로 온다.
      mood: moodLabel(group.mood || ''),
      reason: row.reason ?? '',
    })),
  };
}

/**
 * 다음에 들을 곡. 홈의 "당신을 위한 추천" 도 이 경로다 — 목록 길이만 다르다.
 *
 * **빈 목록은 오류가 아니다.** 조건에 맞는 곡이 없다는 뜻이다.
 */
export async function fetchNextTracks(
  options: { currentMusicId?: number; currentSectionId?: number; limit?: number } = {},
  signal?: AbortSignal,
): Promise<Track[]> {
  const result = await api.recommendation.next(
    {
      current_music_id: options.currentMusicId,
      current_music_section_id: options.currentSectionId,
      limit: options.limit,
    },
    signal,
  );
  if (!result?.tracks?.length) return [];
  return result.tracks.map((item) =>
    withSection(item, { recommendationId: result.recommendation_id }),
  );
}

/**
 * 고른 구간과 닮은 구간. **개인 취향이 섞이지 않는다.**
 *
 * `similarity` 는 코사인이고 순위 근거일 뿐 확률이 아니다. 그대로 백분율로 쓰면
 * 과장되지만, 목록 안에서의 상대 순서를 보여 주는 용도로는 쓸 만하다.
 */
export async function fetchSimilarSections(
  musicId: number,
  sectionId: number,
  limit?: number,
  signal?: AbortSignal,
): Promise<Track[]> {
  const result: SimilarSectionResult | undefined =
    await api.music.similarSection(musicId, sectionId, limit, signal);
  if (!result?.tracks?.length) return [];
  return result.tracks.map((item) =>
    withSection(
      { ...item, reason_code: '', reason: '' } as RecommendedSection,
      { match: Math.round(Math.min(Math.max(item.similarity, 0), 1) * 100) },
    ),
  );
}

/**
 * "당신을 위한 추천" 에 보여 줄 곡.
 *
 * 서버가 준 것이 있으면 그것을 쓰고, 없으면 카탈로그 앞쪽으로 채운다. 홈이 비어
 * 보이는 것보다는 낫지만 **추천은 아니다** — 화면은 `recommendationsReady` 로 그
 * 차이를 말한다.
 *
 * `MupidModel` 을 받지 않고 값만 받는다. `lib` 이 `hooks` 를 import 하면 순환이 된다.
 */
export function pickForYou(
  forYou: Track[],
  tracks: Track[],
  ignored: number[],
  size = 6,
): Track[] {
  const source = forYou.length ? forYou : tracks;
  return source.filter((track) => !ignored.includes(track.id)).slice(0, size);
}

/**
 * "당신을 위한 추천" 자리의 제목.
 *
 * 추천 서버가 답하지 않았을 때는 **추천이라고 부르지 않는다.** 카탈로그 앞쪽을
 * 잘라 채운 목록에 "당신을 위한" 이라고 적으면, 개인화가 꺼져 있는 것을 아무도
 * 눈치채지 못한다.
 */
export function forYouHeading(ready: boolean): { eyebrow: string; title: string } {
  return ready
    ? { eyebrow: '당신을 위한 추천', title: '취향에 이어지는 음악' }
    : { eyebrow: '둘러보기', title: '지금 들을 수 있는 음악' };
}
