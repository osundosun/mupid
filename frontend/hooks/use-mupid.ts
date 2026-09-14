'use client';
import { useEffect, useRef, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { guardPath, pageForPath, pagePaths } from '@/lib/routes';
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
  const [onboardingSubmittedAt, setOnboardingSubmittedAt] = useState<string | null>(null);
  const onboardingRef = useRef<string | null>(null);
  const sessionRef = useRef<AccountSession | null>(null);
  // 새로고침 시 세션 복원은 아직 미연결이다. 추후 서버의 세션 확인 응답으로 초기화해야 한다.
  const [page, setPage] = useState<Page>('welcome');
  const [nickname, setNickname] = useState(initial.nickname);
  const [moods, setMoods] = useState(initial.moods);
  const [genres, setGenres] = useState<string[]>(initial.genres ?? []);
  // 마지막으로 들은 곡을 기억해 미니 플레이어를 계속 띄워 둔다. 한 곡도 듣지 않았다면 null.
  const [lastPlayedId, setLastPlayedId] = useState<number | null>(initial.lastPlayedId ?? null);
  const [active, setActive] = useState<Track | null>(
    tracks.find((t) => t.id === initial.lastPlayedId) ?? tracks[0] ?? null,
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
  // 세션 복원이 끝났는가. 끝나기 전에는 화면 가드를 돌리지 않는다.
  const [authChecked, setAuthChecked] = useState(false);
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
  const activeRef = useRef(active);
  const mountedRef = useRef(true);
  const previewEnd = useRef<number | null>(null);
  /** 종료를 처리한 곡. 한 곡의 종료로 두 번 넘어가지 않게 한다. */
  const endedFor = useRef<number | null>(null);
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
    // 로그인 여부를 알기 전에는 판정하지 않는다. 알기도 전에 보내면 주소를 잃는다.
    if (!authChecked) return;
    // 예전 ?view= 링크는 호환하되 새로운 화면 이동은 경로 주소를 사용한다.
    const legacy = new URLSearchParams(window.location.search).get('view');
    const requested = legacy && Object.hasOwn(pagePaths, legacy)
      ? pagePaths[legacy as keyof typeof pagePaths] : pathname === '/mupid' ? '/home' : pathname;
    const target = guardPath(requested, !!demoSession, !!onboardingSubmittedAt);
    setPage(pageForPath(target));
    setLoginDialog(target === '/login' || target === '/signup');
    if (target !== pathname || legacy) router.replace(target);
  }, [authChecked, pathname, demoSession, onboardingSubmittedAt, router]);
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
  /**
   * 온보딩 결과를 저장한다.
   *
   * 저장이 성공하기 전에는 홈으로 이동하거나 완료 표시를 남기지 않는다.
   * 로그인한 사람은 계정에도 남는다. 서버 저장이 실패하면 던져서 화면이
   * 알리게 한다 — 브라우저에만 남고 기기를 바꾸면 사라지는 걸 조용히 넘기면
   * 안 된다.
   */
  const completeOnboarding = async (name: string, selectedMoods: string[], selectedGenres: string[]) => {
    if (!sessionRef.current) throw new Error('로그인이 필요해요.');
    // 장르·분위기는 서버가 준 선택지에서 고른다. 데모 곡의 장르로 거르지 않는다.
    if (!name.trim() || !selectedMoods.length || !selectedGenres.length)
      throw new Error('이름, 장르와 분위기를 선택해 주세요.');
    const submittedAt = new Date().toISOString();
    const value = { onboardingSubmittedAt: submittedAt, nickname: name.trim().slice(0, 10), moods: selectedMoods, genres: selectedGenres, liked, ignored, history, moments, playlists, lastPlayedId };
    const save = saveQueue.current.catch(() => {}).then(() => mupidService.savePreferences(value));
    saveQueue.current = save;
    await save;
    if (demoSession?.kind === 'authenticated') {
      await saveOnboarding({ nickname: value.nickname, moods: selectedMoods, genres: selectedGenres });
    }
    setNickname(value.nickname); setMoods(selectedMoods); setGenres(selectedGenres); setOnboardingSubmittedAt(submittedAt);
    onboardingRef.current = submittedAt;
    setPage('home');
    router.replace('/home');
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
   * 프로필 화면에서 선호 장르를 바꾼다. 분위기와 같은 규칙이다.
   */
  const updateGenres = (next: (list: string[]) => string[]) => {
    const before = genres;
    const after = next(before);
    setGenres(after);
    if (demoSession?.kind !== 'authenticated' || !onboardingRef.current) return;
    if (!after.length || !moods.length) {
      setGenres(before);
      setToast('장르는 하나 이상 골라야 해요.');
      return;
    }
    saveOnboarding({ nickname, moods, genres: after }).catch(() => {
      if (!mountedRef.current) return;
      setGenres(before);
      setToast('선호 장르를 저장하지 못했어요. 다시 시도해 주세요.');
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
  /**
   * 세션 복원.
   *
   * 액세스 토큰은 메모리에만 있다. 새로고침 뒤에는 쿠키의 리프레시 토큰으로
   * 한 번만 복원을 시도하고, 실패하면 비로그인 상태로 둔다.
   *
   * 끝나기 전에는 화면 가드를 돌리지 않는다. 먼저 돌리면 로그인한 사람이
   * `/explore` 를 새로고침했을 때 비로그인으로 판정돼 `/login` 으로 밀리고,
   * 복원이 끝난 뒤에는 원래 주소가 아니라 `/home` 으로 떨어진다.
   * 온보딩 여부까지 확인해야 `/onboarding` 으로 잘못 보내지 않는다.
   */
  useEffect(() => {
    const abort = new AbortController();
    restoreSession()
      .then(async (session) => {
        if (abort.signal.aborted || session.status !== 'authenticated' || !session.user) return;
        const restored: AccountSession = { kind: 'authenticated', nickname: session.user.nickname, userId: session.user.user_id };
        // sessionRef 를 채워야 설정 저장과 온보딩 제출이 열린다.
        sessionRef.current = restored;
        setNickname(session.user.nickname);
        setDemoSession(restored);
        // 온보딩 여부는 가드가 쓰므로 기다린다. 나머지는 화면을 막지 않는다.
        await adoptServerOnboarding(abort.signal);
        void adoptServerReactions(abort.signal);
        void adoptServerHistory(abort.signal);
      })
      .catch(() => {})
      .finally(() => { if (!abort.signal.aborted) setAuthChecked(true); });
    return () => { abort.abort(); };
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
    void adoptServerOnboarding();
    void adoptServerReactions();
    void adoptServerHistory();
    return true;
  };
  /**
   * 로그아웃.
   *
   * 로그인해야 쓸 수 있는 서비스라 나갈 때는 **재생을 멈추고 계정에서 끌어온
   * 것을 모두 비운다.** 남겨 두면 다음 사람이 앞사람의 좋아요와 감상 기록을
   * 보게 된다. 스트리밍 재생기도 따로 멈춰야 한다 — `<audio>` 만 멈추면
   * 청크로 재생 중이던 소리는 계속 난다.
   */
  const signOutDemo = async () => {
    try { await logout(); } catch { setToast('로그아웃하지 못했어요. 다시 시도해 주세요.'); return; }
    setDemoSession(null);
    sessionRef.current = null;
    onboardingRef.current = null;
    setOnboardingSubmittedAt(null);
    getPlayer()?.stop();
    setPlaying(false);
    // 계정에서 끌어온 값은 계정과 함께 놓는다.
    setLiked([]);
    setIgnored([]);
    setHistory([]);
    setMoods([]);
    setGenres([]);
    setPage('welcome');
    // 보던 화면이 보호된 곳이라 가드가 어차피 로그인으로 보낸다. 코드도 그렇게 적는다.
    router.replace('/login');
    setToast('로그아웃했어요. 다시 로그인하면 이어서 들을 수 있어요.');
  };

  const loadTrack = (track: Track) => {
    if (activeRef.current?.id === track.id) return;
    activeRef.current = track;
    setActive(track);
    setTime(0);
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
      setActive((current) => (current && current.id === active.id ? { ...current, sections } : current));
      if (activeRef.current) activeRef.current = { ...activeRef.current, sections };
    });
    return () => abort.abort();
  }, [active?.id]);
  /*
   * 로그인 뒤에 곡 목록이 들어오면 고를 곡을 잡아 둔다.
   *
   * 목록은 로그인한 사람만 받을 수 있어서, 비로그인으로 앱이 열릴 때는 비어
   * 있다가 로그인 뒤에 채워진다. `active` 는 처음 한 번만 정해지므로 여기서
   * 뒤늦게 들어온 목록을 받아 준다. 재생을 시작하지는 않는다.
   */
  useEffect(() => {
    if (active || !tracks.length) return;
    const pick = tracks.find((t) => t.id === lastPlayedId) ?? tracks[0];
    activeRef.current = pick;
    setActive(pick);
  }, [tracks, active, lastPlayedId]);
  const seek = (value: number) => {
    // 내부 위치 이동 함수. 화면에서 호출하는 seek는 반환 모델에서 순간 미리듣기도 해제한다.
    if (!activeRef.current) return;
    const next = Math.max(0, Math.min(value, activeRef.current.duration));
    setTime(next);
    void getPlayer()?.seek(next);
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
  /**
   * 곡을 튼다. 재생 경로는 청크 스트리밍 하나뿐이다.
   *
   * 요청마다 번호를 매겨, 빠르게 곡을 바꿨을 때 **뒤늦게 끝난 이전 요청이
   * 지금 곡을 덮어쓰지 않게** 한다. 곡 변경이 간혹 먹지 않던 것이 이것 때문이다.
   */
  const startPlayback = async (track: Track, start?: number) => {
    if (typeof track.musicId !== 'number') return;
    analytics.startPlay({ trackId: track.musicId, entryPath: entryPathOf() }, start ?? 0);
    const id = ++requestId.current;
    loadTrack(track);
    setLastPlayedId(track.id);
    setHistory((ids) =>
      [track.id, ...ids.filter((i) => i !== track.id)].slice(0, HISTORY_LIMIT),
    );
    const player = getPlayer();
    if (!player) return;
    try {
      await player.load(track.musicId, { autoplay: true });
      if (id !== requestId.current) return;
      if (start !== undefined && start > 0) await player.seek(start);
    } catch {
      if (id === requestId.current && mountedRef.current) {
        setToast('음원을 불러오지 못했어요. 다시 재생해 주세요.');
      }
    }
  };
  const play = (track: Track | null = activeRef.current, fromMoment = false) => {
    // 곡이 하나도 없으면 누를 것도 없다.
    if (!track) return;
    previewEnd.current = null;
    if (!queueIds.includes(track.id)) {
      // 목록 밖의 곡을 고르면 그 곡을 맨 앞에 두고 상한까지만 다시 채운다.
      setQueueIds([track.id, ...tracks.map((t) => t.id).filter((id) => id !== track.id)].slice(0, QUEUE_LIMIT));
    }
    const player = getPlayer();
    // 지금 울리는 곡을 다시 누르면 멈춘다. 멈춘 곡을 누르면 이어서 튼다.
    if (!fromMoment && activeRef.current?.id === track.id && player) {
      const status = player.store.get().status;
      if (status === 'playing' || status === 'buffering') {
        player.pause();
        return;
      }
      if (status === 'paused') {
        void player.play();
        return;
      }
    }
    void startPlayback(track, fromMoment ? track.moment : undefined);
  };
  const next = (direction = 1) => {
    previewEnd.current = null;
    const pool = queueIds
      .map((id) => tracks.find((t) => t.id === id)!)
      .filter((t) => t && !ignored.includes(t.id));
    if (!pool.length) return;
    const index = pool.findIndex((t) => t.id === activeRef.current?.id);
    const candidates = pool.filter((t) => t.id !== activeRef.current?.id);
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
  const onEndedRef = useRef<() => void>(() => {});
  useEffect(() => {
    const player = getPlayer();
    if (!player) return;
    return player.store.subscribe(() => {
      const state = player.store.get();
      setBuffering(state.status === 'loading' || state.status === 'buffering');
      // 버퍼를 기다리는 동안에도 사용자 입장에서는 '재생 중'이다.
      setPlaying(state.status === 'playing' || state.status === 'buffering');
      setTime(state.positionSec);
      if (state.status === 'error' && state.error) setToast(state.error);

      // 구간 미리듣기는 끝 지점에서 멈춘다. 곡이 끝난 것이 아니므로
      // 다음 곡으로 넘어가지 않는다.
      const stopAt = previewEnd.current;
      if (stopAt !== null && state.status === 'playing' && state.positionSec >= stopAt) {
        previewEnd.current = null;
        player.pause();
        setTime(stopAt);
        return;
      }

      // 새로 재생이 시작되면 다음 종료는 새 사건이다.
      if (state.status === 'playing') endedFor.current = null;
      // **한 곡의 종료로는 한 번만 넘어간다.** 예전에는 불리언 한 개로 막았는데,
      // 다음 곡을 불러오는 동안 상태가 다시 흘러나오면 두 번 넘어가 곡을
      // 건너뛰거나 로딩이 뒤엉켜 멈췄다.
      if (state.status === 'ended' && endedFor.current !== state.musicId) {
        endedFor.current = state.musicId;
        onEndedRef.current();
      }
    });
  }, []);

  /**
   * 대기열에서 다음에 올 스트리밍 곡들의 앞 5초를 미리 받아 둔다.
   *
   * 로그인 전에는 받지 않는다. 스트림 API 가 인증을 요구하므로 401 만 쌓이고,
   * 애초에 로그인 전에는 재생 화면에 들어갈 수도 없다.
   */
  useEffect(() => {
    if (demoSession?.kind !== 'authenticated') {
      getPlayer()?.setQueue([]);
      return;
    }
    const upcoming = queueTracks
      .filter((track) => isStreamed(track) && track.id !== active?.id)
      .map((track) => track.musicId!)
      .slice(0, 3);
    getPlayer()?.setQueue(upcoming);
  }, [queueIds, active?.id, ignored, demoSession]);
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
  /**
   * 곡이 끝났다. 반복·셔플 규칙에 따라 다음 곡을 고른다.
   *
   * 재생 경로가 하나뿐이라 이 함수도 한 곳에서만 불린다 — 재생기의 `ended`
   * 상태를 보는 구독자다. 두 경로가 각자 끝을 판정하던 때는 서로 겹쳐
   * 곡을 건너뛰곤 했다.
   */
  const onEnded = () => {
    if (previewEnd.current !== null) {
      previewEnd.current = null;
      return;
    }
    const current = activeRef.current;
    if (!current) return;
    if (repeatMode === 'one') { void startPlayback(current, 0); return; }
    playedInCycle.current.add(current.id);
    const pool = queueTracks;
    const index = pool.findIndex(t => t.id === current.id);
    let remaining = pool.filter(t => !playedInCycle.current.has(t.id));
    const finished = shuffle ? remaining.length === 0 : index >= pool.length - 1;
    // 목록 끝에 닿았을 때, 전체 반복이면 한 바퀴를 다시 돌고 아니면 멈춘다.
    if (!pool.length || (finished && repeatMode === 'off')) {
      getPlayer()?.pause(); setPlaying(false); return;
    }
    if (finished) { playedInCycle.current.clear(); remaining = pool; }
    const target = shuffle ? remaining[Math.floor(Math.random() * remaining.length)] : pool[(index + 1) % pool.length];
    playedInCycle.current.add(target.id);
    void startPlayback(target, 0);
  };
  // 구독자는 한 번만 등록되므로 최신 규칙을 참조로 넘긴다.
  onEndedRef.current = onEnded;
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
  const openMoment = (track: Track | null = activeRef.current, editing: SavedMoment | null = null) => {
    if (!track) return;
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
      Math.min(activeRef.current?.id === track.id ? time : track.moment, track.duration),
    );
    setMomentRange([part.start, part.end]);
    setMomentSection(part.label);
    go('moment');
  };
  const previewMoment = () => {
    if (!activeRef.current) return;
    previewEnd.current = momentRange[1];
    void startPlayback(activeRef.current, momentRange[0]);
  };
  const playSavedMoment = (moment: SavedMoment) => {
    const player = getPlayer();
    // 듣고 있던 그 구간을 다시 누르면 멈춘다.
    if (
      activeRef.current?.id === moment.track.id &&
      previewEnd.current === moment.end &&
      player?.store.get().status === 'playing'
    ) {
      previewEnd.current = null;
      player.pause();
      return;
    }
    previewEnd.current = moment.end;
    void startPlayback(moment.track, moment.start);
  };
  const saveMoment = () => {
    const current = activeRef.current;
    if (!current) return;
    const saved: SavedMoment = {
      id: Date.now(),
      track: current,
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
    setToast('저장한 순간을 저장했어요. 내 플레이리스트에서 다시 만나요.');
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
    authReady: authChecked,
    loginDialog,
    setLoginDialog,
    loginTriggerRef,
    openLogin,
    signInDemo,
    signOutDemo,
    moods,
    genres,
    updateMoods,
    updateGenres,
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
    showAllSimilar,
    setShowAllSimilar,
    messageEndRef,
    chatInputRef,
    chatButtonRef,
    play,
    next,
    /**
     * 화면에서 재생 위치를 옮긴다.
     *
     * **이미 들고 있는 곡이면 그 자리만 옮긴다.** 예전에는 `startPlayback` 을
     * 다시 불러 곡을 통째로 다시 걸었다. 막대를 끄는 동안 `change` 가 계속
     * 들어오는데 그때마다 청취 이벤트가 나가고 최근 감상에 쌓였고, 다시 건
     * 재생이 옛 자리에서 시작했다가 뒤늦게 목적지로 튀어 막대가 요동쳤다.
     */
    seek: (value: number) => {
      if (!Number.isFinite(value)) return;
      previewEnd.current = null;
      const track = activeRef.current;
      if (!track) return;
      const next = Math.max(0, Math.min(value, track.duration));
      const player = getPlayer();
      // 아직 이 곡을 들고 있지 않으면 그 자리에서 새로 튼다.
      if (!player || player.store.get().musicId !== track.musicId) {
        void startPlayback(track, next);
        return;
      }
      setTime(next);
      void player.seek(next);
    },
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
