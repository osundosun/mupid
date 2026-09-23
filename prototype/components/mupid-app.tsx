'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import type { PointerEvent as ReactPointerEvent } from 'react';
import {
  BarChart3,
  Check,
  ChevronLeft,
  ChevronRight,
  CircleUserRound,
  Compass,
  Disc3,
  Gauge,
  Heart,
  History,
  Home,
  Library,
  ListMusic,
  LogOut,
  MessageCircle,
  MoreHorizontal,
  Music2,
  Pause,
  Pencil,
  Play,
  Plus,
  RefreshCw,
  Repeat2,
  Search,
  Shuffle,
  SkipBack,
  SkipForward,
  Sparkles,
  Trash2,
  UserRound,
  Volume2,
  WandSparkles,
  AudioWaveform as Waveform,
  X,
} from 'lucide-react';

import { Area, AreaChart, Bar, BarChart, CartesianGrid, LabelList, PolarAngleAxis, PolarGrid, Radar, RadarChart, XAxis, YAxis } from 'recharts';

import { Button } from '@/components/ui/button';
import { ChartContainer, ChartTooltip, ChartTooltipContent } from '@/components/ui/chart';

type View = 'home' | 'search' | 'playlist' | 'dna' | 'profile';
type Stage = 'auth' | 'onboarding' | 'app';

type SongSection = {
  label: string;
  start: number;
  end: number;
};

