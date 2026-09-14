'use client';
import { useEffect, useRef, useState } from 'react';
import { mupidService } from '@/lib/services';
import { login, logout, restoreSession } from '@/lib/api/session';
import { getPlayer } from '@/lib/player';
import { isStreamed } from '@/lib/stream-catalog';
import { fetchOnboarding, saveOnboarding } from '@/lib/onboarding';
import { fetchHistory, fetchReactions, pushReaction } from '@/lib/reactions';
import { analytics, type EntryPath } from '@/lib/analytics';
import { fetchSections } from '@/lib/music-analysis';
import type { Bootstrap, AccountSession } from '@/lib/services/contracts';
import {
  getSongSectionAt,
  type Track,
  type Page,
  type Playlist,
  type SavedMoment,
  type ChatMessage,
} from '@/lib/mupid-data';

/** 검색어 입력이 멎었다고 볼 시간. 이벤트를 글자마다 보내지 않으려는 값이다. */
const SEARCH_EVENT_DELAY_MS = 900;

/** 화면이 들고 있는 최근 감상 개수. */
const HISTORY_LIMIT = 8;

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
  const { tracks, preferences: initial } = bootstrap;
  const [onboardingSubmittedAt, setOnboardingSubmittedAt] = useState(initial.onboardingSubmittedAt);
  const onboardingRef = useRef(initial.onboardingSubmittedAt);
  const [page, setPage] = useState<Page>(initial.onboardingSubmittedAt ? 'home' : 'welcome');
  const [nickname, setNickname] = useState(initial.nickname);
  const [moods, setMoods] = useState(initial.moods);
  const [genres, setGenres] = useState(initial.genres);
  const [active, setActive] = useState<Track>(tracks[0]);
  const [playing, setPlaying] = useState(false);
  // 청크를 기다리는 중. 화면은 이 값으로 스피너를 띄운다.
  const [buffering, setBuffering] = useState(false);
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
    if (!onboardingSubmittedAt && next !== 'welcome') next = 'welcome';
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
      setPage(!onboardingRef.current ? 'welcome' : pageNames.includes(value) ? value : 'home');
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
    if (!ready) return;
    saveQueue.current = saveQueue.current.catch(() => {}).then(() =>
      mupidService.savePreferences({ onboardingSubmittedAt, nickname, moods, genres, liked, ignored, history, moments, playlists })
    ).catch(() => { if (mountedRef.current) setToast('변경 내용을 저장하지 못했어요. 다시 시도해 주세요.'); });
  }, [ready, onboardingSubmittedAt, nickname, moods, genres, liked, ignored, history, moments, playlists]);
  /**
   * 온보딩 결과를 저장한다.
   *
   * 로그인했으면 계정에도 남긴다. 서버 저장이 실패하면 던져서 화면이 알리게 한다 —
   * 브라우저에만 남고 기기를 바꾸면 사라지는 걸 조용히 넘기면 안 된다.
   * 비로그인이면 예전처럼 브라우저 저장만 한다.
   */
  const completeOnboarding = async (name: string, selectedMoods: string[], selectedGenres: string[]) => {
    if (!name.trim() || !selectedMoods.length || !selectedGenres.length)
      throw new Error('이름과 좋아하는 분위기·장르를 선택해 주세요.');
    const submittedAt = new Date().toISOString();
    const value = { onboardingSubmittedAt: submittedAt, nickname: name.trim().slice(0, 10), moods: selectedMoods, genres: selectedGenres, liked, ignored, history, moments, playlists };
    const save = saveQueue.current.catch(() => {}).then(() => mupidService.savePreferences(value));
    saveQueue.current = save;
    await save;
    if (demoSession?.kind === 'authenticated') {
      await saveOnboarding({ nickname: value.nickname, moods: selectedMoods, genres: selectedGenres });
    }
    setNickname(value.nickname); setMoods(selectedMoods); setGenres(selectedGenres); setOnboardingSubmittedAt(submittedAt);
    onboardingRef.current = submittedAt;
    setPage('home');
    window.history.replaceState({ mupid: true }, '', '/');
    window.scrollTo(0, 0);
  };
  /**
   * 검색 이벤트.
   *
   * 화면이 입력할 때마다 걸러 주므로 제출 버튼이 없다. 글자마다 이벤트를 보내면
   * "ㅁ", "무", "무피" 가 전부 검색어로 남으므로 손이 멎은 뒤에 한 번만 보낸다.
   */
  useEffect(() => {
    const keyword = query.trim();
    if (!keyword || demoSession?.kind !== 'authenticated') return;
    const timer = setTimeout(() => analytics.search(keyword), SEARCH_EVENT_DELAY_MS);
    return () => clearTimeout(timer);
  }, [query, demoSession]);
  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(''), 3200);
    return () => clearTimeout(timer);
  }, [toast]);
  useEffect(() => {
    if (audioRef.current) audioRef.current.volume = volume;
    getPlayer()?.setVolume(volume);
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
  /**
   * 프로필 화면에서 선호 분위기를 바꾼다.
   *
   * 온보딩과 같은 데이터라 계정에도 같이 반영한다. 서버가 거절하면 화면 상태를
   * 되돌린다 — 눌린 채로 두면 저장된 줄 알게 된다.
   */
  const updateMoods = (next: (list: string[]) => string[]) => {
    const before = moods;
    const after = next(before);
    setMoods(after);
    if (demoSession?.kind !== 'authenticated' || !onboardingRef.current) return;
    if (!after.length || !genres.length) {
      setMoods(before);
      setToast('분위기는 하나 이상 골라야 해요.');
      return;
    }
    saveOnboarding({ nickname, moods: after, genres }).catch(() => {
      if (!mountedRef.current) return;
      setMoods(before);
      setToast('선호 분위기를 저장하지 못했어요. 다시 시도해 주세요.');
    });
  };
  /**
   * 계정에 저장된 온보딩을 화면 상태로 가져온다.
   *
   * 다른 기기에서 제출했으면 여기서 따라온다. 계정에 아직 없으면 브라우저에
   * 있던 값을 그대로 두고 온보딩 화면으로도 보내지 않는다 — 이 기기에서 이미
   * 답한 사람을 다시 묻는 꼴이 되기 때문이다.
   */
  const adoptServerOnboarding = async (signal?: AbortSignal) => {
    const saved = await fetchOnboarding(signal);
    if (!saved || signal?.aborted || !mountedRef.current) return;
    setNickname(saved.nickname);
    setMoods(saved.moods);
    setGenres(saved.genres);
    setOnboardingSubmittedAt(saved.submittedAt);
    onboardingRef.current = saved.submittedAt;
    // 이 기기에는 기록이 없어 온보딩 화면에 서 있다. 계정에 답이 있으니 통과시킨다.
    setPage((current) => (current === 'welcome' ? 'home' : current));
  };
  /**
   * 계정에 저장된 좋아요·관심없음을 화면 상태로 가져온다.
   *
   * 브라우저에 있던 값은 덮는다. 계정 쪽이 기준이다 — 기기를 바꿔도 따라오는
   * 것이 이 API 의 목적이다. 지금 카탈로그에 없는 곡은 버려진다.
   */
  const adoptServerReactions = async (signal?: AbortSignal) => {
    const saved = await fetchReactions(tracks, signal);
    if (!saved || signal?.aborted || !mountedRef.current) return;
    setLiked(saved.liked);
    setIgnored(saved.ignored);
  };
  /**
   * 계정에 저장된 최근 감상을 화면 상태로 가져온다.
   *
   * 계정 기록을 앞에 놓고, 이 기기에만 있던 것(데모 곡 등)을 뒤에 붙인다.
   * 좋아요와 달리 덮지 않는 이유는 방금 들은 곡이 사라지면 화면이 뒤로 가는
   * 것처럼 보이기 때문이다.
   */
  const adoptServerHistory = async (signal?: AbortSignal) => {
    const saved = await fetchHistory(tracks, signal);
    if (!saved?.length || signal?.aborted || !mountedRef.current) return;
    setHistory((current) =>
      [...saved, ...current.filter((id) => !saved.includes(id))].slice(0, HISTORY_LIMIT),
    );
  };
  // 액세스 토큰은 메모리에만 있다. 새로고침 뒤에는 쿠키의 리프레시 토큰으로
  // 한 번만 복원을 시도하고, 실패하면 비로그인 상태로 둔다.
  useEffect(() => {
    const abort = new AbortController();
    restoreSession().then((session) => {
      if (abort.signal.aborted || session.status !== 'authenticated' || !session.user) return;
      setNickname(session.user.nickname);
      setDemoSession({ kind: 'authenticated', nickname: session.user.nickname, userId: session.user.user_id });
      void adoptServerOnboarding(abort.signal);
      void adoptServerReactions(abort.signal);
      void adoptServerHistory(abort.signal);
    });
    return () => { abort.abort(); };
  }, []);
  const signInDemo = async (email: string, password: string) => {
    const user = await login(email, password);
    setNickname(user.nickname);
    setDemoSession({ kind: 'authenticated', nickname: user.nickname, userId: user.user_id });
    setLoginDialog(false);
    setToast('뮤피드에 오신 걸 환영해요.');
    void adoptServerOnboarding();
    void adoptServerReactions();
    void adoptServerHistory();
    return true;
  };
  const signOutDemo = async () => {
    try { await logout(); } catch { setToast('로그아웃하지 못했어요. 다시 시도해 주세요.'); return; }
    setDemoSession(null);
    setLoginDialog(false);
    setToast('로그아웃 화면으로 전환했어요. 음악과 저장 기록은 그대로예요.');
  };

  const loadTrack = (track: Track) => {
    const audio = audioRef.current;
    if (activeRef.current.id !== track.id) {
      requestId.current++;
      seekOnLoad.current = null;
      audio?.pause();
      activeRef.current = track;
      setActive(track);
      setTime(0);
      // 두 재생 경로가 동시에 소리를 내지 않도록 반대쪽을 끈다.
      if (isStreamed(track)) {
        if (audio) audio.removeAttribute('src');
        return;
      }
      getPlayer()?.stop();
      setBuffering(false);
      if (audio) {
        audio.src = track.audio;
        audio.load();
      }
    }
  };
  /**
   * 재생 대상이 바뀌면 서버가 분석한 구간으로 갈아 끼운다.
   *
   * 분석 전이면 그대로 둔다 — `lib/stream-catalog.ts` 가 넣어 둔 임시 구간이
   * 남는다. 구간 UI 는 `m.active.sections` 만 보므로 여기만 고치면 된다.
   */
  useEffect(() => {
    if (!isStreamed(active)) return;
    const abort = new AbortController();
    void fetchSections(active, abort.signal).then((sections) => {
      if (!sections || abort.signal.aborted || !mountedRef.current) return;
      setActive((current) => (current.id === active.id ? { ...current, sections } : current));
      activeRef.current = { ...activeRef.current, sections };
    });
    return () => abort.abort();
  }, [active.id]);
  const seek = (value: number) => {
    const next = Math.max(0, Math.min(value, activeRef.current.duration));
    setTime(next);
    if (isStreamed(activeRef.current)) {
      void getPlayer()?.seek(next);
      return;
    }
    const audio = audioRef.current;
    if (audio && audio.readyState >= 1) audio.currentTime = next;
    else seekOnLoad.current = next;
  };
  /**
   * 이 재생이 어디서 왔는가. 청취 이벤트의 `entry_path` 다.
   *
   * 정확한 출처를 따로 들고 다니지 않으므로 지금 보고 있는 화면으로 갈음한다.
   * 인플레이어 다음곡 추천이 없어서 검색 결과를 이어 듣는 것도 `SEARCH` 다
   * (decision/0014).
   */
  const entryPathOf = (): EntryPath => {
    if (page === 'search') return 'SEARCH';
    if (page === 'library') return 'PLAYLIST';
    return 'RECOMMEND';
  };
  const startPlayback = async (track: Track, start?: number) => {
    const audio = audioRef.current;
    // 데모 곡은 서버에 없다. 있지도 않은 곡 번호로 청취 기록을 남길 수 없다.
    if (typeof track.musicId === 'number') {
      analytics.startPlay({ trackId: track.musicId, entryPath: entryPathOf() }, start ?? 0);
    }
    if (isStreamed(track)) {
      loadTrack(track);
      const player = getPlayer();
      if (!player) return;
      advancing.current = false;
      try {
        await player.load(track.musicId, { autoplay: true });
        if (start !== undefined && start > 0) await player.seek(start);
      } catch {
        setToast('음원을 불러오지 못했어요. 다시 재생해 주세요.');
      }
      setHistory((ids) =>
        [track.id, ...ids.filter((i) => i !== track.id)].slice(0, HISTORY_LIMIT),
      );
      return;
    }
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
      [track.id, ...ids.filter((i) => i !== track.id)].slice(0, HISTORY_LIMIT),
    );
  };
  const play = (track: Track = activeRef.current, fromMoment = false) => {
    const audio = audioRef.current;
    previewEnd.current = null;
    if (!queueIds.includes(track.id)) setQueueIds(tracks.map((t) => t.id));
    if (!fromMoment && activeRef.current.id === track.id && isStreamed(track)) {
      const player = getPlayer();
      if (player && player.store.get().status === 'playing') {
        player.pause();
        return;
      }
    }
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

  // 스트리밍 엔진은 React 밖에서 돈다. 상태를 훅의 값으로 옮겨 화면이 기존과
  // 똑같은 모양(playing / time)을 보게 한다.
  const advanceRef = useRef<() => void>(() => {});
  advanceRef.current = () => next(1);
  useEffect(() => {
    const player = getPlayer();
    if (!player) return;
    return player.store.subscribe(() => {
      if (!isStreamed(activeRef.current)) return;
      const state = player.store.get();
      setBuffering(state.status === 'loading' || state.status === 'buffering');
      // 버퍼를 기다리는 동안에도 사용자 입장에서는 '재생 중'이다.
      setPlaying(state.status === 'playing' || state.status === 'buffering');
      setTime(state.positionSec);
      if (state.status === 'error' && state.error) setToast(state.error);
      if (state.status === 'ended' && !advancing.current) {
        advancing.current = true;
        advanceRef.current();
      }
    });
  }, []);

  // 대기열에서 다음에 올 스트리밍 곡들의 앞 5초를 미리 받아 둔다.
  useEffect(() => {
    const upcoming = queueTracks
      .filter((track) => isStreamed(track) && track.id !== active.id)
      .map((track) => track.musicId!)
      .slice(0, 3);
    getPlayer()?.setQueue(upcoming);
  }, [queueIds, active.id, ignored]);
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
    // 구간을 저장하면 그 곡을 좋아한 것으로 본다. 계정에도 같이 남겨야 한다.
    if (!liked.includes(saved.track.id)) toggleLike(saved.track.id);
    setToast('좋아한 순간을 저장했어요. 내 플레이리스트에서 다시 만나요.');
  };
  /**
   * 좋아요를 켜고 끈다.
   *
   * 화면을 먼저 바꾸고 서버에 보낸다. 하트는 누르자마자 반응해야 한다.
   * 서버가 거절하면 되돌린다 — 저장된 줄 알면 안 된다.
   *
   * 한 곡에 반응은 하나라 좋아요를 켜면 "관심 없음" 은 풀린다.
   */
  const toggleLike = (id: number) => {
    const wasLiked = liked.includes(id);
    const wasIgnored = ignored.includes(id);
    setLiked((ids) => (wasLiked ? ids.filter((i) => i !== id) : [...ids, id]));
    if (!wasLiked && wasIgnored) setIgnored((ids) => ids.filter((i) => i !== id));
    if (demoSession?.kind !== 'authenticated') return;
    pushReaction(tracks, id, wasLiked ? null : 'LIKE').catch(() => {
      if (!mountedRef.current) return;
      setLiked((ids) => (wasLiked ? [...ids, id] : ids.filter((i) => i !== id)));
      if (!wasLiked && wasIgnored) setIgnored((ids) => (ids.includes(id) ? ids : [...ids, id]));
      setToast('좋아요를 저장하지 못했어요. 다시 시도해 주세요.');
    });
  };

  /** 추천에서 숨긴다. 좋아요와 같은 자리를 쓰므로 좋아요는 풀린다. */
  const ignoreTrack = (id: number) => {
    if (ignored.includes(id)) return;
    const wasLiked = liked.includes(id);
    setIgnored((ids) => [...ids, id]);
    if (wasLiked) setLiked((ids) => ids.filter((i) => i !== id));
    if (demoSession?.kind !== 'authenticated') return;
    pushReaction(tracks, id, 'DISLIKE').catch(() => {
      if (!mountedRef.current) return;
      setIgnored((ids) => ids.filter((i) => i !== id));
      if (wasLiked) setLiked((ids) => (ids.includes(id) ? ids : [...ids, id]));
      setToast('이 곡을 숨기지 못했어요. 다시 시도해 주세요.');
    });
  };

  /** 숨긴 곡을 모두 되돌린다. */
  const clearIgnored = () => {
    const before = ignored;
    setIgnored([]);
    if (demoSession?.kind !== 'authenticated' || !before.length) return;
    Promise.all(before.map((id) => pushReaction(tracks, id, null))).catch(() => {
      if (!mountedRef.current) return;
      setIgnored(before);
      setToast('숨긴 곡을 되돌리지 못했어요. 다시 시도해 주세요.');
    });
  };
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
    genres,
    updateMoods,
    active,
    playing,
    setPlaying,
    buffering,
    time,
    volume,
    setVolume,
    liked,
    ignored,
    ignoreTrack,
    clearIgnored,
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
