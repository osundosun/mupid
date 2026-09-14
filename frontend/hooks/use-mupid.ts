'use client';
import { useEffect, useRef, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { guardPath, pageForPath, pagePaths } from '@/lib/routes';
import { mupidService } from '@/lib/services';
import { login, logout, restoreSession } from '@/lib/api/session';
import { getPlayer } from '@/lib/player';
import { isStreamed } from '@/lib/stream-catalog';
import type { Bootstrap, AccountSession } from '@/lib/services/contracts';
import {
  getSongSectionAt,
  type Track,
  type Page,
  type Playlist,
  type SavedMoment,
  type ChatMessage,
} from '@/lib/mupid-data';

// 반복 모드: 반복 없음 · 전체 반복 · 한곡 반복. 셔플은 이와 별개로 켜고 끈다.
export type RepeatMode = 'off' | 'all' | 'one';

// 재생 목록은 끝없이 쌓이면 훑기도, 순서를 바꾸기도 어렵다. 여기까지만 담는다.
export const QUEUE_LIMIT = 100;

export function useMupid(bootstrap: Bootstrap) {
  const router = useRouter();
  const pathname = usePathname() || '/';
  // 화면들은 이 훅의 모델을 공유한다. 재생기나 로그인 상태를 화면마다 별도로 만들지 않는다.
  const { tracks, preferences: initial } = bootstrap;
  // Local preferences are not proof of authentication or account onboarding.
  const [onboardingSubmittedAt, setOnboardingSubmittedAt] = useState<string | null>(bootstrap.session?.onboardingSubmittedAt ?? null);
  const onboardingRef = useRef<string | null>(bootstrap.session?.onboardingSubmittedAt ?? null);
  const sessionRef = useRef<AccountSession | null>(bootstrap.session ?? null);
  // 새로고침 시 세션 복원은 아직 미연결이다. 추후 서버의 세션 확인 응답으로 초기화해야 한다.
  const [page, setPage] = useState<Page>('welcome');
  const [nickname, setNickname] = useState(initial.nickname);
  const [moods, setMoods] = useState(initial.moods);
  const [genres, setGenres] = useState<string[]>(initial.genres ?? []);
  // 마지막으로 들은 곡을 기억해 미니 플레이어를 계속 띄워 둔다. 한 곡도 듣지 않았다면 null.
  const [lastPlayedId, setLastPlayedId] = useState<number | null>(initial.lastPlayedId ?? null);
  const [active, setActive] = useState<Track>(
    tracks.find((t) => t.id === initial.lastPlayedId) ?? tracks[0],
  );
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
  // 셔플은 재생 목록 안에서 중복 없이 섞어 재생한다. 반복과 함께 켤 수 있다.
  const [shuffle, setShuffle] = useState(false);
  const [repeatMode, setRepeatMode] = useState<RepeatMode>('off');
  const [queueIds, setQueueIds] = useState<number[]>(tracks.slice(0, QUEUE_LIMIT).map((t) => t.id));
  const [toast, setToast] = useState('');
  const [ready, setReady] = useState(false);
  const [demoSession, setDemoSession] = useState<AccountSession | null>(bootstrap.session ?? null);
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
      text: '지금 듣는 곡의 저장한 순간에서 새로운 음악을 찾아볼까요? 원하는 느낌을 들려주세요.',
    },
  ]);
  const [momentRange, setMomentRange] = useState<number[]>([70, 92]);
  // 저장한 순간을 열어 고치는 중이면 그 id를 담는다. 새로 만들 때는 null.
  const [editingMomentId, setEditingMomentId] = useState<number | null>(null);
  const [momentSection, setMomentSection] = useState('코러스 2');
  const [playlistDialog, setPlaylistDialog] = useState(false);
  const [deletePlaylist, setDeletePlaylist] = useState<number | null>(null);
  const [renamePlaylist, setRenamePlaylist] = useState<number | null>(null);
  const [showAllSimilar, setShowAllSimilar] = useState(false);
  const audioRef = useRef<HTMLAudioElement>(null);
  const activeRef = useRef(active);
  const mountedRef = useRef(true);
  const seekOnLoad = useRef<number | null>(null);
  const previewEnd = useRef<number | null>(null);
  const advancing = useRef(false);
  const playedInCycle = useRef<Set<number>>(new Set());
  const requestId = useRef(0);
  const messageEndRef = useRef<HTMLDivElement>(null);
  const chatInputRef = useRef<HTMLInputElement>(null);
  const chatButtonRef = useRef<HTMLButtonElement>(null);
  const chatTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // 어디서 들어왔든 그 화면으로 돌아간다. 앱을 바로 열었으면 홈으로.
  const goBack = () => {
    if (typeof window !== 'undefined' && window.history.length > 1) router.back();
    else router.push(pagePaths.home);
  };
  const go = (next: Page) => {
    // URL 직접 이동과 UI 이동 모두 같은 조건으로 보호한다. 실제 접근 권한 검증은 서버 책임이다.
    router.push(guardPath(pagePaths[next], !!sessionRef.current, !!onboardingRef.current));
  };
  useEffect(() => {
    // 예전 ?view= 링크는 호환하되 새로운 화면 이동은 경로 주소를 사용한다.
    const legacy = new URLSearchParams(window.location.search).get('view');
    const requested = legacy && Object.hasOwn(pagePaths, legacy)
      ? pagePaths[legacy as keyof typeof pagePaths] : pathname === '/mupid' ? '/home' : pathname;
    const target = guardPath(requested, !!demoSession, !!onboardingSubmittedAt, demoSession?.kind === 'demo');
    setPage(pageForPath(target));
    setLoginDialog(target === '/login' || target === '/signup');
    if (target !== pathname || legacy) router.replace(target);
  }, [pathname, demoSession, onboardingSubmittedAt, router]);
  useEffect(() => {
    mountedRef.current = true;
    queueMicrotask(() => {
      if (!mountedRef.current) return;
      setReady(true);
    });
    return () => {
      mountedRef.current = false;
      if (chatTimer.current) clearTimeout(chatTimer.current);
    };
  }, [initial.onboardingSubmittedAt]);
  useEffect(() => {
    if (!ready || !sessionRef.current) return;
    saveQueue.current = saveQueue.current.catch(() => {}).then(() =>
      mupidService.savePreferences({ onboardingSubmittedAt, nickname, moods, genres, liked, ignored, history, moments, playlists, lastPlayedId })
    ).catch(() => { if (mountedRef.current) setToast('변경 내용을 저장하지 못했어요. 다시 시도해 주세요.'); });
  }, [ready, onboardingSubmittedAt, nickname, moods, genres, liked, ignored, history, moments, playlists, lastPlayedId]);
  const completeOnboarding = async (name: string, selectedMoods: string[], selectedGenres: string[]) => {
    // 저장이 성공하기 전에는 홈으로 이동하거나 완료 표시를 남기지 않는다.
    if (!sessionRef.current) throw new Error('로그인이 필요해요.');
    if (!name.trim() || !selectedMoods.length || !selectedGenres.length || selectedGenres.some(genre => !tracks.some(t => t.genre === genre))) throw new Error('이름, 장르와 분위기를 선택해 주세요.');
    const submittedAt = new Date().toISOString();
    const value = { onboardingSubmittedAt: submittedAt, nickname: name.trim().slice(0, 20), moods: selectedMoods, genres: selectedGenres, liked, ignored, history, moments, playlists, lastPlayedId };
    const save = saveQueue.current.catch(() => {}).then(() => mupidService.savePreferences(value));
    saveQueue.current = save;
    await save;
    setNickname(value.nickname); setMoods(selectedMoods); setOnboardingSubmittedAt(submittedAt);
    setGenres(selectedGenres);
    onboardingRef.current = submittedAt;
    setPage('home');
    router.replace('/home');
    window.scrollTo(0, 0);
  };
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
    setLoginDialog(true);
    router.push('/login');
  };
  // 액세스 토큰은 메모리에만 있다. 새로고침 뒤에는 쿠키의 리프레시 토큰으로
  // 한 번만 복원을 시도하고, 실패하면 비로그인 상태로 둔다.
  useEffect(() => {
    let alive = true;
    restoreSession().then((session) => {
      if (!alive || session.status !== 'authenticated' || !session.user) return;
      const restored: AccountSession = { kind: 'authenticated', nickname: session.user.nickname, userId: session.user.user_id };
      // sessionRef 를 채워야 설정 저장과 온보딩 제출이 열린다.
      sessionRef.current = restored;
      setNickname(session.user.nickname);
      setDemoSession(restored);
    });
    return () => { alive = false; };
  }, []);
  const signInDemo = async (email: string, password: string) => {
    // 기존 호출부 호환을 위해 이름은 유지했지만 서버 인증을 그대로 거친다.
    const user = await login(email, password);
    const session: AccountSession = { kind: 'authenticated', nickname: user.nickname, userId: user.user_id };
    sessionRef.current = session;
    // 서버 UserInfo 에는 아직 온보딩 완료 시각이 없다. 로컬에 복원해 둔 값을 쓴다.
    const completedAt = onboardingRef.current;
    setOnboardingSubmittedAt(completedAt);
    setNickname(user.nickname);
    setDemoSession(session);
    setLoginDialog(false);
    setPage(completedAt ? 'home' : 'welcome');
    router.replace(completedAt ? '/home' : '/onboarding');
    setToast('뮤피드에 오신 걸 환영해요.');
    return true;
  };
  const signOutDemo = async () => {
    try { await logout(); } catch { setToast('로그아웃하지 못했어요. 다시 시도해 주세요.'); return; }
    setDemoSession(null);
    sessionRef.current = null;
    onboardingRef.current = null;
    setOnboardingSubmittedAt(null);
    audioRef.current?.pause();
    setPlaying(false);
    setPage('welcome');
    router.replace('/');
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
  const seek = (value: number) => {
    // 내부 위치 이동 함수. 화면에서 호출하는 seek는 반환 모델에서 순간 미리듣기도 해제한다.
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
  const startPlayback = async (track: Track, start?: number) => {
    const audio = audioRef.current;
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
        [track.id, ...ids.filter((i) => i !== track.id)].slice(0, 8),
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
    setLastPlayedId(track.id);
    setHistory((ids) =>
      [track.id, ...ids.filter((i) => i !== track.id)].slice(0, 8),
    );
  };
  const play = (track: Track = activeRef.current, fromMoment = false) => {
    const audio = audioRef.current;
    previewEnd.current = null;
    if (!queueIds.includes(track.id)) {
      // 목록 밖의 곡을 고르면 그 곡을 맨 앞에 두고 상한까지만 다시 채운다.
      setQueueIds([track.id, ...tracks.map((t) => t.id).filter((id) => id !== track.id)].slice(0, QUEUE_LIMIT));
    }
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
    .filter((t) => t && !ignored.includes(t.id))
    .slice(0, QUEUE_LIMIT);

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
  const playPlaylist = (playlist: Playlist, track?: Track) => {
    const playable = playlist.tracks.filter((t) => !ignored.includes(t.id));
    if (!playable.length) {
      setToast('재생할 수 있는 곡이 없어요.');
      return;
    }
    // 반복 설정은 곡을 시작해도 유지한다. 랜덤은 한 바퀴 안에서 중복 없이 재생한다.
    const first = track && playable.find(t => t.id === track.id) || (shuffle
      ? playable[Math.floor(Math.random() * playable.length)]
      : playable[0]);
    playedInCycle.current = new Set([first.id]);
    setQueueIds(playable.slice(0, QUEUE_LIMIT).map((t) => t.id));
    previewEnd.current = null;
    void startPlayback(first, 0);
  };
  const onEnded = () => {
    if (advancing.current) return;
    if (previewEnd.current !== null) {
      previewEnd.current = null;
      return;
    }
    advancing.current = true;
    if (repeatMode === 'one') { void startPlayback(activeRef.current, 0); return; }
    playedInCycle.current.add(activeRef.current.id);
    const pool = queueTracks;
    const index = pool.findIndex(t => t.id === activeRef.current.id);
    let remaining = pool.filter(t => !playedInCycle.current.has(t.id));
    const finished = shuffle ? remaining.length === 0 : index >= pool.length - 1;
    // 목록 끝에 닿았을 때, 전체 반복이면 한 바퀴를 다시 돌고 아니면 멈춘다.
    if (!pool.length || (finished && repeatMode === 'off')) {
      audioRef.current?.pause(); setPlaying(false); return;
    }
    if (finished) { playedInCycle.current.clear(); remaining = pool; }
    const target = shuffle ? remaining[Math.floor(Math.random() * remaining.length)] : pool[(index + 1) % pool.length];
    playedInCycle.current.add(target.id);
    void startPlayback(target, 0);
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
  // 공유 링크는 곡만 선택하고 자동 재생하지 않는다.
  useEffect(() => {
    if (pathname !== '/player') return;
    const id = Number(new URLSearchParams(window.location.search).get('track'));
    const track = tracks.find(t => t.id === id);
    if (track) loadTrack(track);
  }, [pathname, tracks]);
  const openMoment = (track = activeRef.current, editing: SavedMoment | null = null) => {
    previewEnd.current = null;
    setEditingMomentId(editing?.id ?? null);
    loadTrack(track);
    if (editing) {
      setMomentRange([editing.start, editing.end]);
      setMomentSection(editing.section);
      go('moment');
      return;
    }
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
    setToast('저장한 순간을 저장했어요. 내 플레이리스트에서 다시 만나요.');
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
    goBack,
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
    setMoods,
    setGenres,
    active,
    playing,
    setPlaying,
    buffering,
    time,
    volume,
    setVolume,
    liked,
    ignored,
    setIgnored,
    history,
    // 한 곡이라도 재생한 적이 있으면 정지 상태여도 미니 플레이어를 유지한다.
    hasPlayed: lastPlayedId !== null,
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
    repeatMode,
    setRepeatMode,
    toast,
    setToast,
    chatOpen,
    setChatOpen,
    chatInput,
    setChatInput,
    chatLoading,
    messages,
    editingMomentId,
    setEditingMomentId,
    momentRange,
    setMomentRange,
    momentSection,
    setMomentSection,
    playlistDialog,
    setPlaylistDialog,
    deletePlaylist,
    setDeletePlaylist,
    renamePlaylist,
    setRenamePlaylist,
    queueTracks,
    // 보이는 순서대로 재정렬한다. 목록에 없던 id는 뒤에 남겨 둔다.
    setQueueOrder: (orderedIds: number[]) => {
      setQueueIds(ids => [...orderedIds, ...ids.filter(id => !orderedIds.includes(id))].slice(0, QUEUE_LIMIT));
    },
    // 재생 목록에서 뺀다. 재생 중인 곡을 빼도 지금 재생은 그대로 이어진다.
    removeFromQueue: (track: Track) => {
      setQueueIds(ids => ids.filter(id => id !== track.id));
      playedInCycle.current.delete(track.id);
      setToast(`‘${track.title}’ 곡을 재생 목록에서 뺐어요.`);
    },
    // 고른 곡을 한 번에 뺀다. 전체 선택 후 삭제하는 흐름에 쓴다.
    removeManyFromQueue: (ids: number[]) => {
      if (!ids.length) return;
      setQueueIds(list => list.filter(id => !ids.includes(id)));
      ids.forEach(id => playedInCycle.current.delete(id));
      setToast(`${ids.length}곡을 재생 목록에서 뺐어요.`);
    },
    // 재생 목록 맨 뒤에 담는다. 이미 있으면 그대로 둔다.
    addToQueue: (track: Track) => {
      if (queueIds.includes(track.id)) { setToast('이미 재생 목록에 있어요.'); return; }
      if (queueIds.length >= QUEUE_LIMIT) {
        setToast(`재생 목록은 ${QUEUE_LIMIT}곡까지 담을 수 있어요. 몇 곡을 먼저 빼주세요.`);
        return;
      }
      setQueueIds(ids => [...ids, track.id]);
      setToast(`‘${track.title}’ 곡을 재생 목록에 담았어요.`);
    },
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