type Track = {
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

type SavedMoment = {
  id: number;
  track: Track;
  start: number;
  end: number;
  section: string;
};

type Playlist = {
  id: number;
  name: string;
  tracks: Track[];
};

type ChatMessage = {
  id: number;
  role: 'assistant' | 'user';
  text: string;
  tracks?: Track[];
};

type MomentTimelineProps = {
  anchor: number;
  duration: number;
  end: number;
  onEndChange: (value: number) => void;
  onSeek: (value: number) => void;
  onStartChange: (value: number) => void;
  start: number;
};

const MIN_MOMENT_LENGTH = 5;

function MomentTimeline({ anchor, duration, end, onEndChange, onSeek, onStartChange, start }: MomentTimelineProps) {
  const timelineRef = useRef<HTMLDivElement>(null);
  const dragRef = useRef<{ mode: 'start' | 'end' | 'move'; originX: number; start: number; end: number } | null>(null);
  const safeDuration = Math.max(duration, 1);
  const startPercent = (start / safeDuration) * 100;
  const endPercent = (end / safeDuration) * 100;
  const anchorPercent = (Math.min(Math.max(anchor, 0), safeDuration) / safeDuration) * 100;
  const clamp = (value: number, min: number, max: number) => Math.min(Math.max(value, min), max);

  const setRange = (nextStart: number, nextEnd: number) => {
    onStartChange(Math.round(nextStart));
    onEndChange(Math.round(nextEnd));
  };

  const beginDrag = (event: ReactPointerEvent<HTMLElement>, mode: 'start' | 'end' | 'move') => {
    event.preventDefault();
    event.stopPropagation();
    event.currentTarget.setPointerCapture(event.pointerId);
    dragRef.current = { mode, originX: event.clientX, start, end };
  };

  const updateDrag = (event: ReactPointerEvent<HTMLElement>) => {
    const drag = dragRef.current;
    const width = timelineRef.current?.getBoundingClientRect().width;
    if (!drag || !width) return;
    const delta = ((event.clientX - drag.originX) / width) * safeDuration;

    if (drag.mode === 'start') {
      onStartChange(Math.round(clamp(drag.start + delta, 0, end - MIN_MOMENT_LENGTH)));
      return;
    }
    if (drag.mode === 'end') {
      onEndChange(Math.round(clamp(drag.end + delta, start + MIN_MOMENT_LENGTH, safeDuration)));
      return;
    }

    const selectionLength = drag.end - drag.start;
    const nextStart = clamp(drag.start + delta, 0, safeDuration - selectionLength);
    setRange(nextStart, nextStart + selectionLength);
  };

  const finishDrag = () => { dragRef.current = null; };
  const moveRange = (seconds: number) => {
    const selectionLength = end - start;
    const nextStart = clamp(start + seconds, 0, safeDuration - selectionLength);
    setRange(nextStart, nextStart + selectionLength);
  };
  const handleKey = (event: React.KeyboardEvent<HTMLElement>, mode: 'start' | 'end' | 'move') => {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    event.preventDefault();
    const delta = (event.key === 'ArrowRight' ? 1 : -1) * (event.shiftKey ? 5 : 1);
    if (mode === 'start') onStartChange(Math.round(clamp(start + delta, 0, end - MIN_MOMENT_LENGTH)));
    if (mode === 'end') onEndChange(Math.round(clamp(end + delta, start + MIN_MOMENT_LENGTH, safeDuration)));
    if (mode === 'move') moveRange(delta);
  };
  const seekFromPointer = (event: ReactPointerEvent<HTMLDivElement>) => {
    const bounds = timelineRef.current?.getBoundingClientRect();
    if (!bounds) return;
    onSeek(Math.round(clamp(((event.clientX - bounds.left) / bounds.width) * safeDuration, 0, safeDuration)));
  };

  return (
    <div>
      <div className="mb-2 flex justify-between font-mono text-xs font-medium text-white/45">
        {[0, 0.25, 0.5, 0.75, 1].map((point) => <span key={point}>{formatTime(safeDuration * point)}</span>)}
      </div>
      <div
        aria-label="곡 타임라인. 클릭하면 재생 위치가 이동합니다."
        className="relative h-32 cursor-crosshair touch-none overflow-hidden rounded-xl border border-[#414047] bg-[#1f1e22]"
        onPointerDown={seekFromPointer}
        ref={timelineRef}
      >
        <div className="absolute inset-x-3 inset-y-4 flex items-center gap-[3px]">
          {Array.from({ length: 72 }, (_, index) => {
            const barTime = (index / 71) * safeDuration;
            const selected = barTime >= start && barTime <= end;
            return <span className={`min-w-0 flex-1 rounded-full transition-colors ${selected ? 'bg-[#d997ad]' : 'bg-[#57545c]'}`} key={index} style={{ height: `${20 + ((index * 37 + 17) % 72)}%` }} />;
          })}
        </div>

        <div className="pointer-events-none absolute inset-y-0 left-0 bg-black/25" style={{ width: `${startPercent}%` }} />
        <div className="pointer-events-none absolute inset-y-0 right-0 bg-black/25" style={{ width: `${100 - endPercent}%` }} />

        <div
          aria-label={`선택 구간 ${formatTime(start)}부터 ${formatTime(end)}까지. 화살표 키로 이동합니다.`}
          className="absolute inset-y-2 cursor-grab rounded-lg border border-[#b96f88] bg-[#b96f88]/15 active:cursor-grabbing focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#d997ad]"
          onKeyDown={(event) => handleKey(event, 'move')}
          onPointerDown={(event) => beginDrag(event, 'move')}
          onPointerMove={updateDrag}
          onPointerUp={finishDrag}
          role="group"
          style={{ left: `${startPercent}%`, width: `${endPercent - startPercent}%` }}
          tabIndex={0}
        />

        <div aria-hidden className="pointer-events-none absolute top-2 z-40 -translate-x-1/2" style={{ left: `${anchorPercent}%` }}>
          <span className="grid size-6 place-items-center rounded-full bg-[#b96f88] text-[#fff3f7] shadow-md"><Heart className="size-3 fill-current" /></span>
        </div>
      </div>

      <div className="mt-4 grid gap-2 sm:grid-cols-2">
        <div className="flex items-center gap-2 rounded-xl border border-[#46444c] bg-[#323136] p-2.5">
          <div className="min-w-0 flex-1 pl-1"><span className="block text-[11px] font-semibold text-white/50">시작 시간</span><strong className="font-mono text-lg text-white">{formatTime(start)}</strong></div>
          <Button aria-label="시작점을 1초 이전으로" className="size-8 border-[#4d4b53] bg-[#3a393f] text-white/70 hover:bg-[#45434a] hover:text-white" onClick={() => onStartChange(clamp(start - 1, 0, end - MIN_MOMENT_LENGTH))} size="icon" variant="outline"><ChevronLeft /></Button>
          <Button aria-label="시작점을 1초 이후로" className="size-8 border-[#4d4b53] bg-[#3a393f] text-white/70 hover:bg-[#45434a] hover:text-white" onClick={() => onStartChange(clamp(start + 1, 0, end - MIN_MOMENT_LENGTH))} size="icon" variant="outline"><ChevronRight /></Button>
        </div>
        <div className="flex items-center gap-2 rounded-xl border border-[#46444c] bg-[#323136] p-2.5">
          <div className="min-w-0 flex-1 pl-1"><span className="block text-[11px] font-semibold text-white/50">종료 시간</span><strong className="font-mono text-lg text-white">{formatTime(end)}</strong></div>
          <Button aria-label="끝점을 1초 이전으로" className="size-8 border-[#4d4b53] bg-[#3a393f] text-white/70 hover:bg-[#45434a] hover:text-white" onClick={() => onEndChange(clamp(end - 1, start + MIN_MOMENT_LENGTH, safeDuration))} size="icon" variant="outline"><ChevronLeft /></Button>
          <Button aria-label="끝점을 1초 이후로" className="size-8 border-[#4d4b53] bg-[#3a393f] text-white/70 hover:bg-[#45434a] hover:text-white" onClick={() => onEndChange(clamp(end + 1, start + MIN_MOMENT_LENGTH, safeDuration))} size="icon" variant="outline"><ChevronRight /></Button>
        </div>
      </div>
    </div>
  );
}

const tracks: Track[] = [
  {
    id: 1,
    title: 'Sea of Voices',
    artist: 'LUME',
    album: 'Blue Static',
    genre: 'Electronic',
    mood: '몽환적인',
    moment: 72,
    duration: 102,
    match: 97,
    reason: '고요한 신스가 넓게 번지는 전환',
    image: 'https://images.unsplash.com/photo-1493225457124-a3eb161ffa5f?auto=format&fit=crop&w=1200&q=88',
    audio: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-7.mp3',
    tags: ['#일렉트로닉', '#몽환적', '#슬로우빌드'],
    sections: [{ label: '인트로', start: 0, end: 12 }, { label: '벌스 1', start: 12, end: 31 }, { label: '프리코러스', start: 31, end: 43 }, { label: '코러스 1', start: 43, end: 60 }, { label: '벌스 2', start: 60, end: 70 }, { label: '코러스 2', start: 70, end: 92 }, { label: '아웃트로', start: 92, end: 102 }],
  },
  {
    id: 2,
    title: 'Neon Skyline',
    artist: 'Lunar Years',
    album: 'Night Geometry',
    genre: 'Synthpop',
    mood: '벅찬',
    moment: 104,
    duration: 134,
    match: 94,
    reason: '낮게 시작해 점층적으로 쌓이는 신스',
    image: 'https://images.unsplash.com/photo-1506157786151-b8491531f063?auto=format&fit=crop&w=720&q=85',
    audio: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3',
    tags: ['#신스팝', '#벅찬', '#드라이브'],
    sections: [{ label: '인트로', start: 0, end: 15 }, { label: '벌스 1', start: 15, end: 38 }, { label: '프리코러스', start: 38, end: 50 }, { label: '코러스 1', start: 50, end: 72 }, { label: '벌스 2', start: 72, end: 98 }, { label: '코러스 2', start: 98, end: 124 }, { label: '아웃트로', start: 124, end: 134 }],
  },
  {
    id: 3,
    title: 'Cold Morning',
    artist: 'Atelier Noir',
    album: 'Pale Windows',
    genre: 'Indie',
    mood: '잔잔한',
    moment: 46,
    duration: 66,
    match: 91,
    reason: '공간감이 넓게 열리는 보컬과 기타',
    image: 'https://images.unsplash.com/photo-1524368535928-5b5e00ddc76b?auto=format&fit=crop&w=720&q=85',
    audio: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3',
    tags: ['#인디', '#잔잔한', '#새벽감성'],
    sections: [{ label: '인트로', start: 0, end: 8 }, { label: '벌스', start: 8, end: 23 }, { label: '프리코러스', start: 23, end: 33 }, { label: '코러스', start: 33, end: 51 }, { label: '브리지', start: 51, end: 60 }, { label: '아웃트로', start: 60, end: 66 }],
  },
  {
    id: 4,
    title: 'Electric Bloom',
    artist: 'Mira Vale',
    album: 'Soft Collision',
    genre: 'Alternative',
    mood: '강렬한',
    moment: 172,
    duration: 202,
    match: 88,
    reason: '리듬이 무너졌다 다시 커지는 순간',
    image: 'https://images.unsplash.com/photo-1492684223066-81342ee5ff30?auto=format&fit=crop&w=720&q=85',
    audio: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3',
    tags: ['#얼터너티브', '#강렬한', '#다이내믹'],
    sections: [{ label: '인트로', start: 0, end: 18 }, { label: '벌스 1', start: 18, end: 48 }, { label: '프리코러스', start: 48, end: 66 }, { label: '코러스 1', start: 66, end: 94 }, { label: '벌스 2', start: 94, end: 124 }, { label: '브리지', start: 124, end: 143 }, { label: '파이널 코러스', start: 143, end: 181 }, { label: '아웃트로', start: 181, end: 202 }],
  },
  {
    id: 5,
    title: 'Silver Hours',
    artist: 'North Arcade',
    album: 'After Midnight',
    genre: 'Rock',
    mood: '신나는',
    moment: 29,
    duration: 49,
    match: 86,
    reason: '낮은 비트 뒤에 한 번에 열리는 밴드 사운드',
    image: 'https://images.unsplash.com/photo-1521337581100-8ca9a73a5f79?auto=format&fit=crop&w=720&q=85',
    audio: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-4.mp3',
    tags: ['#록', '#신나는', '#라이브'],
    sections: [{ label: '인트로', start: 0, end: 7 }, { label: '벌스', start: 7, end: 18 }, { label: '빌드업', start: 18, end: 25 }, { label: '코러스', start: 25, end: 39 }, { label: '아웃트로', start: 39, end: 49 }],
  },
  {
    id: 6,
    title: 'Afterglow',
    artist: 'Sora Motel',
    album: 'Velvet Exit',
    genre: 'R&B',
    mood: '감성적인',
    moment: 66,
    duration: 96,
    match: 84,
    reason: '보컬 뒤로 길게 남는 여운과 느린 드롭',
    image: 'https://images.unsplash.com/photo-1501386761578-eac5c94b800a?auto=format&fit=crop&w=720&q=85',
    audio: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-5.mp3',
    tags: ['#R&B', '#감성적', '#여운'],
    sections: [{ label: '인트로', start: 0, end: 10 }, { label: '벌스 1', start: 10, end: 30 }, { label: '프리코러스', start: 30, end: 42 }, { label: '코러스 1', start: 42, end: 59 }, { label: '브리지', start: 59, end: 73 }, { label: '파이널 코러스', start: 73, end: 88 }, { label: '아웃트로', start: 88, end: 96 }],
  },
  {
    id: 7,
    title: 'Static Hearts',
    artist: 'Dawn Motel',
    album: 'Young Signals',
    genre: 'Pop',
    mood: '설레는',
    moment: 147,
    duration: 177,
    match: 82,
    reason: '드럼 레이어가 하나씩 더해지는 빌드업',
    image: 'https://images.unsplash.com/photo-1501612780327-45045538702b?auto=format&fit=crop&w=720&q=85',
    audio: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-6.mp3',
    tags: ['#팝', '#설레는', '#빌드업'],
    sections: [{ label: '인트로', start: 0, end: 15 }, { label: '벌스 1', start: 15, end: 43 }, { label: '프리코러스', start: 43, end: 60 }, { label: '코러스 1', start: 60, end: 85 }, { label: '벌스 2', start: 85, end: 113 }, { label: '브리지', start: 113, end: 132 }, { label: '파이널 코러스', start: 132, end: 162 }, { label: '아웃트로', start: 162, end: 177 }],
  },
  {
    id: 8,
    title: 'Velvet Run',
    artist: 'Morrow',
    album: 'Open Roads',
    genre: 'Hip-hop',
    mood: '집중되는',
    moment: 51,
    duration: 81,
    match: 80,
    reason: '일정한 그루브 위로 쌓이는 베이스',
    image: 'https://images.unsplash.com/photo-1516280440614-37939bbacd81?auto=format&fit=crop&w=720&q=85',
    audio: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-8.mp3',
    tags: ['#힙합', '#집중', '#그루브'],
    sections: [{ label: '인트로', start: 0, end: 9 }, { label: '벌스 1', start: 9, end: 28 }, { label: '훅 1', start: 28, end: 41 }, { label: '벌스 2', start: 41, end: 51 }, { label: '훅 2', start: 51, end: 72 }, { label: '아웃트로', start: 72, end: 81 }],
  },
];

const genreOptions = ['Electronic', 'Indie', 'R&B', 'Rock', 'Pop', 'Hip-hop', 'Jazz', 'Classical'];
const moodOptions = ['잔잔한', '벅찬', '몽환적인', '신나는', '집중되는', '감성적인', '강렬한', '설레는'];

const formatTime = (seconds: number) => {
  if (!Number.isFinite(seconds)) return '00:00';
  const minutes = Math.floor(seconds / 60);
  return `${String(minutes).padStart(2, '0')}:${String(Math.floor(seconds % 60)).padStart(2, '0')}`;
};

const getSongSectionAt = (track: Track, at: number) => track.sections.find((section, index) => (
  at >= section.start && (at < section.end || index === track.sections.length - 1)
)) ?? track.sections[track.sections.length - 1];

const moodEnergy: Record<string, number> = {
  '잔잔한': 1,
  '감성적인': 2,
  '몽환적인': 2,
  '집중되는': 3,
  '설레는': 4,
  '벅찬': 4,
  '신나는': 5,
  '강렬한': 5,
};

const chatSuggestions = [
  '이 곡보다 조금 더 잔잔하게',
  '분위기는 유지하고 더 벅차게',
  '보컬의 여운이 긴 음악',
  '집중하기 좋은 비트로 이어줘',
];

function deriveChatRecommendations(prompt: string, currentTrack: Track, pool: Track[]) {
  const normalized = prompt.toLowerCase();
  const wantsCalm = /잔잔|차분|편안|고요|느리|부드러/.test(normalized);
  const wantsEnergy = /벅차|터지|강렬|신나|에너지|빠르|고조/.test(normalized);
  const wantsVocal = /보컬|목소리|노래|감성|여운/.test(normalized);
  const wantsRhythm = /집중|비트|리듬|그루브|베이스/.test(normalized);
  const wantsSimilar = /비슷|유사|같은|유지/.test(normalized);
  const currentEnergy = moodEnergy[currentTrack.mood] ?? 3;
  const targetEnergy = wantsCalm ? Math.max(1, currentEnergy - 1.5) : wantsEnergy ? Math.min(5, currentEnergy + 1.5) : currentEnergy;
  const candidates = pool.filter((track) => track.id !== currentTrack.id);
  const ranked = candidates
    .map((track) => {
      const energy = moodEnergy[track.mood] ?? 3;
      const sharedTags = track.tags.filter((tag) => currentTrack.tags.includes(tag)).length;
      let score = 100 - Math.abs(energy - targetEnergy) * 16 + track.match / 12;
      if (track.genre === currentTrack.genre) score += wantsSimilar ? 14 : 6;
      score += sharedTags * (wantsSimilar ? 9 : 4);
      if (wantsVocal && (/보컬|여운/.test(track.reason) || ['R&B', 'Indie', 'Pop'].includes(track.genre))) score += 24;
      if (wantsRhythm && (/리듬|그루브|비트|베이스/.test(track.reason) || ['Hip-hop', 'Electronic'].includes(track.genre))) score += 24;
      if (wantsEnergy && /빌드|쌓|열리|커지/.test(`${track.reason} ${track.tags.join(' ')}`)) score += 18;
      if (wantsCalm && ['잔잔한', '감성적인', '몽환적인'].includes(track.mood)) score += 18;
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
    text: `${currentTrack.title}의 ${currentTrack.mood} 무드를 기준으로, ${direction}을 골랐어요. 추천 순간부터 들어보세요.`,
    tracks: ranked.length ? ranked : tracks.filter((track) => track.id !== currentTrack.id).slice(0, 3),
  };
}

function Brand() {
  return (
    <span className="relative inline-block h-14 w-[136px] shrink-0 overflow-hidden" role="img" aria-label="Mupid">
      <img alt="" aria-hidden="true" className="absolute left-[-6px] top-[-15px] h-auto w-[147px] max-w-none mix-blend-multiply" src="/mupid-cupid-logo.png" />
    </span>
  );
}

function AuthScreen({ onContinue }: { onContinue: (needsOnboarding: boolean) => void }) {
  const [mode, setMode] = useState<'login' | 'signup'>('login');
  const [email, setEmail] = useState('demo@mupid.kr');
  const [password, setPassword] = useState('mupid1234');
  const [nickname, setNickname] = useState('뮤피더');
  const [checked, setChecked] = useState(false);
  const canSubmit = email.includes('@') && password.length >= 6 && (mode === 'login' || (nickname.length >= 2 && checked));

  return (
    <main className="grid min-h-screen bg-background text-foreground lg:grid-cols-[1.08fr_.92fr]" data-editorial>
      <section className="relative hidden overflow-hidden border-r border-border p-12 lg:flex lg:flex-col lg:justify-between">
        <img alt="라이브 음악 공연" className="absolute inset-10 h-[calc(100%-5rem)] w-[calc(100%-5rem)] object-cover opacity-10 grayscale" src={tracks[0].image} />
        <div className="absolute inset-0 bg-[linear-gradient(140deg,rgba(250,250,250,.5),rgba(250,250,250,.92))]" />
        <div className="relative"><Brand /></div>
        <div className="relative max-w-2xl">
          <p className="mb-5 font-mono text-[10px] font-medium lowercase tracking-[.02em] text-muted-foreground">music begins at the moment / 01—08</p>
          <h1 className="text-6xl font-black leading-[.98] tracking-[-0.07em]">좋아한 순간이<br />다음 음악이 되는 곳</h1>
          <p className="mt-7 max-w-lg text-base leading-7 text-muted-foreground">재생, 반복, 탐색 그리고 특정 구간의 반응까지. 뮤피드는 당신이 음악을 듣는 방식을 취향으로 바꿉니다.</p>
        </div>
        <div className="relative flex gap-3 font-mono text-[10px] font-medium lowercase tracking-[.02em] text-muted-foreground"><span>moment discovery</span><span>/</span><span>listening DNA</span><span>/</span><span>personalized feed</span></div>
      </section>

      <section className="flex min-h-screen items-center justify-center bg-card/40 px-5 py-12 text-foreground">
        <div className="w-full max-w-md">
          <div className="mb-10 lg:hidden"><Brand /></div>
          <div className="mb-7">
            <p className="font-mono text-[11px] font-medium lowercase tracking-[.02em] text-[#728d00]">WELCOME TO MUPID</p>
            <h2 className="mt-2 text-3xl font-black tracking-[-0.055em]">{mode === 'login' ? '다시 만나서 반가워요' : '나만의 피드를 시작해요'}</h2>
            <p className="mt-2 text-sm text-muted-foreground">{mode === 'login' ? '로그인하고 오늘의 추천을 확인하세요.' : '몇 가지 정보만 입력하면 바로 취향을 찾기 시작합니다.'}</p>
          </div>

          <div className="space-y-4">
            {mode === 'signup' && (
              <label className="block text-sm font-bold">닉네임
                <input className="mt-2 h-12 w-full rounded-xl border border-border bg-white px-4 font-normal outline-none focus:border-[#7f5caf]" onChange={(event) => setNickname(event.target.value)} value={nickname} />
              </label>
            )}
            <label className="block text-sm font-bold">이메일
              <span className="mt-2 flex gap-2">
                <input className="h-12 min-w-0 flex-1 rounded-xl border border-border bg-white px-4 font-normal outline-none focus:border-[#7f5caf]" onChange={(event) => { setEmail(event.target.value); setChecked(false); }} type="email" value={email} />
                {mode === 'signup' && <Button className="h-12 shrink-0 rounded-xl" disabled={!email.includes('@')} onClick={() => setChecked(true)} variant={checked ? 'secondary' : 'outline'}>{checked ? <Check /> : '중복 확인'}</Button>}
              </span>
              {mode === 'signup' && checked && <span className="mt-1 block text-xs font-medium text-[#6e6e6e]">사용할 수 있는 이메일이에요.</span>}
            </label>
            <label className="block text-sm font-bold">비밀번호
              <input className="mt-2 h-12 w-full rounded-xl border border-border bg-white px-4 font-normal outline-none focus:border-[#7f5caf]" onChange={(event) => setPassword(event.target.value)} type="password" value={password} />
            </label>
            <Button className="h-12 w-full rounded-xl bg-[#2a2a2a] text-white hover:bg-[#414141]" disabled={!canSubmit} onClick={() => onContinue(mode === 'signup')}>{mode === 'login' ? '로그인' : '회원가입'}</Button>
          </div>

          <div className="mt-7 flex items-center gap-3 text-xs text-muted-foreground"><span className="h-px flex-1 bg-border" />또는<span className="h-px flex-1 bg-border" /></div>
          <Button className="mt-4 h-11 w-full rounded-xl" onClick={() => onContinue(true)} variant="outline"><Sparkles data-icon="inline-start" /> 데모 계정으로 시작하기</Button>
          <button className="mt-6 w-full text-center text-sm text-muted-foreground" onClick={() => { setMode(mode === 'login' ? 'signup' : 'login'); setChecked(false); }}>{mode === 'login' ? '처음이신가요? 회원가입' : '이미 계정이 있나요? 로그인'}</button>
        </div>
      </section>
    </main>
  );
}

function OnboardingScreen({ onDone }: { onDone: (genres: string[], moods: string[]) => void }) {
  const [step, setStep] = useState(0);
  const [genres, setGenres] = useState<string[]>([]);
  const [moods, setMoods] = useState<string[]>([]);
  const toggle = (value: string, setter: React.Dispatch<React.SetStateAction<string[]>>) => setter((current) => current.includes(value) ? current.filter((item) => item !== value) : [...current, value]);
  const selection = step === 1 ? genres : moods;
  const options = step === 1 ? genreOptions : moodOptions;

  const tutorials = [
    { icon: Waveform, title: '좋아한 순간을 남겨요', copy: '재생 중 마음에 든 구간을 선택하면 가장 강한 취향 신호로 저장됩니다.' },
    { icon: Sparkles, title: '이유가 있는 추천', copy: '곡 전체가 아닌 빌드업, 리듬, 보컬 변화가 닮은 순간부터 들려드려요.' },
    { icon: BarChart3, title: '취향이 쌓이는 Listening DNA', copy: '청취 행동이 쌓일수록 장르와 분위기, 선호 전개를 정교하게 보여줍니다.' },
  ];

  return (
    <main className="min-h-screen bg-background px-5 py-7 text-foreground" data-editorial>
      <header className="mx-auto flex max-w-5xl items-center justify-between"><Brand /><span className="text-xs font-bold text-muted-foreground">{step + 1} / 3</span></header>
      <div className="mx-auto mt-5 h-1.5 max-w-5xl overflow-hidden rounded-full bg-[#ececec]"><div className="h-full rounded-full bg-primary transition-all" style={{ width: `${((step + 1) / 3) * 100}%` }} /></div>
      <section className="mx-auto flex min-h-[calc(100vh-110px)] max-w-5xl flex-col justify-center py-10">
        {step === 0 ? (
          <>
            <div className="text-center"><p className="font-mono text-[10px] font-medium lowercase tracking-[.02em] text-[#728d00]">How it works</p><h1 className="mt-3 text-4xl font-black tracking-[-0.06em] sm:text-5xl">당신의 청취가 취향이 되는 방법</h1><p className="mx-auto mt-3 max-w-xl text-sm leading-6 text-muted-foreground">뮤피드의 핵심 기능을 빠르게 알아보고 첫 추천을 위한 취향을 선택해 주세요.</p></div>
            <div className="mt-10 grid gap-4 md:grid-cols-3">{tutorials.map(({ icon: Icon, title, copy }, index) => <article className="rounded-[1.7rem] border border-border bg-white p-6" key={title}><span className="grid size-11 place-items-center rounded-2xl bg-[#2a2a2a] text-[#ddff63]"><Icon /></span><span className="mt-8 block text-xs font-black text-[#7f5caf]">0{index + 1}</span><h2 className="mt-2 text-xl font-black tracking-[-0.04em]">{title}</h2><p className="mt-3 text-sm leading-6 text-muted-foreground">{copy}</p></article>)}</div>
          </>
        ) : (
          <>
            <div className="text-center"><p className="font-mono text-[10px] font-medium lowercase tracking-[.02em] text-[#728d00]">{step === 1 ? 'Favorite genres' : 'Favorite moods'}</p><h1 className="mt-3 text-4xl font-black tracking-[-0.06em]">{step === 1 ? '자주 듣는 장르가 무엇인가요?' : '어떤 분위기에 끌리나요?'}</h1><p className="mt-3 text-sm text-muted-foreground">복수 선택할 수 있어요.</p></div>
            <div className="mx-auto mt-10 grid w-full max-w-3xl grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4">{options.map((option, index) => {
              const active = selection.includes(option);
              return <button className={`relative min-h-28 rounded-2xl border p-4 text-left transition ${active ? 'border-[#2a2a2a] bg-[#2a2a2a] text-white shadow-lg' : 'border-border bg-white hover:-translate-y-0.5 hover:border-[#a9a9a9]'}`} key={option} onClick={() => toggle(option, step === 1 ? setGenres : setMoods)}><span className={`mb-6 grid size-8 place-items-center rounded-full text-xs font-black ${active ? 'bg-[#ddff63] text-black' : 'bg-muted'}`}>{active ? <Check className="size-4" /> : String(index + 1).padStart(2, '0')}</span><strong>{option}</strong></button>;
            })}</div>
          </>
        )}
        <div className="mx-auto mt-10 flex w-full max-w-3xl items-center justify-between"><Button className={step === 0 ? 'invisible' : ''} onClick={() => setStep((current) => current - 1)} variant="ghost"><ChevronLeft /> 이전</Button><Button className="h-11 rounded-full bg-[#2a2a2a] px-6 text-white" disabled={(step === 1 || step === 2) && selection.length === 0} onClick={() => step === 2 ? onDone(genres, moods) : setStep((current) => current + 1)}>{step === 2 ? '뮤피드 시작하기' : '다음'} <ChevronRight /></Button></div>
      </section>
    </main>
  );
}

function TrackCard({ track, isActive = false, isLiked, isPlaying = false, onLike, onPlay, onDetail, onDislike, progress = 0, compact = false }: { track: Track; isActive?: boolean; isLiked: boolean; isPlaying?: boolean; onLike: () => void; onPlay: () => void; onDetail: () => void; onDislike?: () => void; progress?: number; compact?: boolean }) {
  return (
    <article className={`group transition ${compact ? 'flex items-center gap-4 border-b border-border py-3' : ''} ${isActive ? 'text-foreground' : ''}`}>
      <div className={`relative shrink-0 ${compact ? 'size-20' : 'aspect-square w-full'}`}>
        <button aria-label={isPlaying ? `${track.title} 일시정지` : `${track.title} 재생`} className="relative h-full w-full overflow-hidden bg-muted" onClick={onPlay}>
          <img alt={`${track.title} 커버`} className={`h-full w-full object-cover transition duration-500 ${isActive ? 'scale-[1.02]' : 'group-hover:scale-[1.02]'}`} src={track.image} />
          <span className={`absolute inset-0 grid place-items-center bg-black/30 transition-opacity ${isActive ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'}`}>
            <span className={`grid place-items-center rounded-full bg-black/70 text-white shadow-lg backdrop-blur-sm ${compact ? 'size-9' : 'size-12'}`}>{isPlaying ? <Pause className="fill-current" /> : <Play className="ml-0.5 fill-current" />}</span>
          </span>
          {isPlaying && !compact && <span className="absolute bottom-3 left-3 flex items-center gap-2 rounded-full bg-black/70 px-3 py-1.5 text-[10px] font-bold text-white backdrop-blur-sm"><span className="flex h-3 items-end gap-0.5"><i className="h-2 w-0.5 animate-pulse rounded-full bg-[#e3a9bc]" /><i className="h-3 w-0.5 animate-pulse rounded-full bg-[#e3a9bc] [animation-delay:150ms]" /><i className="h-1.5 w-0.5 animate-pulse rounded-full bg-[#e3a9bc] [animation-delay:300ms]" /></span>재생 중</span>}
        </button>
        {onDislike && <button aria-label="관심없음" className={`absolute z-10 grid size-8 place-items-center rounded-full border border-black/10 bg-white/90 text-[#625e69] shadow-sm backdrop-blur-sm transition hover:bg-white hover:text-black ${compact ? 'right-2 top-2' : 'right-3 top-3'}`} onClick={onDislike}><X className="size-4" /></button>}
      </div>
      <div className={`${compact ? 'min-w-0 flex-1' : 'pb-1 pt-4'}`}>
        <p className="mb-2 font-mono text-[10px] font-medium lowercase tracking-[.02em] text-muted-foreground">{track.match}% match · {compact ? formatTime(track.duration) : `pick ${formatTime(track.moment)}`}</p>
        <div className="flex items-start justify-between gap-2"><button className="min-w-0 text-left" onClick={onDetail}><h3 className="truncate text-sm font-black">{track.title}</h3><p className="truncate text-xs text-muted-foreground">{track.artist}</p></button><button aria-label="좋아요" className={isLiked ? 'text-[#d0245c]' : 'text-muted-foreground'} onClick={onLike}><Heart className={`size-4 ${isLiked ? 'fill-current' : ''}`} /></button></div>
        {!compact && <p className="mt-3 truncate text-[10px] font-medium text-muted-foreground">{track.reason}</p>}
        <div className="mt-2 flex flex-wrap gap-1">{track.tags.slice(0, compact ? 2 : 3).map((tag) => <span className="rounded-full border border-[#d9d5de] bg-[#eeecf1] px-2 py-1 text-[10px] font-bold text-[#625e69]" key={tag}>{tag}</span>)}</div>
        <div className="relative mt-3 overflow-hidden border-t border-border"><span aria-hidden className="absolute left-0 top-0 z-10 h-0.5 bg-[#b96f88] transition-[width] duration-300" style={{ width: isActive ? `${Math.min(Math.max(progress, 0), 1) * 100}%` : '0%' }} /><Button className={`h-9 w-full justify-start border-0 bg-transparent px-0 text-[11px] shadow-none hover:bg-transparent ${isActive ? 'font-bold text-[#9f5871]' : 'text-foreground'}`} onClick={onPlay} variant="outline">{isPlaying ? <Pause className="fill-current" /> : <Play className="fill-current" />} {isPlaying ? '재생 중' : isActive ? '이어서 듣기' : '이 순간부터 듣기'}{isActive && <span className="ml-auto pr-1 font-mono text-[9px] font-semibold text-muted-foreground">{Math.round(progress * 100)}%</span>}</Button></div>
      </div>
    </article>
  );
}

function TrackDetailPage({ activeTrack, likedIds, onBack, onLike, onOpenChat, onOpenMoment, onPlay, onPlayMoment, onSelectTrack, onToggleRelated, playing, time, track }: { activeTrack: Track; likedIds: number[]; onBack: () => void; onLike: (id: number) => void; onOpenChat: () => void; onOpenMoment: (track: Track) => void; onPlay: (track: Track) => void; onPlayMoment: (track: Track) => void; onSelectTrack: (track: Track) => void; onToggleRelated: (track: Track) => void; playing: boolean; time: number; track: Track }) {
  const relatedTracks = tracks.filter((item) => item.id !== track.id).slice(0, 3);

  return (
    <div className="space-y-8">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <button className="flex items-center gap-2 text-sm font-bold text-muted-foreground transition hover:text-foreground" onClick={onBack}><ChevronLeft className="size-4" /> 이전 화면</button>
        <span className="font-mono text-[10px] font-medium lowercase tracking-[.02em] text-muted-foreground">track archive / {String(track.id).padStart(2, '0')}</span>
      </div>

      <section className="overflow-hidden border-y border-border bg-[#f5ecef]">
        <div className="grid lg:grid-cols-[minmax(320px,.9fr)_minmax(360px,1.1fr)]">
          <div className="relative min-h-[330px] overflow-hidden p-5 sm:min-h-[480px] sm:p-10">
            <span aria-hidden className="absolute left-[36%] top-1/2 aspect-square w-[65%] -translate-y-1/2 rounded-full mupid-vinyl" />
            <button className="group relative z-10 aspect-square w-full max-w-[410px] overflow-hidden bg-white p-1 shadow-[0_24px_70px_rgb(66_40_49/18%)]" onClick={() => onPlay(track)}>
              <img alt={`${track.title} 앨범 아트`} className="h-full w-full object-cover" src={track.image} />
              <span className="absolute inset-0 grid place-items-center bg-black/0 transition hover:bg-black/25"><span className="grid size-14 place-items-center rounded-full bg-black/70 text-white opacity-0 shadow-xl transition group-hover:opacity-100"><Play className="fill-current" /></span></span>
            </button>
          </div>

          <div className="flex flex-col justify-between border-t border-border p-7 sm:p-10 lg:border-l lg:border-t-0">
            <div>
              <div className="flex items-center justify-between border-b border-border pb-3 font-mono text-[10px] font-medium lowercase tracking-[.02em] text-muted-foreground"><span>{track.album}</span><span>{track.match}% match</span></div>
              <p className="mt-10 text-sm text-muted-foreground">{track.artist} · {track.genre}</p>
              <h1 className="mt-2 text-4xl font-black leading-[.92] tracking-[-.07em] sm:text-6xl">{track.title}</h1>
              <p className="mt-6 max-w-lg text-base leading-7 text-[#625e69]">{track.reason}. 좋아한 구간의 에너지 변화와 전개 방식이 자연스럽게 이어져요.</p>
              <div className="mt-5 flex flex-wrap gap-2">{track.tags.map((tag) => <span className="rounded-full border border-[#d9cbd1] bg-white/45 px-3 py-1.5 text-[10px] font-bold text-[#625e69]" key={tag}>{tag}</span>)}</div>
            </div>

            <div className="mt-10 space-y-3">
              <div className="grid grid-cols-[1fr_auto] gap-2 sm:grid-cols-[1fr_1fr_auto]">
                <Button className="col-span-2 h-12 rounded-full bg-[#202020] text-white hover:bg-black sm:col-span-1" onClick={() => onPlay(track)}>{playing && activeTrack.id === track.id ? <Pause className="fill-current" /> : <Play className="fill-current" />} {playing && activeTrack.id === track.id ? '재생 중' : '처음부터 재생'}</Button>
                <Button className="h-12 rounded-full border-0 bg-white/60 text-[#39294b] shadow-none hover:bg-white" onClick={() => onPlayMoment(track)}><Waveform /> {formatTime(track.moment)}부터</Button>
                <Button aria-label="좋아요" className={`size-12 rounded-full ${likedIds.includes(track.id) ? 'text-[#d0245c]' : ''}`} onClick={() => onLike(track.id)} size="icon-lg" variant="outline"><Heart className={likedIds.includes(track.id) ? 'fill-current' : ''} /></Button>
              </div>
              <Button className="h-12 w-full rounded-full border border-[#2a2a2a] bg-transparent text-[#2a2a2a] shadow-none hover:bg-white/45" onClick={onOpenChat} variant="outline"><MessageCircle /> 이 곡에서 AI로 이어 듣기</Button>
            </div>
          </div>
        </div>
      </section>

      <div className="grid gap-5 lg:grid-cols-[1.12fr_.88fr]">
        <section className="bg-[#292829] p-6 text-white sm:p-8">
          <p className="font-mono text-[10px] font-medium lowercase tracking-[.02em] text-[#ddff63]">Why this track?</p>
          <h2 className="mt-3 text-2xl font-black tracking-[-.04em]">{track.reason}</h2>
          <p className="mt-3 max-w-xl text-sm leading-6 text-white/50">좋아한 구간의 에너지 상승과 리듬 밀도가 89% 유사합니다. 특히 {formatTime(track.moment)} 전후의 {getSongSectionAt(track, track.moment).label}에서 취향 신호가 강하게 나타나요.</p>
          <div className="mt-6 flex flex-wrap gap-2">{['Slow build', track.genre, track.mood].map((chip) => <span className="rounded-full border border-white/10 bg-white/5 px-3 py-1.5 text-[10px]" key={chip}>{chip}</span>)}</div>
        </section>
        <section className="flex flex-col justify-between border border-border bg-card p-6 sm:p-8">
          <div><p className="font-mono text-[10px] font-medium lowercase tracking-[.02em] text-[#d0245c]">Save the moment</p><h2 className="mt-3 text-2xl font-black tracking-[-.04em]">좋아하는 부분만 남겨요</h2><p className="mt-3 text-sm leading-6 text-muted-foreground">송폼을 기준으로 구간을 고르고 시작과 끝을 직접 조절할 수 있어요.</p></div>
          <Button className="mt-8 h-13 w-full rounded-full bg-[#202020] text-white hover:bg-black" onClick={() => onOpenMoment(track)}><span className="grid size-7 place-items-center rounded-full bg-[#ff6b9d]"><Heart className="size-4 fill-current" /></span> 좋아하는 부분 선택하기</Button>
        </section>
      </div>

      <section>
        <div className="mb-4 flex items-end justify-between"><div><p className="font-mono text-[10px] font-medium lowercase tracking-[.02em] text-[#728d00]">DERIVED FROM THIS MOMENT</p><h2 className="mt-1 text-2xl font-black tracking-[-.045em]">이 구간과 유사한 음악</h2></div><button className="text-xs text-muted-foreground underline" onClick={onBack}>피드로 돌아가기</button></div>
        <div className="grid gap-x-6 md:grid-cols-3">{relatedTracks.map((item) => <TrackCard compact isActive={activeTrack.id === item.id} isLiked={likedIds.includes(item.id)} isPlaying={playing && activeTrack.id === item.id} key={item.id} onDetail={() => onSelectTrack(item)} onLike={() => onLike(item.id)} onPlay={() => onToggleRelated(item)} progress={activeTrack.id === item.id ? time / item.duration : 0} track={item} />)}</div>
      </section>
    </div>
  );
}

function EditorialHero({ track, onPlay, onDetail }: { track: Track; onPlay: () => void; onDetail: () => void }) {
  const queue = [track, ...tracks.filter((item) => item.id !== track.id)].slice(0, 6);

  return (
    <article className="border-y border-border bg-card/35 px-0 py-7 sm:px-7 sm:py-9">
      <div className="grid gap-8 lg:grid-cols-[minmax(260px,1.05fr)_minmax(220px,.85fr)_minmax(220px,.8fr)]">
        <div className="relative self-start">
          <span aria-hidden className="pointer-events-none absolute right-0 top-1/2 w-[67%] -translate-y-1/2">
            <span className="mupid-vinyl block aspect-square w-full rounded-full" />
          </span>
          <button className="relative aspect-square w-[74%] overflow-hidden bg-white p-1 shadow-[0_18px_50px_rgb(60_60_60/10%)]" onClick={onDetail}>
            <img alt={`${track.title} 앨범 아트`} className="h-full w-full object-cover" src={track.image} />
          </button>
        </div>
        <div className="flex flex-col justify-between py-1">
          <div className="flex items-center justify-between border-b border-border pb-3 font-mono text-[10px] font-medium lowercase tracking-[.02em] text-muted-foreground"><span>Today&apos;s moment</span><span>01 — 08</span></div>
          <div className="py-8">
            <p className="text-xs text-muted-foreground">{track.album} · {track.artist}</p>
            <h2 className="mt-3 text-4xl font-black leading-[.94] tracking-[-.065em] sm:text-5xl">{track.title}</h2>
            <p className="mt-4 max-w-sm text-sm leading-6 text-muted-foreground">{track.reason}. 최근 좋아한 순간과 에너지 상승 폭이 비슷해요.</p>
            <div className="mt-4 flex flex-wrap gap-1.5">{track.tags.map((tag) => <span className="rounded-full border border-[#d9d5de] bg-[#eeecf1] px-2.5 py-1 text-[11px] font-bold text-[#625e69]" key={tag}>{tag}</span>)}</div>
          </div>
          <button className="flex items-center justify-between border-y border-foreground py-3 text-left font-mono text-[10px] font-medium lowercase tracking-[.02em]" onClick={onPlay}><span>{formatTime(track.moment)}부터 듣기</span><Play className="size-4 fill-current" /></button>
        </div>
        <div className="border-l border-border pl-6">
          <div className="flex items-center justify-between border-b border-border pb-3 font-mono text-[10px] font-medium lowercase tracking-[.02em] text-muted-foreground"><span>Next in feed</span><span>{queue.length} tracks</span></div>
          <ol>{queue.map((item, index) => <li className="grid grid-cols-[24px_minmax(0,1fr)_auto] items-center gap-2 border-b border-border py-3" key={item.id}><span className="text-[9px] text-muted-foreground">{String(index + 1).padStart(2, '0')}</span><button className="min-w-0 text-left" onClick={item.id === track.id ? onDetail : undefined}><strong className="block truncate text-[11px]">{item.title}</strong><small className="block truncate text-[9px] text-muted-foreground">{item.artist}</small></button><span className="text-[9px] text-muted-foreground">{formatTime(item.duration)}</span></li>)}</ol>
        </div>
      </div>
    </article>
  );
}

const energyCurve = [
  { at: 0, energy: 18 },
  { at: 12, energy: 24 },
  { at: 24, energy: 33 },
  { at: 36, energy: 45 },
  { at: 48, energy: 59 },
  { at: 60, energy: 78 },
  { at: 70, energy: 94 },
  { at: 80, energy: 76 },
  { at: 90, energy: 51 },
  { at: 100, energy: 33 },
];

const genreAffinity = [
  { genre: 'Electronic', score: 82 },
  { genre: 'Indie', score: 68 },
  { genre: 'R&B', score: 52 },
  { genre: 'Rock', score: 34 },
];

const trackListenCounts = [
  { track: tracks[0], plays: 18 },
  { track: tracks[2], plays: 13 },
  { track: tracks[1], plays: 9 },
  { track: tracks[5], plays: 7 },
  { track: tracks[4], plays: 5 },
  { track: tracks[7], plays: 3 },
].map(({ track, plays }) => ({ title: track.title, artist: track.artist, tags: track.tags, plays }));

const moodBase: Record<string, number> = {
  잔잔한: 62,
  벅찬: 47,
  몽환적인: 71,
  신나는: 39,
  집중되는: 55,
  감성적인: 66,
};

function DnaView({ nickname, moods, savedMoments, onPlayMoment }: { nickname: string; moods: string[]; savedMoments: SavedMoment[]; onPlayMoment: (moment: SavedMoment) => void }) {
  const moodData = Object.keys(moodBase).map((mood) => ({ mood, score: Math.min(100, moodBase[mood] + (moods.includes(mood) ? 24 : 0)) }));
  const peak = energyCurve.reduce((best, point) => (point.energy > best.energy ? point : best), energyCurve[0]);
  const topGenre = genreAffinity[0];
  const topMood = moodData.reduce((best, item) => (item.score > best.score ? item : best), moodData[0]);
  const totalPlays = trackListenCounts.reduce((total, item) => total + item.plays, 0);
  const topPlayedTrack = trackListenCounts[0];
  const repeatFavorites = trackListenCounts.filter((item) => item.plays >= 5).length;

  return (
    <div className="space-y-7">
      <div>
        <p className="font-mono text-[11px] font-medium lowercase tracking-[.02em] text-[#728d00]">LISTENING DNA</p>
        <h1 className="mt-1 text-3xl font-black tracking-[-.055em]">{nickname}님의 음악 취향</h1>
        <p className="mt-2 text-sm text-muted-foreground">좋아요, 반복 재생, 저장한 구간을 함께 분석했어요.</p>
      </div>

      <section className="grid gap-5 lg:grid-cols-[.85fr_1.15fr]">
        <article className="relative overflow-hidden rounded-[2rem] bg-[#2a2a2a] p-7 text-white sm:p-9">
          <div className="absolute -right-20 -top-20 size-72 rounded-full bg-white/5 blur-3xl" />
          <div className="relative">
            <p className="font-mono text-[10px] font-medium lowercase tracking-[.02em] text-white/45">Your persona</p>
            <h2 className="mt-3 text-4xl font-black tracking-[-.06em]">시네마틱 몰입자</h2>
            <p className="mt-4 max-w-lg text-sm leading-6 text-white/55">잔잔한 상태에서 긴장감이 천천히 쌓이다, 사운드가 크게 확장되는 순간에 자주 반응합니다.</p>
            <div className="mt-10 grid grid-cols-3 gap-3">
              {[['Slow build', '41%'], ['Wide release', '28%'], ['Deep rhythm', '19%']].map(([label, value]) => (
                <div className="rounded-2xl border border-white/10 bg-white/5 p-4" key={label}>
                  <strong className="block text-2xl">{value}</strong>
                  <span className="mt-1 block font-mono text-[10px] font-medium lowercase tracking-[.02em] text-white/45">{label}</span>
                </div>
              ))}
            </div>
          </div>
        </article>

        <article className="rounded-[2rem] border border-border bg-card p-6 sm:p-7">
          <p className="font-mono text-[10px] font-medium lowercase tracking-[.02em] text-muted-foreground">Energy shape</p>
          <h2 className="mt-2 text-xl font-black">좋아한 구간의 에너지 흐름</h2>
          <p className="mt-1 text-sm text-muted-foreground">저장한 구간을 곡 길이 기준으로 겹쳐 평균 낸 곡선이에요. 재생 <strong className="font-bold text-foreground">{peak.at}%</strong> 지점에서 가장 크게 반응합니다.</p>
          <ChartContainer className="mt-5 aspect-auto h-52 w-full" config={{ energy: { label: '에너지', color: 'var(--foreground)' } }}>
            <AreaChart data={energyCurve} margin={{ left: -18, right: 6, top: 6 }}>
              <defs>
                <linearGradient id="mupid-energy-fill" x1="0" x2="0" y1="0" y2="1">
                  <stop offset="0%" stopColor="var(--color-energy)" stopOpacity={0.2} />
                  <stop offset="100%" stopColor="var(--color-energy)" stopOpacity={0.02} />
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="2 5" vertical={false} />
              <XAxis axisLine={false} dataKey="at" tickFormatter={(value: number) => `${value}%`} tickLine={false} tickMargin={10} />
              <YAxis axisLine={false} domain={[0, 100]} tickLine={false} width={44} />
              <ChartTooltip content={<ChartTooltipContent labelFormatter={(value) => `재생 ${value}% 지점`} />} cursor />
              <Area dataKey="energy" fill="url(#mupid-energy-fill)" stroke="var(--color-energy)" strokeWidth={2} type="monotone" />
            </AreaChart>
          </ChartContainer>
          <div className="mt-3 flex justify-between pl-[26px] pr-1 font-mono text-[10px] font-medium lowercase tracking-[.02em] text-muted-foreground"><span>도입</span><span>상승</span><span>폭발</span><span>감쇄</span></div>
        </article>
      </section>

      <section className="grid gap-5 md:grid-cols-2">
        <article className="rounded-[1.7rem] border border-border bg-card p-6">
          <p className="font-mono text-[10px] font-medium lowercase tracking-[.02em] text-muted-foreground">Favorite genres</p>
          <h2 className="mt-2 text-xl font-black">선호 장르</h2>
          <p className="mt-1 text-sm text-muted-foreground"><strong className="font-bold text-foreground">{topGenre.genre}</strong>가 {topGenre.score}%로 가장 높아요.</p>
          <ChartContainer className="mt-5 aspect-auto h-52 w-full" config={{ score: { label: '선호도', color: 'var(--foreground)' } }}>
            <BarChart data={genreAffinity} layout="vertical" margin={{ left: 4, right: 44 }}>
              <CartesianGrid horizontal={false} strokeDasharray="2 5" />
              <XAxis dataKey="score" domain={[0, 100]} hide type="number" />
              <YAxis axisLine={false} dataKey="genre" tickLine={false} tickMargin={6} type="category" width={82} />
              <ChartTooltip content={<ChartTooltipContent hideLabel />} cursor={false} />
              <Bar barSize={16} dataKey="score" fill="var(--color-score)" radius={[0, 4, 4, 0]}>
                <LabelList className="fill-foreground" dataKey="score" formatter={(value: unknown) => String(value) + '%'} offset={10} position="right" />
              </Bar>
            </BarChart>
          </ChartContainer>
        </article>

        <article className="rounded-[1.7rem] border border-border bg-card p-6">
          <p className="font-mono text-[10px] font-medium lowercase tracking-[.02em] text-muted-foreground">Favorite moods</p>
          <h2 className="mt-2 text-xl font-black">선호 분위기</h2>
          <p className="mt-1 text-sm text-muted-foreground"><strong className="font-bold text-foreground">{topMood.mood}</strong> 분위기에 가장 강하게 반응해요.</p>
          <ChartContainer className="mt-2 aspect-auto h-56 w-full" config={{ score: { label: '반응도', color: 'var(--foreground)' } }}>
            <RadarChart data={moodData} outerRadius="70%">
              <PolarGrid />
              <PolarAngleAxis dataKey="mood" />
              <ChartTooltip content={<ChartTooltipContent hideLabel />} cursor={false} />
              <Radar dataKey="score" dot={{ fillOpacity: 1, r: 4 }} fill="var(--color-score)" fillOpacity={0.16} stroke="var(--color-score)" strokeWidth={2} />
            </RadarChart>
          </ChartContainer>
        </article>
      </section>

      <article className="rounded-[1.7rem] border border-border bg-card p-6 sm:p-7">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <p className="font-mono text-[10px] font-medium lowercase tracking-[.02em] text-muted-foreground">Plays per track · last 7 days</p>
            <h2 className="mt-2 text-xl font-black">곡별 반복 청취</h2>
            <p className="mt-1 text-sm text-muted-foreground">최근 7일 동안 곡마다 몇 번 들었는지 비교했어요. <strong className="font-bold text-foreground">{topPlayedTrack.title}</strong>를 가장 자주 반복했습니다.</p>
          </div>
          <span className="inline-flex items-center gap-2 rounded-full bg-[#fff0f5] px-3 py-2 text-xs font-bold text-[#d0245c]"><Repeat2 className="size-4" /> 최다 {topPlayedTrack.plays}회</span>
        </div>

        <div className="mt-6 grid gap-5 lg:grid-cols-[minmax(0,1fr)_220px]">
          <div className="min-w-0 rounded-2xl bg-muted/45 p-3 sm:p-4">
            <ChartContainer className="aspect-auto h-64 w-full" config={{ plays: { label: '재생 횟수', color: '#ff6b9d' } }}>
              <BarChart data={trackListenCounts} layout="vertical" margin={{ left: 8, right: 46, top: 8, bottom: 8 }}>
                <CartesianGrid horizontal={false} strokeDasharray="2 5" />
                <XAxis dataKey="plays" domain={[0, 20]} hide type="number" />
                <YAxis axisLine={false} dataKey="title" tickLine={false} tickMargin={8} type="category" width={116} />
                <ChartTooltip content={<ChartTooltipContent />} cursor={{ fill: 'rgb(255 107 157 / 8%)' }} />
                <Bar barSize={18} dataKey="plays" fill="var(--color-plays)" radius={[0, 6, 6, 0]}>
                  <LabelList className="fill-foreground" dataKey="plays" formatter={(value: unknown) => `${String(value)}회`} offset={10} position="right" />
                </Bar>
              </BarChart>
            </ChartContainer>
          </div>

          <div className="grid gap-2 sm:grid-cols-3 lg:grid-cols-1">
            <div className="rounded-2xl border border-border p-4"><span className="text-[10px] font-bold text-muted-foreground">가장 많이 들은 곡</span><strong className="mt-1 block truncate text-base tracking-[-.03em]">{topPlayedTrack.title}</strong><small className="text-muted-foreground">{topPlayedTrack.plays}회 재생</small></div>
            <div className="rounded-2xl border border-border p-4"><span className="text-[10px] font-bold text-muted-foreground">반복해서 들은 곡</span><strong className="mt-1 block text-xl tracking-[-.03em]">{repeatFavorites}곡</strong><small className="text-muted-foreground">5회 이상 재생</small></div>
            <div className="rounded-2xl border border-border p-4"><span className="text-[10px] font-bold text-muted-foreground">최근 7일 총 재생</span><strong className="mt-1 block text-xl tracking-[-.03em]">{totalPlays}회</strong><small className="text-muted-foreground">하루 평균 {Math.round(totalPlays / 7)}회</small></div>
          </div>
        </div>
        <div className="mt-5 border-t border-border pt-5">
          <p className="mb-3 text-xs font-black text-muted-foreground">자주 들은 곡의 장르·감성 스타일</p>
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {trackListenCounts.map((item) => <div className="rounded-xl bg-muted/55 px-3 py-2.5" key={item.title}><strong className="block truncate text-xs">{item.title}</strong><div className="mt-1.5 flex flex-wrap gap-1">{item.tags.map((tag) => <span className="rounded-full border border-[#d9d5de] bg-[#eeecf1] px-2 py-1 text-[10px] font-bold text-[#625e69]" key={tag}>{tag}</span>)}</div></div>)}
          </div>
        </div>
      </article>

      <article className="rounded-[1.7rem] border border-border bg-card p-6">
        <p className="font-mono text-[10px] font-medium lowercase tracking-[.02em] text-muted-foreground">Moment patterns</p>
        <h2 className="mt-2 text-xl font-black">좋아요를 남긴 구간</h2>
        <p className="mt-1 text-sm text-muted-foreground">각 곡의 어느 지점에 좋아요를 남겼는지 곡 길이 기준으로 표시했어요.</p>
        <div className="mt-5 space-y-4">
          {savedMoments.slice(0, 4).map((moment) => {
            const start = (moment.start / moment.track.duration) * 100;
            const width = Math.max(2, ((moment.end - moment.start) / moment.track.duration) * 100);
            return (
              <button aria-label={`${moment.track.title} ${formatTime(moment.start)}부터 듣기`} className="block w-full text-left" key={moment.id} onClick={() => onPlayMoment(moment)}>
                <div className="flex items-baseline justify-between gap-3">
                  <strong className="truncate text-sm">{moment.track.title}</strong>
                  <span className="shrink-0 font-mono text-[10px] font-medium tracking-[.02em] text-muted-foreground">{moment.section} · {formatTime(moment.start)}–{formatTime(moment.end)}</span>
                </div>
                <div className="relative mt-2 h-2 w-full rounded-full bg-muted">
                  <span className="absolute inset-y-0 rounded-full bg-foreground" style={{ left: `${start}%`, width: `${width}%` }} />
                </div>
              </button>
            );
          })}
          {!savedMoments.length && <p className="py-8 text-center text-sm text-muted-foreground">아직 저장한 구간이 없어요.</p>}
        </div>
      </article>
    </div>
  );
}

export function MupidApp() {
  const audioRef = useRef<HTMLAudioElement>(null);
  const chatMessageIdRef = useRef(2);
  const [stage, setStage] = useState<Stage>('auth');
  const [view, setView] = useState<View>('home');
  const [nickname, setNickname] = useState('뮤피더');
  const [genres, setGenres] = useState<string[]>(['Electronic', 'Indie']);
  const [moods, setMoods] = useState<string[]>(['몽환적인', '벅찬']);
  const [activeTrack, setActiveTrack] = useState(tracks[0]);
  const [playing, setPlaying] = useState(false);
  const [time, setTime] = useState(tracks[0].moment);
  const [volume, setVolume] = useState(0.75);
  const [likedIds, setLikedIds] = useState<number[]>([1, 3]);
  const [ignoredIds, setIgnoredIds] = useState<number[]>([]);
  const [historyIds, setHistoryIds] = useState<number[]>([1, 3, 5]);
  const [savedMoments, setSavedMoments] = useState<SavedMoment[]>([{ id: 1, track: tracks[0], start: 70, end: 92, section: '코러스 2' }]);
  const [playlists, setPlaylists] = useState<Playlist[]>([{ id: 1, name: '새벽 드라이브', tracks: [tracks[0], tracks[1], tracks[5]] }, { id: 2, name: '집중의 순간', tracks: [tracks[2], tracks[7]] }]);
  const [selectedPlaylist, setSelectedPlaylist] = useState(1);
  const [playlistName, setPlaylistName] = useState('');
  const [showPlaylistForm, setShowPlaylistForm] = useState(false);
  const [detailTrack, setDetailTrack] = useState<Track | null>(null);
  const [showMoment, setShowMoment] = useState(false);
  const [momentTrack, setMomentTrack] = useState<Track>(tracks[0]);
  const [momentStart, setMomentStart] = useState(70);
  const [momentEnd, setMomentEnd] = useState(92);
  const [momentAnchor, setMomentAnchor] = useState(72);
  const [momentPlayhead, setMomentPlayhead] = useState(72);
  const [momentFormLabel, setMomentFormLabel] = useState('코러스 2');
  const [recommendationOffset, setRecommendationOffset] = useState(0);
  const [query, setQuery] = useState('');
  const [searchTriggered, setSearchTriggered] = useState(false);
  const [recentSearches, setRecentSearches] = useState(['LUME', '몽환적인', 'Slow build']);
  const [chatInput, setChatInput] = useState('');
  const [chatOpen, setChatOpen] = useState(false);
  const [chatLoading, setChatLoading] = useState(false);
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([
    { id: 1, role: 'assistant', text: '지금 듣고 있는 곡을 기준으로 다음 음악을 함께 찾아볼게요. 분위기, 에너지, 보컬, 리듬 중 원하는 변화를 말해 주세요.' },
  ]);
  const [toast, setToast] = useState('');
  const [profileTab, setProfileTab] = useState<'liked' | 'moments' | 'history'>('liked');

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(''), 2600);
    return () => window.clearTimeout(timer);
  }, [toast]);

  useEffect(() => {
    if (detailTrack) window.scrollTo({ top: 0, behavior: 'smooth' });
  }, [detailTrack]);

  useEffect(() => {
    const audio = audioRef.current;
    if (!audio || !showMoment || !playing || activeTrack.id !== momentTrack.id || momentPlayhead < momentEnd) return;
    audio.pause();
    audio.currentTime = momentStart;
    setTime(momentStart);
    setMomentPlayhead(momentStart);
  }, [activeTrack.id, momentEnd, momentPlayhead, momentStart, momentTrack.id, playing, showMoment]);

  const availableTracks = tracks.filter((track) => !ignoredIds.includes(track.id));
  const recommendations = [...availableTracks.slice(1), availableTracks[0]].map((_, index, list) => list[(index + recommendationOffset) % list.length]).slice(0, 4);
  const searchResults = useMemo(() => {
    if (!query.trim()) return availableTracks;
    const normalized = query.toLowerCase();
    return availableTracks.filter((track) => `${track.title} ${track.artist} ${track.genre} ${track.mood} ${track.tags.join(' ')}`.toLowerCase().includes(normalized));
  }, [availableTracks, query]);

  const submitChatPrompt = (prompt = chatInput) => {
    const request = prompt.trim();
    if (!request || chatLoading) return;
    const referenceTrack = activeTrack;
    const requestId = chatMessageIdRef.current++;
    const responseId = chatMessageIdRef.current++;
    setChatMessages((current) => [...current, { id: requestId, role: 'user', text: request }]);
    setChatInput('');
    setChatLoading(true);
    window.setTimeout(() => {
      const recommendation = deriveChatRecommendations(request, referenceTrack, availableTracks);
      setChatMessages((current) => [...current, { id: responseId, role: 'assistant', text: recommendation.text, tracks: recommendation.tracks }]);
      setChatLoading(false);
    }, 650);
  };

  const playTrack = async (track: Track, fromMoment = false) => {
    const audio = audioRef.current;
    if (!audio) return;
    if (activeTrack.id !== track.id) { setActiveTrack(track); audio.src = track.audio; audio.load(); }
    const start = fromMoment ? track.moment : 0;
    audio.currentTime = start;
    setTime(start);
    setHistoryIds((current) => [track.id, ...current.filter((id) => id !== track.id)].slice(0, 8));
    try { await audio.play(); setPlaying(true); } catch { setToast('재생을 시작하지 못했어요. 다시 눌러주세요.'); }
  };

  const togglePlay = async () => {
    const audio = audioRef.current;
    if (!audio) return;
    if (playing) { audio.pause(); setPlaying(false); }
    else {
      if (audio.currentTime >= activeTrack.duration) { audio.currentTime = 0; setTime(0); }
      try { await audio.play(); setPlaying(true); } catch { setToast('재생 버튼을 다시 눌러주세요.'); }
    }
  };

  const toggleTrackPlayback = async (track: Track, fromMoment = false) => {
    if (activeTrack.id === track.id) {
      await togglePlay();
      return;
    }
    await playTrack(track, fromMoment);
  };

  const toggleLike = (id: number) => setLikedIds((current) => current.includes(id) ? current.filter((item) => item !== id) : [...current, id]);
  const openMoment = (track: Track = activeTrack, at?: number) => {
    const clickedAt = Math.min(track.duration, Math.max(0, Math.floor(at ?? (track.id === activeTrack.id ? time : track.moment))));
    const songSection = getSongSectionAt(track, clickedAt);
    setMomentTrack(track);
    setMomentStart(songSection.start);
    setMomentEnd(songSection.end);
    setMomentAnchor(clickedAt);
    setMomentPlayhead(clickedAt);
    setMomentFormLabel(songSection.label);
    setShowMoment(true);
  };
  const seekMoment = (nextTime: number) => {
    setMomentPlayhead(nextTime);
    if (activeTrack.id !== momentTrack.id || !audioRef.current) return;
    audioRef.current.currentTime = nextTime;
    setTime(nextTime);
  };
  const previewMoment = async () => {
    const audio = audioRef.current;
    if (!audio) return;
    if (playing && activeTrack.id === momentTrack.id && momentPlayhead >= momentStart && momentPlayhead <= momentEnd) {
      audio.pause();
      return;
    }
    if (activeTrack.id !== momentTrack.id) {
      setActiveTrack(momentTrack);
      audio.src = momentTrack.audio;
      audio.load();
    }
    audio.currentTime = momentStart;
    setTime(momentStart);
    setMomentPlayhead(momentStart);
    try { await audio.play(); setPlaying(true); } catch { setToast('미리듣기를 시작하지 못했어요. 다시 눌러주세요.'); }
  };
  const syncPlaybackTime = (audio: HTMLAudioElement) => {
    const nextTime = Math.min(audio.currentTime, activeTrack.duration);
    setTime(nextTime);
    if (showMoment && activeTrack.id === momentTrack.id) setMomentPlayhead(nextTime);
    if (audio.currentTime >= activeTrack.duration) {
      audio.pause();
      setPlaying(false);
    }
  };
  const saveMoment = () => { setSavedMoments((current) => [...current, { id: Date.now(), track: momentTrack, start: momentStart, end: momentEnd, section: momentFormLabel }]); setLikedIds((current) => current.includes(momentTrack.id) ? current : [...current, momentTrack.id]); setShowMoment(false); setToast(`${momentTrack.title}의 ${momentFormLabel} 구간을 저장했어요.`); };
  const findTrack = (id: number) => tracks.find((track) => track.id === id)!;

  if (stage === 'auth') return <AuthScreen onContinue={(needsOnboarding) => setStage(needsOnboarding ? 'onboarding' : 'app')} />;
  if (stage === 'onboarding') return <OnboardingScreen onDone={(nextGenres, nextMoods) => { setGenres(nextGenres); setMoods(nextMoods); setStage('app'); }} />;

  const navItems: Array<{ id: View; label: string; icon: typeof Home }> = [
    { id: 'home', label: '홈', icon: Home }, { id: 'search', label: '검색', icon: Search }, { id: 'playlist', label: '플레이리스트', icon: Library }, { id: 'dna', label: '취향 분석', icon: BarChart3 }, { id: 'profile', label: '마이페이지', icon: UserRound },
  ];

  return (
    <main className="min-h-screen overflow-x-hidden bg-background pb-44 text-foreground lg:pb-28" data-editorial>
      <audio onEnded={() => setPlaying(false)} onPause={() => setPlaying(false)} onPlay={() => setPlaying(true)} onTimeUpdate={(event) => syncPlaybackTime(event.currentTarget)} preload="metadata" ref={audioRef} src={activeTrack.audio} />

      <header className="sticky top-0 z-30 border-b border-border bg-background/95 backdrop-blur-xl">
        <div className="mx-auto flex h-16 max-w-[1480px] items-center gap-3 px-3 sm:h-18 sm:gap-5 sm:px-7"><button onClick={() => { setDetailTrack(null); setView('home'); }}><Brand /></button><span className="hidden font-mono text-[10px] font-medium lowercase tracking-[.02em] text-muted-foreground sm:block">listening archive / 2026</span><div className="relative ml-auto hidden w-full max-w-sm md:block"><Search className="absolute left-0 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" /><input aria-label="음악 검색" className="h-10 w-full border-0 border-b border-border bg-transparent pl-7 pr-2 text-sm outline-none focus:border-foreground" onChange={(event) => { setQuery(event.target.value); setSearchTriggered(false); }} onFocus={() => { setDetailTrack(null); setView('search'); }} placeholder="곡, 아티스트를 검색하세요" value={query} /></div><Button className="ml-auto hidden rounded-none border-0 border-l border-border bg-transparent font-mono text-[10px] font-medium lowercase tracking-[.02em] shadow-none sm:inline-flex md:ml-0" onClick={() => { setDetailTrack(null); setView('dna'); }} variant="outline"><Sparkles /> 내 취향 보기</Button><button aria-label="마이페이지" className="grid size-9 shrink-0 place-items-center rounded-full bg-[#333333] text-xs font-bold text-white" onClick={() => { setDetailTrack(null); setView('profile'); }}>MY</button></div>
      </header>

      <div className={`mx-auto grid max-w-[1480px] grid-cols-1 gap-10 px-4 py-8 sm:px-7 ${detailTrack ? 'lg:grid-cols-1' : 'lg:grid-cols-[180px_minmax(0,1fr)]'}`}>
        {!detailTrack && <aside className="hidden lg:block"><nav className="sticky top-24 space-y-1">{navItems.map(({ id, label, icon: Icon }, index) => <button className={`flex w-full items-center gap-3 border-l px-3 py-2.5 text-sm transition ${view === id ? 'border-foreground font-black text-foreground' : 'border-transparent text-muted-foreground hover:border-border hover:text-foreground'}`} key={id} onClick={() => { setDetailTrack(null); setView(id); }}><span className="w-5 text-[9px]">0{index + 1}</span><Icon className="size-4" />{label}</button>)}<div className="mt-7 border-t border-border pt-5"><p className="mb-2 px-3 font-mono text-[10px] font-medium lowercase tracking-[.02em] text-muted-foreground">My playlist</p>{playlists.map((playlist) => <button className="block w-full truncate border-l border-transparent px-3 py-2 text-left text-xs text-muted-foreground hover:border-border hover:text-foreground" key={playlist.id} onClick={() => { setDetailTrack(null); setSelectedPlaylist(playlist.id); setView('playlist'); }}>{playlist.name}</button>)}</div></nav></aside>}

        <section className="min-w-0">
          {detailTrack && <TrackDetailPage activeTrack={activeTrack} likedIds={likedIds} onBack={() => setDetailTrack(null)} onLike={toggleLike} onOpenChat={() => setChatOpen(true)} onOpenMoment={(track) => { void playTrack(track, true); openMoment(track, track.moment); }} onPlay={(track) => { void toggleTrackPlayback(track); }} onPlayMoment={(track) => { void toggleTrackPlayback(track, true); }} onSelectTrack={setDetailTrack} onToggleRelated={(track) => { void toggleTrackPlayback(track, true); }} playing={playing} time={time} track={detailTrack} />}

          {!detailTrack && view === 'home' && (
            <div className="space-y-9">
              <div className="flex flex-wrap items-end justify-between gap-4"><div><p className="mb-1 font-mono text-[11px] font-medium lowercase tracking-[.02em] text-[#728d00]">FOR {nickname.toUpperCase()}</p><h1 className="text-3xl font-black tracking-[-.055em] sm:text-4xl">오늘, 이 순간부터</h1><p className="mt-2 text-sm text-muted-foreground">최근 반복한 구간과 {genres.slice(0, 2).join(' · ')} 취향을 반영했어요.</p></div><Button className="rounded-full" onClick={() => { setRecommendationOffset((value) => (value + 2) % tracks.length); setToast('새로운 추천 목록을 준비했어요.'); }} variant="outline"><RefreshCw /> 새로고침</Button></div>
              <EditorialHero onDetail={() => setDetailTrack(recommendations[0])} onPlay={() => playTrack(recommendations[0], true)} track={recommendations[0]} />
              <section><div className="mb-4 flex items-center justify-between"><div><h2 className="text-xl font-black tracking-[-.04em]">개인화 추천</h2><p className="mt-1 text-sm text-muted-foreground">행동과 취향을 기반으로 고른 음악이에요.</p></div></div><div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">{recommendations.map((track) => <TrackCard isActive={activeTrack.id === track.id} isLiked={likedIds.includes(track.id)} isPlaying={playing && activeTrack.id === track.id} key={track.id} onDetail={() => setDetailTrack(track)} onDislike={() => { setIgnoredIds((current) => [...current, track.id]); setToast('이 곡을 다음 추천에서 제외할게요.'); }} onLike={() => toggleLike(track.id)} onPlay={() => toggleTrackPlayback(track, true)} progress={activeTrack.id === track.id ? time / track.duration : 0} track={track} />)}</div></section>
              <section><div className="mb-4 flex items-center justify-between"><div><h2 className="text-xl font-black tracking-[-.04em]">최근 들은 음악</h2><p className="mt-1 text-sm text-muted-foreground">다시 듣고 싶은 순간을 이어가세요.</p></div><Button variant="ghost" onClick={() => { setView('profile'); setProfileTab('history'); }}>전체 기록</Button></div><div className="grid gap-3 md:grid-cols-3">{historyIds.slice(0, 3).map((id) => { const track = findTrack(id); return <TrackCard compact isActive={activeTrack.id === track.id} isLiked={likedIds.includes(id)} isPlaying={playing && activeTrack.id === track.id} key={id} onDetail={() => setDetailTrack(track)} onLike={() => toggleLike(id)} onPlay={() => toggleTrackPlayback(track)} progress={activeTrack.id === track.id ? time / track.duration : 0} track={track} />; })}</div></section>
              <section><h2 className="text-xl font-black tracking-[-.04em]">취향 기반 카테고리</h2><div className="mt-4 grid gap-3 sm:grid-cols-3">{[['느리게 쌓이는 밤', 'Slow build · 18 tracks', '#2a2a2a', '#ddff63'], ['벅차게 열리는 순간', 'Wide release · 24 tracks', '#e9dfff', '#2e2e2e'], ['집중을 위한 그루브', 'Deep rhythm · 16 tracks', '#e7e7e7', '#2e2e2e']].map(([title, copy, bg, color]) => <button className="min-h-36 rounded-[1.5rem] p-5 text-left transition hover:-translate-y-1" key={title} onClick={() => { setView('search'); setQuery(title.split(' ')[0]); setSearchTriggered(true); }} style={{ background: bg, color }}><Disc3 className="mb-8" /><strong className="block text-lg tracking-[-.03em]">{title}</strong><span className="mt-1 block text-xs opacity-60">{copy}</span></button>)}</div></section>
            </div>
          )}

          {!detailTrack && view === 'search' && (
            <div className="space-y-6"><div><p className="font-mono text-[11px] font-medium lowercase tracking-[.02em] text-[#728d00]">DISCOVER</p><h1 className="mt-1 text-3xl font-black tracking-[-.055em]">찾고 싶은 음악이 있나요?</h1></div><div className="rounded-[1.7rem] border border-border bg-card p-4 sm:p-6"><div className="pt-1"><form className="flex gap-2" onSubmit={(event) => { event.preventDefault(); setSearchTriggered(true); if (query.trim()) setRecentSearches((current) => [query, ...current.filter((item) => item !== query)].slice(0, 6)); }}><div className="relative flex-1"><Search className="absolute left-4 top-1/2 size-5 -translate-y-1/2 text-muted-foreground" /><input autoFocus className="h-13 w-full rounded-xl border border-border bg-background pl-12 pr-4 outline-none focus:border-[#7f5caf]" onChange={(event) => setQuery(event.target.value)} placeholder="곡명 또는 아티스트명을 입력하세요" value={query} /></div><Button className="h-13 rounded-xl px-5" type="submit">검색</Button></form><div className="mt-4 flex flex-wrap items-center gap-2"><span className="mr-1 text-xs font-bold text-muted-foreground">최근 검색어</span>{recentSearches.map((item) => <span className="flex items-center gap-1 rounded-full bg-muted px-3 py-1.5 text-xs" key={item}><button onClick={() => { setQuery(item); setSearchTriggered(true); }}>{item}</button><button aria-label={`${item} 삭제`} onClick={() => setRecentSearches((current) => current.filter((value) => value !== item))}><X className="size-3" /></button></span>)}{recentSearches.length > 0 && <button className="text-xs text-muted-foreground underline" onClick={() => setRecentSearches([])}>전체 삭제</button>}</div></div></div>{searchTriggered && <section><div className="mb-4 flex items-end justify-between"><div><h2 className="text-xl font-black tracking-[-.04em]">검색 결과</h2><p className="mt-1 text-sm text-muted-foreground">{searchResults.length}개의 음악을 찾았어요.</p></div></div>{searchResults.length ? <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">{searchResults.map((track) => <TrackCard isActive={activeTrack.id === track.id} isLiked={likedIds.includes(track.id)} isPlaying={playing && activeTrack.id === track.id} key={track.id} onDetail={() => setDetailTrack(track)} onLike={() => toggleLike(track.id)} onPlay={() => toggleTrackPlayback(track)} progress={activeTrack.id === track.id ? time / track.duration : 0} track={track} />)}</div> : <div className="rounded-2xl border border-dashed border-border py-16 text-center"><Search className="mx-auto mb-3 text-muted-foreground" /><p className="font-bold">검색 결과가 없어요.</p><button className="mt-1 text-sm text-muted-foreground underline" onClick={() => setQuery('')}>검색어 지우기</button></div>}</section>}</div>
          )}

          {!detailTrack && view === 'playlist' && (
            <div><div className="flex items-end justify-between"><div><p className="font-mono text-[11px] font-medium lowercase tracking-[.02em] text-[#728d00]">MY COLLECTION</p><h1 className="mt-1 text-3xl font-black tracking-[-.055em]">플레이리스트</h1></div><Button className="rounded-full" onClick={() => setShowPlaylistForm(true)}><Plus /> 새 플레이리스트</Button></div><div className="mt-7 grid gap-6 lg:grid-cols-[280px_minmax(0,1fr)]"><aside className="space-y-2">{playlists.map((playlist) => <button className={`flex w-full items-center gap-3 rounded-2xl border p-3 text-left ${selectedPlaylist === playlist.id ? 'border-[#2a2a2a] bg-[#2a2a2a] text-white' : 'border-border bg-card'}`} key={playlist.id} onClick={() => setSelectedPlaylist(playlist.id)}><span className={`grid size-12 place-items-center rounded-xl ${selectedPlaylist === playlist.id ? 'bg-[#ddff63] text-black' : 'bg-muted'}`}><ListMusic /></span><span className="min-w-0 flex-1"><strong className="block truncate text-sm">{playlist.name}</strong><small className="opacity-55">{playlist.tracks.length}곡</small></span></button>)}</aside>{(() => { const playlist = playlists.find((item) => item.id === selectedPlaylist) || playlists[0]; return playlist ? <section className="rounded-[1.7rem] border border-border bg-card p-5 sm:p-7"><div className="flex flex-wrap items-start justify-between gap-4"><div><p className="font-mono text-[11px] font-medium lowercase tracking-[.02em] text-muted-foreground">PLAYLIST</p><h2 className="mt-1 text-2xl font-black tracking-[-.04em]">{playlist.name}</h2><p className="mt-1 text-sm text-muted-foreground">{playlist.tracks.length}곡 · {Math.round(playlist.tracks.reduce((sum, track) => sum + track.duration, 0) / 60)}분</p></div><div className="flex gap-2"><Button className="rounded-full" disabled={!playlist.tracks.length} onClick={() => playTrack(playlist.tracks[0])}><Play className="fill-current" /> 전체 재생</Button><Button aria-label="플레이리스트 삭제" onClick={() => { setPlaylists((current) => current.filter((item) => item.id !== playlist.id)); setSelectedPlaylist(playlists.find((item) => item.id !== playlist.id)?.id || 0); }} size="icon" variant="outline"><Trash2 /></Button></div></div><div className="mt-6 space-y-2">{playlist.tracks.map((track, index) => <div className="flex items-center gap-3 rounded-xl p-2 hover:bg-muted" key={track.id}><span className="w-5 text-center text-xs text-muted-foreground">{index + 1}</span><img alt="" className="size-11 rounded-lg object-cover" src={track.image} /><button className="min-w-0 flex-1 text-left" onClick={() => playTrack(track)}><strong className="block truncate text-sm">{track.title}</strong><span className="text-xs text-muted-foreground">{track.artist}</span></button><span className="hidden text-xs text-muted-foreground sm:block">{formatTime(track.duration)}</span><Button aria-label="플레이리스트에서 제거" onClick={() => setPlaylists((current) => current.map((item) => item.id === playlist.id ? { ...item, tracks: item.tracks.filter((value) => value.id !== track.id) } : item))} size="icon" variant="ghost"><X /></Button></div>)}{!playlist.tracks.length && <div className="py-14 text-center text-sm text-muted-foreground">아직 저장한 음악이 없어요.</div>}</div><div className="mt-5 rounded-xl bg-muted p-4"><p className="text-xs font-bold">현재 재생 중인 곡 추가</p><div className="mt-2 flex items-center gap-3"><img alt="" className="size-10 rounded-lg object-cover" src={activeTrack.image} /><span className="min-w-0 flex-1 text-sm font-bold">{activeTrack.title}</span><Button disabled={playlist.tracks.some((track) => track.id === activeTrack.id)} onClick={() => setPlaylists((current) => current.map((item) => item.id === playlist.id ? { ...item, tracks: [...item.tracks, activeTrack] } : item))} size="sm">{playlist.tracks.some((track) => track.id === activeTrack.id) ? <Check /> : <Plus />} 추가</Button></div></div></section> : null; })()}</div></div>
          )}

          {!detailTrack && view === 'dna' && (
            <DnaView moods={moods} nickname={nickname} onPlayMoment={(moment) => { void playTrack(moment.track, true); }} savedMoments={savedMoments} />
          )}

          {!detailTrack && view === 'profile' && (
            <div className="space-y-7"><div><p className="font-mono text-[11px] font-medium lowercase tracking-[.02em] text-[#728d00]">MY PAGE</p><h1 className="mt-1 text-3xl font-black tracking-[-.055em]">나의 뮤피드</h1></div><section className="flex flex-col gap-5 rounded-[1.7rem] border border-border bg-card p-6 sm:flex-row sm:items-center"><span className="grid size-20 shrink-0 place-items-center rounded-full bg-[#2a2a2a] text-2xl font-black text-[#ddff63]">MY</span><div className="min-w-0 flex-1"><label className="text-xs font-bold text-muted-foreground">닉네임</label><div className="mt-1 flex max-w-sm gap-2"><input className="h-10 min-w-0 flex-1 rounded-xl border border-border bg-background px-3 font-bold outline-none" onChange={(event) => setNickname(event.target.value)} value={nickname} /><Button aria-label="닉네임 저장" onClick={() => setToast('닉네임을 변경했어요.')} size="icon" variant="outline"><Pencil /></Button></div><p className="mt-2 text-xs text-muted-foreground">demo@mupid.kr</p></div><div className="flex gap-2"><Button onClick={() => setStage('onboarding')} variant="outline"><WandSparkles /> 취향 다시 설정</Button><Button onClick={() => setStage('auth')} variant="ghost"><LogOut /> 로그아웃</Button></div></section><div className="flex gap-2 overflow-x-auto border-b border-border pb-3">{[{ id: 'liked', label: '좋아요 음악', icon: Heart }, { id: 'moments', label: '저장한 구간', icon: Waveform }, { id: 'history', label: '감상 기록', icon: History }].map(({ id, label, icon: Icon }) => <Button className="shrink-0 rounded-full" key={id} onClick={() => setProfileTab(id as typeof profileTab)} variant={profileTab === id ? 'default' : 'ghost'}><Icon />{label}</Button>)}</div>{profileTab === 'liked' && <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">{likedIds.map((id) => { const track = findTrack(id); return <TrackCard isLiked key={id} onDetail={() => setDetailTrack(track)} onLike={() => toggleLike(id)} onPlay={() => playTrack(track)} track={track} />; })}</div>}{profileTab === 'moments' && <div className="space-y-3">{savedMoments.map((moment) => <article className="flex items-center gap-4 rounded-2xl border border-border bg-card p-3" key={moment.id}><img alt="" className="size-16 rounded-xl object-cover" src={moment.track.image} /><button className="min-w-0 flex-1 text-left" onClick={() => playTrack(moment.track, true)}><strong className="block truncate">{moment.track.title}</strong><span className="text-sm text-muted-foreground">{moment.track.artist} · {formatTime(moment.start)}–{formatTime(moment.end)}</span></button><Button aria-label="저장 구간 삭제" onClick={() => setSavedMoments((current) => current.filter((item) => item.id !== moment.id))} size="icon" variant="ghost"><Trash2 /></Button></article>)}</div>}{profileTab === 'history' && <div className="space-y-2">{historyIds.map((id, index) => { const track = findTrack(id); return <button className="flex w-full items-center gap-4 rounded-2xl border border-border bg-card p-3 text-left hover:bg-muted" key={id} onClick={() => playTrack(track)}><span className="text-xs text-muted-foreground">{index === 0 ? '방금' : `${index * 12}분 전`}</span><img alt="" className="size-12 rounded-xl object-cover" src={track.image} /><span className="min-w-0 flex-1"><strong className="block truncate text-sm">{track.title}</strong><small className="text-muted-foreground">{track.artist}</small></span><Play className="size-4" /></button>; })}</div>}</div>
          )}
        </section>
      </div>

      <nav className="fixed bottom-3 left-3 z-40 grid w-[56px] gap-1 rounded-2xl border border-white/10 bg-[#201f1f]/95 p-2 text-white shadow-xl backdrop-blur-xl lg:hidden">
        {navItems.map(({ id, label, icon: Icon }) => {
          const selected = !detailTrack && view === id;
          return (
            <button
              aria-current={selected ? 'page' : undefined}
              aria-label={label}
              className="flex min-w-0 flex-col items-center justify-center gap-0.5 rounded-xl py-1.5 transition-colors"
              key={id}
              onClick={() => { setDetailTrack(null); setView(id); }}
            >
              <span className={`grid size-8 place-items-center rounded-xl border transition-colors ${selected ? 'border-[#dfc0cb] bg-[#f6e9ee] text-[#9d4e69]' : 'border-transparent text-muted-foreground'}`}>
                <Icon className="size-[18px]" strokeWidth={selected ? 2.4 : 2} />
              </span>
              <span className={`max-w-full truncate text-[9px] font-bold ${selected ? 'text-foreground' : 'text-muted-foreground'}`}>{label}</span>
            </button>
          );
        })}
      </nav>

      <section className="fixed bottom-3 left-[76px] right-3 z-40 mx-auto flex max-w-[940px] items-center gap-2 rounded-2xl border border-white/10 bg-[#201f1f]/95 p-2 pr-2 text-white shadow-[0_18px_50px_rgb(30_30_30/32%)] backdrop-blur-xl sm:gap-3 sm:p-2.5 sm:pr-4 lg:left-3"><button className="size-11 shrink-0 overflow-hidden rounded-xl sm:size-12" onClick={() => setDetailTrack(activeTrack)}><img alt="현재 재생 곡" className="h-full w-full object-cover" src={activeTrack.image} /></button><button className="min-w-0 flex-1 text-left sm:max-w-40" onClick={() => setDetailTrack(activeTrack)}><p className="truncate text-xs font-bold sm:text-sm">{activeTrack.title}</p><p className="truncate text-[10px] text-white/45 sm:text-xs">{activeTrack.artist}</p><p className="mt-0.5 font-mono text-[10px] tabular-nums text-white/55 md:hidden">{formatTime(Math.min(time, activeTrack.duration))} / {formatTime(activeTrack.duration)}</p></button><div className="flex items-center gap-1"><Button aria-label="이전 곡" className="hidden text-white/65 hover:bg-white/10 hover:text-white sm:inline-flex" onClick={() => { const index = tracks.findIndex((track) => track.id === activeTrack.id); playTrack(tracks[(index - 1 + tracks.length) % tracks.length]); }} size="icon" variant="ghost"><SkipBack /></Button><Button aria-label={playing ? '일시정지' : '재생'} className="size-10 rounded-full bg-[#ddff63] text-black hover:bg-[#efffae]" onClick={togglePlay} size="icon-lg">{playing ? <Pause className="fill-current" /> : <Play className="fill-current" />}</Button><Button aria-label="다음 곡" className="hidden text-white/65 hover:bg-white/10 hover:text-white sm:inline-flex" onClick={() => { const index = tracks.findIndex((track) => track.id === activeTrack.id); playTrack(tracks[(index + 1) % tracks.length]); }} size="icon" variant="ghost"><SkipForward /></Button></div><div className="hidden min-w-0 flex-1 items-center gap-3 md:flex"><span className="text-[10px] text-white/40">{formatTime(Math.min(time, activeTrack.duration))}</span><input aria-label="재생 구간 이동" className="h-1 flex-1 accent-[#ddff63]" max={activeTrack.duration} min={0} onChange={(event) => { const next = Number(event.target.value); setTime(next); if (audioRef.current) audioRef.current.currentTime = next; }} type="range" value={Math.min(time, activeTrack.duration)} /><span className="text-[10px] text-white/40">{formatTime(activeTrack.duration)}</span></div><Button aria-label="좋아하는 부분 선택" className="size-10 shrink-0 rounded-full border border-black/80 bg-white text-[#ed3b78] shadow-none hover:border-black hover:bg-[#fff4f8] hover:text-[#d82665]" onClick={() => openMoment()} size="icon" variant="ghost"><Heart className="fill-current" /></Button><div className="hidden items-center gap-2 xl:flex"><Volume2 className="size-4 text-white/50" /><input aria-label="볼륨" className="w-20 accent-[#ddff63]" max={1} min={0} onChange={(event) => { const next = Number(event.target.value); setVolume(next); if (audioRef.current) audioRef.current.volume = next; }} step={0.05} type="range" value={volume} /></div><input aria-label="모바일 재생 구간 이동" className="absolute bottom-2 left-3 right-3 h-1 w-[calc(100%-1.5rem)] cursor-pointer accent-[#b96f88] md:hidden" max={activeTrack.duration} min={0} onChange={(event) => { const next = Number(event.target.value); setTime(next); if (audioRef.current) audioRef.current.currentTime = next; }} type="range" value={Math.min(time, activeTrack.duration)} /></section>

      {!chatOpen && <button aria-label="AI 음악 탐색 열기" className="fixed bottom-[10.5rem] right-3 z-50 flex h-13 items-center gap-2 rounded-full border border-black/10 bg-[#ddff63] px-2.5 font-black text-[#202020] shadow-[0_14px_40px_rgb(42_42_42/25%)] transition hover:-translate-y-0.5 hover:bg-[#efffae] sm:right-6 sm:px-4 lg:bottom-24" onClick={() => setChatOpen(true)}><span className="grid size-8 place-items-center rounded-full bg-[#202020] text-[#ddff63]"><MessageCircle className="size-4" /></span><span className="hidden sm:inline">AI로 이어 듣기</span></button>}

      {chatOpen && (
        <aside aria-label="MUPID AI 음악 탐색" className="fixed bottom-[9.75rem] left-2 right-2 z-50 flex h-[min(66dvh,590px)] w-auto max-w-[430px] flex-col overflow-hidden rounded-[1.35rem] border border-black/10 bg-background shadow-[0_24px_80px_rgb(30_30_30/30%)] sm:left-auto sm:right-6 sm:w-[calc(100vw-1.5rem)] lg:bottom-24 lg:h-[min(72vh,640px)] lg:rounded-[1.6rem]">
          <header className="flex items-center gap-3 bg-[#272627] px-4 py-3.5 text-white">
            <img alt="" className="size-11 rounded-xl object-cover" src={activeTrack.image} />
            <div className="min-w-0 flex-1"><div className="flex items-center gap-2"><strong className="text-sm">MUPID AI</strong><span className="size-1.5 rounded-full bg-[#ddff63]" /></div><p className="truncate text-[11px] text-white/45">{activeTrack.title}에서 이어 찾는 중</p></div>
            <Button aria-label="새 대화" className="text-white/60 hover:bg-white/10 hover:text-white" onClick={() => setChatMessages([{ id: chatMessageIdRef.current++, role: 'assistant', text: '새로운 탐색을 시작할게요. 지금 듣는 곡에서 어떤 방향으로 이어가고 싶나요?' }])} size="icon" variant="ghost"><RefreshCw /></Button>
            <Button aria-label="AI 음악 탐색 닫기" className="text-white/60 hover:bg-white/10 hover:text-white" onClick={() => setChatOpen(false)} size="icon" variant="ghost"><X /></Button>
          </header>

          <div className="min-h-0 flex-1 space-y-5 overflow-y-auto p-4">
            {chatMessages.map((message) => message.role === 'user' ? (
              <div className="flex justify-end" key={message.id}><p className="max-w-[84%] rounded-[1.15rem_1.15rem_.3rem_1.15rem] bg-[#2a2a2a] px-4 py-3 text-sm leading-6 text-white">{message.text}</p></div>
            ) : (
              <div className="flex items-start gap-2.5" key={message.id}>
                <span className="mt-0.5 grid size-7 shrink-0 place-items-center rounded-full bg-[#ddff63] text-black"><Sparkles className="size-3.5" /></span>
                <div className="min-w-0 flex-1"><p className="text-sm leading-6 text-[#4b4b4b]">{message.text}</p>{message.tracks && <div className="mt-3">{message.tracks.map((track) => <TrackCard compact isActive={activeTrack.id === track.id} isLiked={likedIds.includes(track.id)} isPlaying={playing && activeTrack.id === track.id} key={`${message.id}-${track.id}`} onDetail={() => { setDetailTrack(track); setChatOpen(false); }} onLike={() => toggleLike(track.id)} onPlay={() => { void toggleTrackPlayback(track, true); }} progress={activeTrack.id === track.id ? time / track.duration : 0} track={track} />)}</div>}</div>
              </div>
            ))}
            {chatLoading && <div className="flex items-center gap-2.5 text-xs text-muted-foreground"><span className="grid size-7 place-items-center rounded-full bg-[#ddff63] text-black"><Sparkles className="size-3.5 animate-pulse" /></span><span>분위기와 전개를 비교하고 있어요</span></div>}
          </div>

          <footer className="border-t border-border bg-card p-3">
            <div className="mb-2 flex flex-wrap gap-2">{chatSuggestions.slice(0, 3).map((suggestion) => <button className="min-h-9 rounded-full border border-border bg-background px-3.5 py-2 text-left text-[11px] font-bold leading-4 transition-colors hover:border-foreground hover:bg-muted disabled:opacity-40" disabled={chatLoading} key={suggestion} onClick={() => submitChatPrompt(suggestion)}>{suggestion}</button>)}</div>
            <form className="flex items-center gap-2 rounded-xl border border-border bg-background p-1.5 focus-within:border-foreground" onSubmit={(event) => { event.preventDefault(); submitChatPrompt(); }}><label className="sr-only" htmlFor="floating-mupid-ai-prompt">파생 추천 요청</label><input className="h-10 min-w-0 flex-1 bg-transparent px-2.5 text-sm outline-none" id="floating-mupid-ai-prompt" onChange={(event) => setChatInput(event.target.value)} placeholder="조금 더 잔잔한 곡 추천해줘" value={chatInput} /><Button aria-label="추천 요청 보내기" className="size-10 rounded-lg bg-[#2a2a2a] text-[#ddff63] hover:bg-black" disabled={!chatInput.trim() || chatLoading} size="icon" type="submit"><MessageCircle /></Button></form>
          </footer>
        </aside>
      )}

      {showMoment && (
        <div className="fixed inset-0 z-[60] grid place-items-center overflow-y-auto bg-black/60 p-3 backdrop-blur-sm sm:p-5">
          <div className="my-auto w-full max-w-2xl rounded-[1.75rem] bg-background p-4 shadow-2xl sm:rounded-[2rem] sm:p-6">
            <div className="flex items-start justify-between gap-4">
              <div>
                <p className="text-xs font-bold text-[#d0245c]">좋아한 구간 저장</p>
                <h2 className="mt-1 text-2xl font-black tracking-[-.045em]">좋아하는 부분을 선택해 주세요</h2>
                <p className="mt-1 text-sm leading-6 text-muted-foreground">송폼을 고른 뒤 시작과 종료 시간을 1초씩 조절할 수 있어요.</p>
              </div>
              <Button aria-label="닫기" className="shrink-0" onClick={() => setShowMoment(false)} size="icon" variant="ghost"><X /></Button>
            </div>

            <div className="mt-5 rounded-2xl bg-[#2a292d] p-4 text-[#f1ecef] sm:p-5">
              <div className="flex items-center gap-3 border-b border-[#403e45] pb-4">
                <img alt="" className="size-12 rounded-xl object-cover" src={momentTrack.image} />
                <div className="min-w-0 flex-1"><strong className="block truncate text-sm">{momentTrack.title}</strong><span className="text-xs text-white/45">{momentTrack.artist} · {formatTime(momentTrack.duration)}</span></div>
                <Button className="h-9 shrink-0 rounded-full border border-[#b96f88]/35 bg-[#3a3036] px-4 text-[#f3dbe3] hover:bg-[#453840]" onClick={previewMoment} size="sm">
                  {playing && activeTrack.id === momentTrack.id && momentPlayhead >= momentStart && momentPlayhead < momentEnd ? <Pause className="fill-current" /> : <Play className="fill-current" />}
                  <span className="hidden sm:inline">선택 구간 미리듣기</span><span className="sm:hidden">미리듣기</span>
                </Button>
              </div>

              <div className="mt-4 flex flex-wrap items-end justify-between gap-3">
                <div><span className="block text-[11px] font-semibold text-white/50">현재 선택 · {momentFormLabel}</span><strong className="mt-1 block font-mono text-xl">{formatTime(momentStart)} <span className="text-white/35">—</span> {formatTime(momentEnd)}</strong></div>
                <span className="flex items-center gap-1.5 rounded-full border border-[#b96f88]/35 bg-[#3a3036] px-3 py-1.5 text-xs font-semibold text-[#e1a9bb]"><Heart className="size-3.5 fill-current" /> 좋아요 {formatTime(momentAnchor)}</span>
              </div>

              <p className="mb-2 mt-5 text-[11px] font-semibold text-white/50">송폼으로 빠르게 선택</p>
              <div className="flex gap-1.5 overflow-x-auto pb-1" aria-label="송폼 구간 선택">
                {momentTrack.sections.map((section) => {
                  const selected = momentStart === section.start && momentEnd === section.end;
                  return <button className={`shrink-0 rounded-full border px-3.5 py-2 text-xs font-bold transition-colors ${selected ? 'border-[#b96f88] bg-[#b96f88] text-[#fff7fa]' : 'border-[#48464e] bg-[#343338] text-white/60 hover:bg-[#3e3c43] hover:text-white/85'}`} key={`${section.label}-${section.start}`} onClick={() => { setMomentStart(section.start); setMomentEnd(section.end); setMomentFormLabel(section.label); seekMoment(section.start); }} type="button">{section.label}</button>;
                })}
              </div>

              <div className="mt-4">
                <MomentTimeline
                  anchor={momentAnchor}
                  duration={momentTrack.duration}
                  end={momentEnd}
                  onEndChange={(value) => { setMomentEnd(value); setMomentFormLabel('직접 조정'); if (momentPlayhead > value) seekMoment(value); }}
                  onSeek={seekMoment}
                  onStartChange={(value) => { setMomentStart(value); setMomentFormLabel('직접 조정'); if (momentPlayhead < value) seekMoment(value); }}
                  start={momentStart}
                />
              </div>

              <p className="mt-3 text-center text-xs text-white/45">선택 영역을 끌어서 원하는 위치로 옮길 수 있어요.</p>
            </div>

            <div className="mt-4 flex gap-2"><Button className="h-11 flex-1" onClick={() => setShowMoment(false)} variant="outline">취소</Button><Button className="h-11 flex-1 bg-[#b96f88] text-white hover:bg-[#a96079]" onClick={saveMoment}><Heart className="fill-current" /> {Math.round(momentEnd - momentStart)}초 구간 저장</Button></div>
          </div>
        </div>
      )}

      {showPlaylistForm && <div className="fixed inset-0 z-[60] grid place-items-center bg-black/45 p-4 backdrop-blur-sm"><form className="w-full max-w-sm rounded-[1.7rem] bg-background p-6 shadow-2xl" onSubmit={(event) => { event.preventDefault(); if (!playlistName.trim()) return; const nextId = Date.now(); setPlaylists((current) => [...current, { id: nextId, name: playlistName, tracks: [] }]); setSelectedPlaylist(nextId); setPlaylistName(''); setShowPlaylistForm(false); }}><div className="flex items-center justify-between"><h2 className="text-xl font-black">새 플레이리스트</h2><Button aria-label="닫기" onClick={() => setShowPlaylistForm(false)} size="icon" type="button" variant="ghost"><X /></Button></div><input autoFocus className="mt-5 h-12 w-full rounded-xl border border-border bg-card px-4 outline-none focus:border-[#7f5caf]" onChange={(event) => setPlaylistName(event.target.value)} placeholder="플레이리스트 이름" value={playlistName} /><Button className="mt-3 h-11 w-full rounded-xl" disabled={!playlistName.trim()} type="submit"><Plus /> 만들기</Button></form></div>}

      {toast && <div aria-live="polite" className="fixed left-1/2 top-20 z-[70] flex -translate-x-1/2 items-center gap-2 rounded-full bg-[#201f1f] px-4 py-2.5 text-sm font-semibold text-white shadow-xl"><Sparkles className="size-4 text-[#ddff63]" />{toast}</div>}
    </main>
  );
}
