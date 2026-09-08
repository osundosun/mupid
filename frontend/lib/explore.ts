import type { Track } from './mupid-data';

export function filterExploreTracks(
  catalog: Track[],
  query: string,
  mood: string,
  ignored: number[],
) {
  const q = query.trim().toLocaleLowerCase();
  return catalog.filter(
    (track) =>
      !ignored.includes(track.id) &&
      (mood === '전체' || track.mood === mood) &&
      (!q ||
        [
          track.title,
          track.artist,
          track.genre,
          track.mood,
          ...track.tags,
        ].some((value) => value.toLocaleLowerCase().includes(q))),
  );
}

/** A unique, circular window: even short result sets never repeat a cover. */
export function getExploreWindow<T>(items: T[], selectedIndex: number) {
  if (!items.length) return [];
  const count = Math.min(7, items.length);
  const before = Math.floor((count - 1) / 2);
  const center = ((selectedIndex % items.length) + items.length) % items.length;
  return Array.from({ length: count }, (_, i) => {
    const offset = i - before;
    const index = (center + offset + items.length) % items.length;
    return { item: items[index], index, offset };
  });
}
