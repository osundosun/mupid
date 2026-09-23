/**
 * 곡 구간 분석.
 *
 * 서버는 구간을 `INTRO` · `CHORUS` 같은 enum 이름과 ms 로 준다. 화면은 한글
 * 이름과 초를 쓰므로 여기서 옮긴다.
 *
 * 분석 파이프라인이 아직 돌지 않아 대부분의 곡은 `analyzed: false` 로 온다.
 * 그때는 `null` 을 돌려주고 화면은 임시 구간(`lib/stream-catalog.ts`)을 그대로
 * 쓴다.
 */

import { api } from './api/session';
import type { MusicSection } from './api/endpoints';
import type { SongSection, Track } from './mupid-data';

/** 서버 enum → 화면 이름. 없는 값은 그대로 보여준다. */
const LABELS: Record<string, string> = {
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

/**
 * 같은 이름이 여러 번 나오면 번호를 붙인다 — "벌스", "벌스 2".
 * 데모 데이터가 쓰던 표기와 같다.
 */
const toSections = (sections: MusicSection[]): SongSection[] => {
  const seen = new Map<string, number>();
  return sections.map((section) => {
    const base = LABELS[section.label] ?? section.label;
    const count = (seen.get(base) ?? 0) + 1;
    seen.set(base, count);
    return {
      label: count > 1 ? `${base} ${count}` : base,
      start: section.start_ms / 1000,
      end: section.end_ms / 1000,
      // 「닮은 구간」이 이 번호로 물어본다. 화면 이름으로는 되물을 수 없다.
      sectionId: section.section_id,
    };
  });
};

/**
 * 서버가 분석한 구간. 분석 전이거나 서버에 없는 곡이면 `null`.
 *
 * 실패해도 던지지 않는다. 구간은 있으면 좋은 값이지 없으면 재생을 막을 값이
 * 아니다.
 */
export async function fetchSections(
  track: Track,
  signal?: AbortSignal,
): Promise<SongSection[] | null> {
  if (typeof track.musicId !== 'number') return null;
  const musicId = track.musicId;
  try {
    const analysis = await api.music.analysis(musicId, signal);
    if (!analysis?.analyzed || !analysis.sections.length) return null;
    return toSections(analysis.sections);
  } catch (error) {
    if (signal?.aborted) return null;
    console.warn('[mupid] 곡 구간을 불러오지 못했어요.', error);
    return null;
  }
}
