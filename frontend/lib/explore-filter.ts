export type ExploreFilterQuery = {
  vocal?: string;
  mood?: string;
  genre?: string;
};

/** 사이드바의 안정적인 선택 키를 API 쿼리로 옮긴다. */
export function exploreFilterQuery(selected: readonly string[]): ExploreFilterQuery {
  const values = (prefix: string) => [...new Set(selected
    .filter((key) => key.startsWith(prefix))
    .map((key) => key.slice(prefix.length))
    .filter(Boolean))];
  const vocals = values('vocal:');
  const moods = values('mood:');
  const genres = values('genre:');
  return {
    vocal: vocals.length ? vocals.join(',') : undefined,
    mood: moods.length ? moods.join(',') : undefined,
    genre: genres.length ? genres.join(',') : undefined,
  };
}

/** 남성·여성·혼성·인스트루멘탈은 보컬 축에서 하나만 고른다. */
export function toggleExploreFilter(selected: readonly string[], key: string): string[] {
  if (selected.includes(key)) {
    return selected.filter((value) => value !== key);
  }
  if (key.startsWith('vocal:')) {
    return [...selected.filter((value) => !value.startsWith('vocal:')), key];
  }
  return [...selected, key];
}
