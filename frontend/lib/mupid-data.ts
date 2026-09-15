export type SongSection = {
  label: string;
  start: number;
  end: number;
};

export type Track = {
  id: number;
  /**
   * 서버의 음악 번호. 재생은 이 번호로 청크 스트리밍을 받는다.
   * → `lib/stream-catalog.ts` · `lib/player/`
   */
  musicId?: number;
  title: string;
  artist: string;
  album: string;
  genre: string;
  mood: string;
  moment: number;
  duration: number;
  match: number;
  reason: string;
  image: string;
  tags: string[];
  sections: SongSection[];
};

export type SavedMoment = {
  id: number;
  track: Track;
  start: number;
  end: number;
  section: string;
};

export type Playlist = {
  id: number;
  name: string;
  tracks: Track[];
};

export type ChatMessage = {
  id: number;
  role: 'assistant' | 'user';
  text: string;
  tracks?: Track[];
};

/**
 * 화면이 다루는 곡 목록.
 *
 * **데모 곡은 없다.** 서버에 등록된 곡만 보여 준다 — `lib/stream-catalog.ts`
 * 가 `GET /api/v1/music` 로 받아 `MupidApp` 이 이 자리에 넣는다. 여기에
 * 예시 곡을 두면 없는 곡을 재생하려다 실패하고, 좋아요·기록도 서버에 남길 수
 * 없는 번호를 갖게 된다.
 */
export const tracks: Track[] = [];

export const genreOptions = [
  'Electronic',
  'Indie',
  'R&B',
  'Rock',
  'Pop',
  'Hip-hop',
  'Jazz',
  'Classical',
];
export const moodOptions = [
  '잔잔한',
  '벅찬',
  '몽환적인',
  '신나는',
  '집중되는',
  '감성적인',
  '강렬한',
  '설레는',
];

export const formatTime = (seconds: number) => {
  if (!Number.isFinite(seconds)) return '00:00';
  seconds = Math.max(0, seconds);
  const minutes = Math.floor(seconds / 60);
  return `${String(minutes).padStart(2, '0')}:${String(Math.floor(seconds % 60)).padStart(2, '0')}`;
};

/**
 * 위치가 걸치는 구간. **언제나 구간 하나를 준다.**
 *
 * 구간이 비어 있을 수 있다 — 서버가 곡 길이를 모르면(`streams` 에 행이 없어
 * `duration_ms` 가 null) `stream-catalog` 가 나눌 기준이 없어 빈 배열을 넣는다.
 * 부르는 쪽은 다섯 군데 모두 결과의 `.start` 를 바로 읽으므로, 여기서
 * undefined 를 돌려주면 화면이 통째로 죽는다. 곡 전체를 한 구간으로 준다.
 */
export const getSongSectionAt = (track: Track, at: number): SongSection => {
  const position = Number.isFinite(at)
    ? Math.max(0, Math.min(at, track.duration))
    : 0;
  return (
    track.sections.find(
      (section, index) =>
        position >= section.start &&
        (position < section.end || index === track.sections.length - 1),
    ) ??
    track.sections[0] ?? {
      label: '전체',
      start: 0,
      end: Number.isFinite(track.duration) ? Math.max(0, track.duration) : 0,
    }
  );
};

const moodEnergy: Record<string, number> = {
  잔잔한: 1,
  감성적인: 2,
  몽환적인: 2,
  집중되는: 3,
  설레는: 4,
  벅찬: 4,
  신나는: 5,
  강렬한: 5,
};

export const chatSuggestions = [
  '이 곡보다 조금 더 잔잔하게',
  '분위기는 유지하고 더 벅차게',
  '보컬의 여운이 긴 음악',
  '집중하기 좋은 비트로 이어줘',
];

