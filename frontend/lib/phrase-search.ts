import type { MusicPage } from './api/endpoints';

const normalize = (value: string) => value.normalize('NFKC').trim().replace(/\s+/gu, ' ').toLocaleLowerCase();

/** 구문 검색 결과가 없는 API에서는 한 단어로 후보를 받고 프론트에서 전체 구문을 확인한다. */
export async function expandPhraseSearch(
  initial: MusicPage, query: string, filters: { vocal?: string; mood?: string; genre?: string }, signal: AbortSignal,
  search: (options: { q?: string; vocal?: string; mood?: string; genre?: string; page?: number; size?: number }, signal?: AbortSignal) => Promise<MusicPage>,
): Promise<MusicPage> {
  const phrase = normalize(query);
  const words = phrase.split(' ');
  if (initial.total_elements > 0 || words.length < 2) return initial;
  const seed = words.reduce((best, word) => word.length > best.length ? word : best);
  const matches = new Map<number, MusicPage['items'][number]>();
  let pages = 1;
  for (let page = 0; page < pages; page++) {
    if (signal.aborted) throw new DOMException('aborted', 'AbortError');
    const result = await search({ ...filters, q: seed, page, size: 100 }, signal);
    pages = result.total_pages;
    for (const music of result.items) {
      if ([music.title, music.artist.name, music.album.title].some(value => normalize(value).includes(phrase))) {
        matches.set(music.music_id, music);
      }
    }
  }
  const items = [...matches.values()];
  return { ...initial, items, total_elements: items.length, page: 0, size: items.length, total_pages: items.length ? 1 : 0 };
}
