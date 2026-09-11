export type SongSection = {
  label: string;
  start: number;
  end: number;
};

export type Track = {
  id: number;
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
  audio: string;
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

export const tracks: Track[] = [
  {
    id: 1,
    title: '푸른 오후',
    artist: 'LUME',
    album: 'Blue Static',
    genre: 'Electronic',
    mood: '몽환적인',
    moment: 72,
    duration: 102,
    match: 97,
    reason: '고요한 신스가 넓게 번지는 전환',
    image: '/sky-background.webp',
    audio: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-7.mp3',
    tags: ['#일렉트로닉', '#몽환적', '#슬로우빌드'],
    sections: [
      { label: '인트로', start: 0, end: 12 },
      { label: '벌스 1', start: 12, end: 31 },
      { label: '프리코러스', start: 31, end: 43 },
      { label: '코러스 1', start: 43, end: 60 },
      { label: '벌스 2', start: 60, end: 70 },
      { label: '코러스 2', start: 70, end: 92 },
      { label: '아웃트로', start: 92, end: 102 },
    ],
  },
  {
    id: 2,
    title: '구름 뒤의 산책',
    artist: 'Lunar Years',
    album: 'Night Geometry',
    genre: 'Synthpop',
    mood: '벅찬',
    moment: 104,
    duration: 134,
    match: 94,
    reason: '낮게 시작해 점층적으로 쌓이는 신스',
    image: '/sky-background.webp',
    audio: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3',
    tags: ['#신스팝', '#벅찬', '#드라이브'],
    sections: [
      { label: '인트로', start: 0, end: 15 },
      { label: '벌스 1', start: 15, end: 38 },
      { label: '프리코러스', start: 38, end: 50 },
      { label: '코러스 1', start: 50, end: 72 },
      { label: '벌스 2', start: 72, end: 98 },
      { label: '코러스 2', start: 98, end: 124 },
      { label: '아웃트로', start: 124, end: 134 },
    ],
  },
  {
    id: 3,
    title: '새벽의 온도',
    artist: 'Atelier Noir',
    album: 'Pale Windows',
    genre: 'Indie',
    mood: '잔잔한',
    moment: 46,
    duration: 66,
    match: 91,
    reason: '공간감이 넓게 열리는 보컬과 기타',
    image: '/sky-background.webp',
    audio: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3',
    tags: ['#인디', '#잔잔한', '#새벽감성'],
    sections: [
      { label: '인트로', start: 0, end: 8 },
      { label: '벌스', start: 8, end: 23 },
      { label: '프리코러스', start: 23, end: 33 },
      { label: '코러스', start: 33, end: 51 },
      { label: '브리지', start: 51, end: 60 },
      { label: '아웃트로', start: 60, end: 66 },
    ],
  },
  {
    id: 4,
    title: '모든 날, 모든 순간',
    artist: 'Mira Vale',
    album: 'Soft Collision',
    genre: 'Alternative',
    mood: '강렬한',
    moment: 172,
    duration: 202,
    match: 88,
    reason: '리듬이 무너졌다 다시 커지는 순간',
    image: '/sky-background.webp',
    audio: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3',
    tags: ['#얼터너티브', '#강렬한', '#다이내믹'],
    sections: [
      { label: '인트로', start: 0, end: 18 },
      { label: '벌스 1', start: 18, end: 48 },
      { label: '프리코러스', start: 48, end: 66 },
      { label: '코러스 1', start: 66, end: 94 },
      { label: '벌스 2', start: 94, end: 124 },
      { label: '브리지', start: 124, end: 143 },
      { label: '파이널 코러스', start: 143, end: 181 },
      { label: '아웃트로', start: 181, end: 202 },
    ],
  },
  {
    id: 5,
    title: '우리의 계절',
    artist: 'North Arcade',
    album: 'After Midnight',
    genre: 'Rock',
    mood: '신나는',
    moment: 29,
    duration: 49,
    match: 86,
    reason: '낮은 비트 뒤에 한 번에 열리는 밴드 사운드',
    image: '/sky-background.webp',
    audio: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-4.mp3',
    tags: ['#록', '#신나는', '#라이브'],
    sections: [
      { label: '인트로', start: 0, end: 7 },
      { label: '벌스', start: 7, end: 18 },
      { label: '빌드업', start: 18, end: 25 },
      { label: '코러스', start: 25, end: 39 },
      { label: '아웃트로', start: 39, end: 49 },
    ],
  },
  {
    id: 6,
    title: '조금만, 더',
    artist: 'Sora Motel',
    album: 'Velvet Exit',
    genre: 'R&B',
    mood: '감성적인',
    moment: 66,
    duration: 96,
    match: 84,
    reason: '보컬 뒤로 길게 남는 여운과 느린 드롭',
    image: '/sky-background.webp',
    audio: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-5.mp3',
    tags: ['#R&B', '#감성적', '#여운'],
    sections: [
      { label: '인트로', start: 0, end: 10 },
      { label: '벌스 1', start: 10, end: 30 },
      { label: '프리코러스', start: 30, end: 42 },
      { label: '코러스 1', start: 42, end: 59 },
      { label: '브리지', start: 59, end: 73 },
      { label: '파이널 코러스', start: 73, end: 88 },
      { label: '아웃트로', start: 88, end: 96 },
    ],
  },
  {
    id: 7,
    title: '후회 밖의 우리',
    artist: 'Dawn Motel',
    album: 'Young Signals',
    genre: 'Pop',
    mood: '설레는',
    moment: 147,
    duration: 177,
    match: 82,
    reason: '드럼 레이어가 하나씩 더해지는 빌드업',
    image: '/sky-background.webp',
    audio: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-6.mp3',
    tags: ['#팝', '#설레는', '#빌드업'],
    sections: [
      { label: '인트로', start: 0, end: 15 },
      { label: '벌스 1', start: 15, end: 43 },
      { label: '프리코러스', start: 43, end: 60 },
      { label: '코러스 1', start: 60, end: 85 },
      { label: '벌스 2', start: 85, end: 113 },
      { label: '브리지', start: 113, end: 132 },
      { label: '파이널 코러스', start: 132, end: 162 },
      { label: '아웃트로', start: 162, end: 177 },
    ],
  },
  {
    id: 8,
    title: '비 오는 날의 우리',
    artist: 'Morrow',
    album: 'Open Roads',
    genre: 'Hip-hop',
    mood: '집중되는',
    moment: 51,
    duration: 81,
    match: 80,
    reason: '일정한 그루브 위로 쌓이는 베이스',
    image: '/sky-background.webp',
    audio: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-8.mp3',
    tags: ['#힙합', '#집중', '#그루브'],
    sections: [
      { label: '인트로', start: 0, end: 9 },
      { label: '벌스 1', start: 9, end: 28 },
      { label: '훅 1', start: 28, end: 41 },
      { label: '벌스 2', start: 41, end: 51 },
      { label: '훅 2', start: 51, end: 72 },
      { label: '아웃트로', start: 72, end: 81 },
    ],
  },
];

// Fictional catalog entries for UI exploration. Audio and section timings reuse
// the original eight demo sources; these are not 42 additional recordings.
const extraDemoTitles = [
  '유리빛 아침', '느린 파도', '너에게 닿는 빛', '밤의 정원', '여름의 뒷면', '별빛 우편함',
  '작은 우주', '우리가 머문 자리', '오렌지 노을', '달의 궤도', '비 오는 창가', '아주 먼 산책',
  '바람의 문장', '수요일의 꿈', '반짝이는 거리', '새벽 편지', '너머의 바다', '하얀 소음',
  '겨울을 건너', '푸른 신호등', '나른한 일요일', '빛나는 조각', '오늘의 끝에서', '종이비행기',
  '해질녘 대화', '밤을 걷는 마음', '여백 사이', '낯선 행성', '봄의 속도', '남겨진 멜로디',
  '투명한 계절', '먼지 속 별', '반달 아래', '잠들지 않는 도시', '파스텔 구름', '둘만의 주파수',
  '숲의 숨결', '조용한 파란색', '새로운 페이지', '오래된 카세트', '다시 만난 아침', '마지막 여름밤',
];
const demoArtists = ['Cloud Letter', 'Blue Sunday', 'Mellow Room', 'Paper Moon', 'Sea Glass', 'Afterglow', 'Day Orbit'];
const demoGenres = ['Pop', 'Electronic', 'R&B / Soul', 'Rock', 'Folk', 'Classical'];
const originalDemoTracks = tracks.slice();
tracks.push(...extraDemoTitles.map((title, index): Track => {
  const source = originalDemoTracks[index % originalDemoTracks.length];
  return {
    ...source,
    id: index + 9,
    title,
    artist: demoArtists[index % demoArtists.length],
    album: `뮤피드 데모 모음 ${Math.floor(index / 7) + 1}`,
    genre: demoGenres[index % demoGenres.length],
    reason: 'UI 탐색용 예시 곡 · 기존 데모 음원 재사용',
    tags: ['#예시음악'],
    sections: source.sections.map(section => ({ ...section })),
  };
}));

// Stable, individual artwork keeps each demo track recognizable across screens.
tracks.forEach(track => { track.image = `/demo-covers/album-${track.id}.svg`; });

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

export const getSongSectionAt = (track: Track, at: number) => {
  const position = Number.isFinite(at)
    ? Math.max(0, Math.min(at, track.duration))
    : 0;
  return (
    track.sections.find(
      (section, index) =>
        position >= section.start &&
        (position < section.end || index === track.sections.length - 1),
    ) ?? track.sections[0]
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
  currentTrack: Track,
  pool: Track[],
) {
  const normalized = prompt.toLowerCase();
  const wantsCalm = /잔잔|차분|편안|고요|느리|부드러/.test(normalized);
  const wantsEnergy = /벅차|터지|강렬|신나|에너지|빠르|고조/.test(normalized);
  const wantsVocal = /보컬|목소리|노래|감성|여운/.test(normalized);
  const wantsRhythm = /집중|비트|리듬|그루브|베이스/.test(normalized);
  const wantsSimilar = /비슷|유사|같은|유지/.test(normalized);
  const currentEnergy = moodEnergy[currentTrack.mood] ?? 3;
  const targetEnergy = wantsCalm
    ? Math.max(1, currentEnergy - 1.5)
    : wantsEnergy
      ? Math.min(5, currentEnergy + 1.5)
      : currentEnergy;
  const candidates = pool.filter((track) => track.id !== currentTrack.id);
  const ranked = candidates
    .map((track) => {
      const energy = moodEnergy[track.mood] ?? 3;
      const sharedTags = track.tags.filter((tag) =>
        currentTrack.tags.includes(tag),
      ).length;
      let score = 100 - Math.abs(energy - targetEnergy) * 16 + track.match / 12;
      if (track.genre === currentTrack.genre) score += wantsSimilar ? 14 : 6;
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
    text: ranked.length
      ? `${currentTrack.title}의 ${currentTrack.mood} 무드를 기준으로, ${direction}을 골랐어요. 추천 순간부터 들어보세요.`
      : '지금 추천할 수 있는 다른 곡이 없어요. 다른 음악을 선택하거나 탐색에서 더 찾아보세요.',
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
export const initialPlaylists: Playlist[] = [
  {
    id: 1,
    name: '규원의 Mupid Mix',
    tracks: [tracks[0], tracks[2], tracks[1]],
  },
  { id: 2, name: '헤어지지 말아요', tracks: [tracks[5], tracks[6]] },
  { id: 3, name: '새벽 드라이브', tracks: [tracks[1], tracks[4], tracks[3]] },
  { id: 4, name: '잠들기 전, 이 노래', tracks: [tracks[2], tracks[7]] },
];
export const initialMoments: SavedMoment[] = [
  { id: 1, track: tracks[0], start: 70, end: 92, section: '코러스 2' },
  { id: 2, track: tracks[2], start: 33, end: 51, section: '코러스' },
  { id: 3, track: tracks[6], start: 43, end: 60, section: '프리코러스' },
];