export function deriveChatRecommendations(
  prompt: string,
  // 아직 한 곡도 고르지 않았을 수 있다. 그때는 기준 곡 없이 고른다.
  currentTrack: Track | null,
  pool: Track[],
) {
  const normalized = prompt.toLowerCase();
  const wantsCalm = /잔잔|차분|편안|고요|느리|부드러/.test(normalized);
  const wantsEnergy = /벅차|터지|강렬|신나|에너지|빠르|고조/.test(normalized);
  const wantsVocal = /보컬|목소리|노래|감성|여운/.test(normalized);
  const wantsRhythm = /집중|비트|리듬|그루브|베이스/.test(normalized);
  const wantsSimilar = /비슷|유사|같은|유지/.test(normalized);
  const currentEnergy = (currentTrack ? moodEnergy[currentTrack.mood] : undefined) ?? 3;
  const targetEnergy = wantsCalm
    ? Math.max(1, currentEnergy - 1.5)
    : wantsEnergy
      ? Math.min(5, currentEnergy + 1.5)
      : currentEnergy;
  const candidates = pool.filter((track) => track.id !== currentTrack?.id);
  const ranked = candidates
    .map((track) => {
      const energy = moodEnergy[track.mood] ?? 3;
      const sharedTags = currentTrack
        ? track.tags.filter((tag) => currentTrack.tags.includes(tag)).length
        : 0;
      let score = 100 - Math.abs(energy - targetEnergy) * 16 + track.match / 12;
      if (currentTrack && track.genre === currentTrack.genre) score += wantsSimilar ? 14 : 6;
      score += sharedTags * (wantsSimilar ? 9 : 4);
      if (
        wantsVocal &&
        (/보컬|여운/.test(track.reason) ||
          ['R&B', 'Indie', 'Pop'].includes(track.genre))
      )
        score += 24;
      if (
        wantsRhythm &&
        (/리듬|그루브|비트|베이스/.test(track.reason) ||
          ['Hip-hop', 'Electronic'].includes(track.genre))
      )
        score += 24;
      if (
        wantsEnergy &&
        /빌드|쌓|열리|커지/.test(`${track.reason} ${track.tags.join(' ')}`)
      )
        score += 18;
      if (wantsCalm && ['잔잔한', '감성적인', '몽환적인'].includes(track.mood))
        score += 18;
      return { track, score };
    })
    .sort((a, b) => b.score - a.score)
    .slice(0, 3)
    .map(({ track }) => track);

  const direction = wantsCalm
    ? '공간감과 결은 남기고 에너지 밀도를 한 단계 낮춘 곡'
    : wantsEnergy
      ? '현재 분위기에서 전개와 에너지가 더 크게 열리는 곡'
      : wantsVocal
        ? '보컬의 질감과 긴 여운이 중심이 되는 곡'
        : wantsRhythm
          ? '리듬과 그루브가 또렷해 자연스럽게 집중되는 곡'
          : wantsSimilar
            ? '장르와 전개 방식이 닮은 곡'
            : '요청한 분위기와 현재 곡의 청취 맥락이 잘 이어지는 곡';

  return {
    text: !ranked.length
      ? '지금 추천할 수 있는 다른 곡이 없어요. 다른 음악을 선택하거나 탐색에서 더 찾아보세요.'
      : currentTrack
        ? `${currentTrack.title}의 ${currentTrack.mood} 무드를 기준으로, ${direction}을 골랐어요. 추천 순간부터 들어보세요.`
        : `${direction}을 골랐어요. 마음에 드는 곡부터 들어보세요.`,
    tracks: ranked,
  };
}

export type Page =
  | 'home'
  | 'search'
  | 'library'
  | 'report'
  | 'profile'
  | 'player'
  | 'moment'
  | 'similar'
  | 'welcome';
/** 데모 곡이 없으므로 미리 채워 둘 플레이리스트도 없다. */
export const initialPlaylists: Playlist[] = [];

/** 저장한 구간도 마찬가지다. 서버 API 가 생기기 전까지는 비어 있다. */
export const initialMoments: SavedMoment[] = [];
