import type { SavedMoment, Track } from './mupid-data';

export type SavedMomentGroup = {
  track: Track;
  moments: SavedMoment[];
};

/** 저장 행은 그대로 두고, 보관함에서만 곡 단위로 묶는다. */
export function groupSavedMoments(moments: SavedMoment[]): SavedMomentGroup[] {
  const groups = new Map<number, SavedMomentGroup>();
  for (const moment of moments) {
    const found = groups.get(moment.track.id);
    if (found) found.moments.push(moment);
    else groups.set(moment.track.id, { track: moment.track, moments: [moment] });
  }
  return [...groups.values()].map((group) => ({
    ...group,
    moments: [...group.moments].sort((a, b) => a.start - b.start || a.end - b.end),
  }));
}

/** 겹치거나 바로 맞닿은 구간은 하나로 합쳐 재생 경계에서 끊기지 않게 한다. */
export function mergeMomentRanges(moments: SavedMoment[]): SavedMoment[] {
  const sorted = [...moments].sort((a, b) => a.start - b.start || a.end - b.end);
  const merged: SavedMoment[] = [];
  for (const moment of sorted) {
    const previous = merged.at(-1);
    if (previous && moment.start <= previous.end + 0.05) {
      previous.end = Math.max(previous.end, moment.end);
      previous.section = `${previous.section} · ${moment.section}`;
    } else {
      merged.push({ ...moment });
    }
  }
  return merged;
}

/**
 * 곡 전체 재생은 가장 앞 순간부터, 구간 버튼 재생은 그 순간의 시작점부터 만든다.
 * 연속 구간이 병합돼 있어도 클릭한 순간보다 앞쪽으로 되감기지 않는다.
 */
export function momentPlaybackRanges(
  moments: SavedMoment[],
  startAt?: SavedMoment,
): SavedMoment[] {
  const merged = mergeMomentRanges(moments);
  if (!startAt) return merged;
  const index = merged.findIndex(
    (range) => startAt.start >= range.start && startAt.start < range.end,
  );
  if (index < 0) return merged;
  const ranges = merged.slice(index).map((range) => ({ ...range }));
  ranges[0].start = Math.max(ranges[0].start, startAt.start);
  return ranges;
}
