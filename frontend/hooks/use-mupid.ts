'use client';
import { useEffect, useRef, useState } from 'react';
import { mupidService } from '@/lib/services';
import type { Bootstrap, AccountSession } from '@/lib/services/contracts';
import {
  getSongSectionAt,
  type Track,
  type Page,
  type Playlist,
  type SavedMoment,
  type ChatMessage,
} from '@/lib/mupid-data';

const pageNames: Page[] = [
  'home',
  'search',
  'library',
  'report',
  'profile',
  'player',
  'moment',
  'similar',
  'welcome',
];
export function useMupid(bootstrap: Bootstrap) {
  // 화면들은 이 훅의 모델을 공유한다. 재생기나 로그인 상태를 화면마다 별도로 만들지 않는다.
  const { tracks, preferences: initial } = bootstrap;
  // Local preferences are not proof of authentication or account onboarding.
  const [onboardingSubmittedAt, setOnboardingSubmittedAt] = useState<string | null>(null);
  const onboardingRef = useRef<string | null>(null);
  const sessionRef = useRef<AccountSession | null>(null);
  // 새로고침 시 세션 복원은 아직 미연결이다. 추후 서버의 세션 확인 응답으로 초기화해야 한다.
  const [page, setPage] = useState<Page>('welcome');
  const [nickname, setNickname] = useState(initial.nickname);
  const [moods, setMoods] = useState(initial.moods);
  const [active, setActive] = useState<Track>(tracks[0]);
  const [playing, setPlaying] = useState(false);
  const [time, setTime] = useState(0);
  const [volume, setVolume] = useState(0.65);
  const [liked, setLiked] = useState(initial.liked);
  const [ignored, setIgnored] = useState<number[]>(initial.ignored);
  const [history, setHistory] = useState(initial.history);
  const [moments, setMoments] = useState<SavedMoment[]>(initial.moments);
  const [playlists, setPlaylists] = useState<Playlist[]>(initial.playlists);
  const [selectedPlaylist, setSelectedPlaylist] = useState<number | null>(null);
  const [libraryTab, setLibraryTab] = useState('playlists');
  const [query, setQuery] = useState('');
  const [moodFilter, setMoodFilter] = useState('전체');
  const [shuffle, setShuffle] = useState(false);
  const [repeat, setRepeat] = useState(false);
  const [queueIds, setQueueIds] = useState<number[]>(tracks.map((t) => t.id));
  const [toast, setToast] = useState('');
  const [ready, setReady] = useState(false);
  const [demoSession, setDemoSession] = useState<AccountSession | null>(null);
  const saveQueue = useRef<Promise<void>>(Promise.resolve());
  const [loginDialog, setLoginDialog] = useState(false);
  const loginTriggerRef = useRef<HTMLElement | null>(null);
  const [chatOpen, setChatOpen] = useState(false);
  const [chatInput, setChatInput] = useState('');
  const [chatLoading, setChatLoading] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([
    {
      id: 1,
      role: 'assistant',
      text: '지금 듣는 곡의 좋아한 구간에서 새로운 음악을 찾아볼까요? 원하는 느낌을 들려주세요.',
    },
  ]);
  const [momentRange, setMomentRange] = useState<number[]>([70, 92]);
  const [momentSection, setMomentSection] = useState('코러스 2');
  const [notificationOpen, setNotificationOpen] = useState(false);
  const [playlistDialog, setPlaylistDialog] = useState(false);
  const [deletePlaylist, setDeletePlaylist] = useState<number | null>(null);
  const [queueDialog, setQueueDialog] = useState(false);
  const [showAllSimilar, setShowAllSimilar] = useState(false);
  const audioRef = useRef<HTMLAudioElement>(null);
  const activeRef = useRef(active);
  const mountedRef = useRef(true);
  const seekOnLoad = useRef<number | null>(null);
  const previewEnd = useRef<number | null>(null);
  const advancing = useRef(false);
  const requestId = useRef(0);
  const messageEndRef = useRef<HTMLDivElement>(null);
  const chatInputRef = useRef<HTMLInputElement>(null);
  const chatButtonRef = useRef<HTMLButtonElement>(null);
  const chatTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const go = (next: Page) => {
    // URL 직접 이동과 UI 이동 모두 같은 조건으로 보호한다. 실제 접근 권한 검증은 서버 책임이다.
    if ((!sessionRef.current || !onboardingRef.current) && next !== 'welcome') next = 'welcome';
    setPage(next);
    setNotificationOpen(false);
    const url = new URL(window.location.href);
    if (next === 'home') url.searchParams.delete('view');
    else url.searchParams.set('view', next);
    window.history.pushState({ mupid: true }, '', url);
    window.scrollTo({ top: 0, behavior: 'instant' });
  };
  useEffect(() => {
    mountedRef.current = true;
    const readPage = () => {
      const value = new URLSearchParams(window.location.search).get(
        'view',
      ) as Page;
      setPage(!sessionRef.current || !onboardingRef.current ? 'welcome' : pageNames.includes(value) ? value : 'home');
      window.scrollTo(0, 0);
    };
    window.addEventListener('popstate', readPage);
    queueMicrotask(() => {
      if (!mountedRef.current) return;
      readPage();
      setReady(true);
    });
    return () => {
      mountedRef.current = false;
      window.removeEventListener('popstate', readPage);
      if (chatTimer.current) clearTimeout(chatTimer.current);
    };
  }, [initial.onboardingSubmittedAt]);
  useEffect(() => {
    if (!ready || !sessionRef.current) return;
    saveQueue.current = saveQueue.current.catch(() => {}).then(() =>
      mupidService.savePreferences({ onboardingSubmittedAt, nickname, moods, liked, ignored, history, moments, playlists })
    ).catch(() => { if (mountedRef.current) setToast('변경 내용을 저장하지 못했어요. 다시 시도해 주세요.'); });
  }, [ready, onboardingSubmittedAt, nickname, moods, liked, ignored, history, moments, playlists]);
  const completeOnboarding = async (name: string, selectedMoods: string[]) => {
    // 저장이 성공하기 전에는 홈으로 이동하거나 완료 표시를 남기지 않는다.
    if (sessionRef.current?.kind !== 'authenticated') throw new Error('로그인이 필요해요.');
    if (!name.trim() || !selectedMoods.length) throw new Error('이름과 좋아하는 분위기를 선택해 주세요.');
    const submittedAt = new Date().toISOString();
    const value = { onboardingSubmittedAt: submittedAt, nickname: name.trim().slice(0, 20), moods: selectedMoods, liked, ignored, history, moments, playlists };
    const save = saveQueue.current.catch(() => {}).then(() => mupidService.savePreferences(value));
    saveQueue.current = save;
    await save;
    setNickname(value.nickname); setMoods(selectedMoods); setOnboardingSubmittedAt(submittedAt);
    onboardingRef.current = submittedAt;
    setPage('home');
    window.history.replaceState({ mupid: true }, '', '/');
    window.scrollTo(0, 0);
  };
  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(''), 3200);
    return () => clearTimeout(timer);
  }, [toast]);
  useEffect(() => {
    if (audioRef.current) audioRef.current.volume = volume;
  }, [volume]);
  useEffect(() => {
    if (!chatOpen) return;
    chatInputRef.current?.focus({ preventScroll: true });
    const escape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setChatOpen(false);
        chatButtonRef.current?.focus();
      }
    };
    window.addEventListener('keydown', escape);
    return () => window.removeEventListener('keydown', escape);
  }, [chatOpen]);
  useEffect(() => {
    if (chatOpen)
      messageEndRef.current?.scrollIntoView({
        block: 'nearest',
        behavior: 'smooth',
      });
  }, [chatOpen, messages, chatLoading]);

  const openLogin = () => {
    loginTriggerRef.current =
      document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null;
    setChatOpen(false);
    setNotificationOpen(false);
    setLoginDialog(true);
  };
  const signInDemo = async (email: string, password: string) => {
    // 기존 호출부 호환을 위해 이름은 유지했지만 데모 로그인 성공을 만들지 않는다.
    // API 어댑터에서 서버의 onboarding_submitted_at을 onboardingSubmittedAt으로 변환한다.
    const session = await mupidService.login({ email, password });
    if (session.kind !== 'authenticated') throw new Error('실제 로그인이 필요해요.');
    sessionRef.current = session;
    const completedAt = session.onboardingSubmittedAt ?? null;
    onboardingRef.current = completedAt;
    setOnboardingSubmittedAt(completedAt);
    setNickname(session.nickname);
    setDemoSession(session);
    setLoginDialog(false);
    setPage(completedAt ? 'home' : 'welcome');
    window.history.replaceState({ mupid: true }, '', completedAt ? '/' : '/?view=welcome');
    setToast('뮤피드에 오신 걸 환영해요.');
    return true;
  };
  const signOutDemo = async () => {
    try { await mupidService.logout(); } catch { setToast('로그아웃하지 못했어요. 다시 시도해 주세요.'); return; }
    setDemoSession(null);
    sessionRef.current = null;
    onboardingRef.current = null;
    setOnboardingSubmittedAt(null);
    audioRef.current?.pause();
    setPlaying(false);
    setPage('welcome');
    window.history.replaceState({ mupid: true }, '', '/?view=welcome');
    setLoginDialog(false);
    setToast('로그아웃 화면으로 전환했어요. 음악과 저장 기록은 그대로예요.');
  };

  const loadTrack = (track: Track) => {
    // 곡이 바뀌면 이전 곡의 지연된 재생 요청과 예약된 탐색 위치를 무효화한다.
    const audio = audioRef.current;
    if (activeRef.current.id !== track.id) {
      requestId.current++;
      seekOnLoad.current = null;
      audio?.pause();
      activeRef.current = track;
      setActive(track);
      setTime(0);
      if (audio) {
        audio.src = track.audio;
        audio.load();
      }
    }
  };
  const seek = (value: number) => {
    // 내부 위치 이동 함수. 화면에서 호출하는 seek는 반환 모델에서 구간 미리듣기도 해제한다.
    const next = Math.max(0, Math.min(value, activeRef.current.duration));
    setTime(next);
    const audio = audioRef.current;
    if (audio && audio.readyState >= 1) audio.currentTime = next;
    else seekOnLoad.current = next;
  };
  const startPlayback = async (track: Track, start?: number) => {
    const audio = audioRef.current;
    if (!audio) return;
    loadTrack(track);
    if (start !== undefined) seek(start);
    const id = ++requestId.current;
    try {
      await audio.play();
      if (id === requestId.current) advancing.current = false;
    } catch (error) {
      if (
        id === requestId.current &&
        mountedRef.current &&
        !(error instanceof DOMException && error.name === 'AbortError')
      ) {
        setPlaying(false);
        advancing.current = false;
        setToast('미리듣기를 불러오지 못했어요. 다시 재생해 주세요.');
      }
    }
    setHistory((ids) =>
      [track.id, ...ids.filter((i) => i !== track.id)].slice(0, 8),
    );
  };
  const play = (track: Track = activeRef.current, fromMoment = false) => {
    const audio = audioRef.current;
    previewEnd.current = null;
    if (!queueIds.includes(track.id)) setQueueIds(tracks.map((t) => t.id));
    if (
      !fromMoment &&
      activeRef.current.id === track.id &&
      audio &&
      !audio.paused
    ) {
      requestId.current++;
      audio.pause();
      return;
    }
    void startPlayback(
      track,
      fromMoment
        ? track.moment
        : audio && audio.currentTime >= track.duration
          ? 0
          : undefined,
    );
  };
  const next = (direction = 1) => {
    previewEnd.current = null;
    const pool = queueIds
      .map((id) => tracks.find((t) => t.id === id)!)
      .filter((t) => t && !ignored.includes(t.id));
    if (!pool.length) return;
    const index = pool.findIndex((t) => t.id === activeRef.current.id);
    const candidates = pool.filter((t) => t.id !== activeRef.current.id);
    const target =
      shuffle && candidates.length
        ? candidates[Math.floor(Math.random() * candidates.length)]
        : pool[(index + direction + pool.length) % pool.length];
    void startPlayback(target, 0);
  };
  const queueTracks = queueIds
    .map((id) => tracks.find((t) => t.id === id)!)
    .filter((t) => t && !ignored.includes(t.id));
  const playPlaylist = (playlist: Playlist) => {
    const playable = playlist.tracks.filter((t) => !ignored.includes(t.id));
    if (!playable.length) {
      setToast('재생할 수 있는 곡이 없어요.');
      return;
    }
    setQueueIds(playable.map((t) => t.id));
    previewEnd.current = null;
    void startPlayback(playable[0], 0);
  };
  const onEnded = () => {
    if (advancing.current) return;
    if (previewEnd.current !== null) {
      previewEnd.current = null;
      return;
    }
    advancing.current = true;
    if (repeat) void startPlayback(activeRef.current, 0);
    else next();
  };
  const onTimeUpdate = () => {
    const audio = audioRef.current;
    if (!audio || advancing.current || audio.readyState < 1) return;
    const mediaEnd = Number.isFinite(audio.duration) ? audio.duration : activeRef.current.duration;
    const boundary = previewEnd.current ?? Math.min(activeRef.current.duration, mediaEnd);
    if (audio.currentTime >= boundary) {
      if (previewEnd.current !== null) {
        audio.pause();
        setTime(boundary);
        previewEnd.current = null;
      } else onEnded();
    } else setTime(audio.currentTime);
  };
  const openTrack = (track: Track) => {
    previewEnd.current = null;
    loadTrack(track);
    go('player');
  };
  const openMoment = (track = activeRef.current) => {
    previewEnd.current = null;
    loadTrack(track);
    const part = getSongSectionAt(
      track,
      Math.min(audioRef.current?.currentTime || track.moment, track.duration),
    );
    setMomentRange([part.start, part.end]);
    setMomentSection(part.label);
    go('moment');
  };
  const previewMoment = () => {
    previewEnd.current = momentRange[1];
    void startPlayback(activeRef.current, momentRange[0]);
  };
  const playSavedMoment = (moment: SavedMoment) => {
    const audio = audioRef.current;
    if (
      activeRef.current.id === moment.track.id &&
      previewEnd.current === moment.end &&
      audio &&
      !audio.paused
    ) {
      audio.pause();
      return;
    }
    previewEnd.current = moment.end;
    void startPlayback(moment.track, moment.start);
  };
  const saveMoment = () => {
    const saved: SavedMoment = {
      id: Date.now(),
      track: activeRef.current,
      start: momentRange[0],
      end: momentRange[1],
      section: momentSection,
    };
    setMoments((list) => [
      saved,
      ...list.filter(
        (m) =>
          !(
            m.track.id === saved.track.id &&
            m.start === saved.start &&
            m.end === saved.end
          ),
      ),
    ]);
    setLiked((ids) =>
      ids.includes(saved.track.id) ? ids : [...ids, saved.track.id],
    );
    setToast('좋아한 순간을 저장했어요. 내 플레이리스트에서 다시 만나요.');
  };
  const toggleLike = (id: number) =>
    setLiked((ids) =>
      ids.includes(id) ? ids.filter((i) => i !== id) : [...ids, id],
    );
  const openChat = () => setChatOpen(true);
  const closeChat = () => {
    setChatOpen(false);
    requestAnimationFrame(() => chatButtonRef.current?.focus());
  };
  const onLoadedMetadata = () => {
    if (seekOnLoad.current !== null) {
      seek(seekOnLoad.current);
      seekOnLoad.current = null;
    }
  };
  const sendMessage = (prompt = chatInput) => {
    if (!prompt.trim() || chatLoading) return;
    const text = prompt.trim().slice(0, 500),
      current = activeRef.current;
    setMessages((list) => [...list, { id: Date.now(), role: 'user', text }]);
    setChatInput('');
    setChatLoading(true);
    chatTimer.current = setTimeout(async () => {
      try {
      const answer = await mupidService.recommend(
        text,
        current,
        tracks.filter((t) => !ignored.includes(t.id)),
      );
      if (!mountedRef.current) return;
      setMessages((list) => [
        ...list,
        {
          id: Date.now() + 1,
          role: 'assistant',
          text: answer.text,
          tracks: answer.tracks,
        },
      ]);
      } catch { if (mountedRef.current) setToast('추천을 불러오지 못했어요. 다시 요청해 주세요.'); }
      finally { if (mountedRef.current) setChatLoading(false); }
    }, 650);
  };
  const createPlaylist = (name: string) => {
    if (!name.trim()) return;
    const id = Date.now();
    setPlaylists((list) => [
      ...list,
      { id, name: name.trim().slice(0, 40), tracks: [] },
    ]);
    setSelectedPlaylist(id);
    setPlaylistDialog(false);
    setToast('새 플레이리스트를 만들었어요.');
  };
  return {
    onboardingSubmittedAt,
    completeOnboarding,
    tracks,
    page,
    go,
    nickname,
    setNickname,
    displayName: demoSession ? nickname : '게스트',
    demoSession,
    authReady: ready,
    loginDialog,
    setLoginDialog,
    loginTriggerRef,
    openLogin,
    signInDemo,
    signOutDemo,
    moods,
    setMoods,
    active,
    playing,
    setPlaying,
    time,
    volume,
    setVolume,
    liked,
    ignored,
    setIgnored,
    history,
    moments,
    setMoments,
    playlists,
    setPlaylists,
    selectedPlaylist,
    setSelectedPlaylist,
    libraryTab,
    setLibraryTab,
    query,
    setQuery,
    moodFilter,
    setMoodFilter,
    shuffle,
    setShuffle,
    repeat,
    setRepeat,
    toast,
    setToast,
    chatOpen,
    setChatOpen,
    chatInput,
    setChatInput,
    chatLoading,
    messages,
    momentRange,
    setMomentRange,
    momentSection,
    setMomentSection,
    notificationOpen,
    setNotificationOpen,
    playlistDialog,
    setPlaylistDialog,
    deletePlaylist,
    setDeletePlaylist,
    queueDialog,
    setQueueDialog,
    queueTracks,
    playPlaylist,
    onEnded,
    showAllSimilar,
    setShowAllSimilar,
    audioRef,
    messageEndRef,
    chatInputRef,
    chatButtonRef,
    play,
    next,
    seek: (value: number) => {
      if (!Number.isFinite(value)) return;
      previewEnd.current = null;
      void startPlayback(activeRef.current, value);
    },
    onTimeUpdate,
    onLoadedMetadata,
    openTrack,
    openMoment,
    previewMoment,
    playSavedMoment,
    saveMoment,
    toggleLike,
    openChat,
    closeChat,
    sendMessage,
    createPlaylist,
  };
}
export type MupidModel = ReturnType<typeof useMupid>;
