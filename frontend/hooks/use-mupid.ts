'use client';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { guardPath, pageForPath, pagePaths } from '@/lib/routes';
import { mupidService } from '@/lib/services';
import { localDemoEnabled } from '@/lib/api/local-demo';
import { login, logout, restoreSession, withdrawAccount } from '@/lib/api/session';
import { getPlayer } from '@/lib/player';
import { isStreamed } from '@/lib/stream-catalog';
import {
  fetchNextTracks,
  fetchSimilarSections,
  fetchTodayMoments,
} from '@/lib/recommendations';
import {
  queueEntry,
  queueIndexOf,
  removeQueueEntries,
  removeQueueEntry,
  placeInQueue,
  reorderQueue,
  stepQueue,
  toQueueEntries,
  upcomingQueue,
  type QueueEntry,
} from '@/lib/queue';
import { saveOnboarding } from '@/lib/onboarding';
import { updateNickname } from '@/lib/profile';
import { fetchHistory, fetchReactions, pushReaction } from '@/lib/reactions';
import * as playlistApi from '@/lib/playlists';
import * as queueApi from '@/lib/play-queue';
import { analytics, bindPlayer, type EntryPath } from '@/lib/analytics';
import { fetchSections } from '@/lib/music-analysis';
import { chatErrorMessage, requestChat } from '@/lib/ai-chat';
import { momentPlaybackRanges } from '@/lib/saved-moments';
import { useMediaSession } from './use-media-session';
import { useDismissableOverlay } from './use-dismissable-overlay';
import { closeTopOverlay } from '@/lib/overlay-stack';
import type { Bootstrap, AccountSession } from '@/lib/services/contracts';
import {
  getSongSectionAt,
  type Track,
  type Page,
  type Playlist,
  type SavedMoment,
  type ChatMessage,
} from '@/lib/mupid-data';

/** 화면이 들고 있는 최근 감상 개수. */
const HISTORY_LIMIT = 8;

// 반복 모드: 반복 없음 · 전체 반복 · 한곡 반복. 셔플은 이와 별개로 켜고 끈다.
export type RepeatMode = 'off' | 'all' | 'one';

/**
 * 재생 목록은 끝없이 쌓이면 훑기도, 순서를 바꾸기도 어렵다. 여기까지만 담는다.
 *
 * **서버도 같은 상한을 들고 있다**(`PlayQueueDocument.LIMIT`). 여기 값은 화면이
 * 미리 반응하기 위한 사본이고 기준은 서버다 — decision/0022.
 */
export const QUEUE_LIMIT = 100;

/**
 * 화면에 내보내는 재생 목록의 한 줄.
 *
 * 곡에 **자리 번호**(`uid`)를 덧붙인 것이다. 화면의 `key` · 끌어 옮길 대상 ·
 * 뺄 대상은 모두 이 번호로 가리킨다 (`lib/queue.ts`).
 *
 * 큐에 같은 곡이 두 번 담기지는 않지만(아래 `QueueInsertPosition` 참고) 자리
 * 번호는 그대로 쓴다. 곡 번호를 키로 쓰면 순서를 바꿀 때 React 가 줄을 다시
 * 만들어 끌던 줄이 손에서 놓이고, 건너뛴 곡을 감춘 자리도 되짚을 수 없다.
 */
export type QueueTrack = Track & { uid: string };

/**
 * 담은 곡이 큐의 어디로 들어갈지. 사용자가 고른다.
 *
 * - `bottom` 맨 아래 (기본값)
 * - `top` 맨 위
 * - `afterCurrent` 지금 재생 중인 곡 바로 다음
 *
 * `afterCurrent` 인데 재생 중인 곡이 없으면 맨 아래로 넣고 그 사실을 알린다 —
 * 조용히 다른 자리에 넣으면 설정이 안 먹은 것처럼 보인다.
 */
import type { QueueInsertPosition } from '@/lib/queue';
export type { QueueInsertPosition };

/**
 * 큐를 고친 뒤 서버에 올리기까지 기다리는 시간.
 *
 * 끌어서 순서를 바꾸는 동안 조작마다 왕복을 걸면 목록이 끊긴다. 잠깐 모았다가
 * 한 번에 보낸다. 이 창 안에 화면을 떠나도 잃지 않도록 떠날 때 한 번 더 밀어낸다.
 */
const QUEUE_SAVE_DELAY = 1200;

/** 알림 문구에 쓰는 자리 이름. */
export const INSERT_POSITION_LABEL: Record<QueueInsertPosition, string> = {
  bottom: '맨 아래',
  top: '맨 위',
  afterCurrent: '재생 중인 곡 다음',
};

/**
 * 대화를 열 때 보이는 첫 인사. **새 대화도 이 자리에서 시작한다.**
 *
 * 모듈 수준에 두는 이유는 초기 상태(`useState`)와 새 대화(`startNewChat`)가 같은 문장을
 * 써야 하기 때문이다. 훅 안에 두면 초기 상태가 선언보다 먼저 평가돼 `TS2448`
 * (used before its declaration)이 난다.
 */
const CHAT_GREETING =
  '지금 듣는 곡의 저장한 순간에서 새로운 음악을 찾아볼까요? 원하는 느낌을 들려주세요.';

/** 고른 삽입 위치를 이 기기에 기억해 둔다. 계정이 아니라 기기의 취향이다. */
const QUEUE_INSERT_KEY = 'mupid.queue.insert-position';

const readInsertPosition = (): QueueInsertPosition => {
  try {
    const saved = localStorage.getItem(QUEUE_INSERT_KEY);
    if (saved === 'top' || saved === 'afterCurrent' || saved === 'bottom') return saved;
  } catch {
    // 사생활 보호 창 등에서 막힐 수 있다. 기본값으로 간다.
  }
  return 'bottom';
};

export function useMupid(bootstrap: Bootstrap) {
  const router = useRouter();
  const pathname = usePathname() || '/';
  // 화면들은 이 훅의 모델을 공유한다. 재생기나 로그인 상태를 화면마다 별도로 만들지 않는다.
  const { tracks: catalog, preferences: initial } = bootstrap;
  /**
   * 카탈로그 밖에서 만난 곡들. 검색 결과·추천·계정 큐가 여기로 들어온다.
   *
   * 앱이 처음 받아 오는 카탈로그는 앞 100곡뿐이다(`lib/stream-catalog.ts`).
   * 그런데 재생 목록·좋아요·최근 기록은 모두 **곡 번호**만 들고 있고, 번호를
   * 곡으로 되돌리는 곳은 이 목록 하나다. 그래서 카탈로그 밖의 곡은 담아도
   * 화면이 그 줄을 그리지 못하고 조용히 사라졌다 — 검색으로 찾은 곡의 담기·재생
   * 버튼이 아무 일도 하지 않는 것처럼 보이던 원인이다 (S15P21A204-441).
   *
   * 그래서 **만난 곡은 기억한다.** 담거나 튼 곡, 계정 큐로 받아 온 곡을 여기에
   * 쌓아 두면 그 뒤로는 카탈로그에 있던 곡과 똑같이 다룰 수 있다.
   */
  const [discovered, setDiscovered] = useState<Track[]>([]);
  const tracks = useMemo(
    () => (discovered.length ? [...catalog, ...discovered] : catalog),
    [catalog, discovered],
  );
  /**
   * 카탈로그에 없던 곡을 기억한다. 이미 아는 곡은 그냥 넘긴다.
   *
   * 아무것도 더할 것이 없으면 **같은 배열을 그대로 돌려준다** — 새 배열을 만들면
   * `tracks` 가 매번 달라져 이것을 보는 효과들(계정 큐 저장 등)이 헛돈다.
   */
  const rememberTracks = useCallback((found: Track[]) => {
    if (!found.length) return;
    setDiscovered((current) => {
      const known = new Set([...catalog, ...current].map((track) => track.id));
      const added: Track[] = [];
      for (const track of found) {
        if (known.has(track.id)) continue;
        known.add(track.id);
        added.push(track);
      }
      return added.length ? [...current, ...added] : current;
    });
  }, [catalog]);
  // Local preferences are not proof of authentication or account onboarding.
  // 완료 **시각**은 들고 있지 않는다. 화면이 묻는 것은 "설문으로 보내야
  // 하는가" 하나뿐이고, 서버도 `GET /api/v1/user` 로 불리언만 준다.
  const [onboardingSubmitted, setOnboardingSubmitted] = useState(false);
  /**
   * 서버 추천. 못 받으면 빈 배열이고, 화면이 카탈로그로 물러난다.
   *
   * 물러났다는 사실을 `recommendationsReady` 로 알린다 — 조용히 대신 채우면
   * "추천이 도는 줄" 알고 넘어간다.
   */
  const [todayMoments, setTodayMoments] = useState<Track[]>([]);
  const [todayMomentNote, setTodayMomentNote] = useState('');
  const [forYou, setForYou] = useState<Track[]>([]);
  const [recommendationsReady, setRecommendationsReady] = useState(false);
  const onboardingRef = useRef(false);
  const sessionRef = useRef<AccountSession | null>(null);
  // 새로고침 시 세션 복원은 아직 미연결이다. 추후 서버의 세션 확인 응답으로 초기화해야 한다.
  const [page, setPage] = useState<Page>('welcome');
  const [nickname, setNickname] = useState(initial.nickname);
  const [moods, setMoods] = useState(initial.moods);
  const [genres, setGenres] = useState<string[]>(initial.genres ?? []);
  // 마지막으로 들은 곡을 기억해 미니 플레이어를 계속 띄워 둔다. 한 곡도 듣지 않았다면 null.
  const [lastPlayedId, setLastPlayedId] = useState<number | null>(initial.lastPlayedId ?? null);
  const [active, setActive] = useState<Track | null>(
    tracks.find((t) => t.id === initial.lastPlayedId) ?? null,
  );
  const [playing, setPlaying] = useState(false);
  // 청크를 기다리는 중. 화면은 이 값으로 스피너를 띄운다.
  const [buffering, setBuffering] = useState(false);
  const [time, setTime] = useState(0);
  const [liked, setLiked] = useState(initial.liked);
  const [ignored, setIgnored] = useState<number[]>(initial.ignored);
  const [history, setHistory] = useState(initial.history);
  const [moments, setMoments] = useState<SavedMoment[]>(initial.moments);
  const [playlists, setPlaylists] = useState<Playlist[]>(initial.playlists);
  const [selectedPlaylist, setSelectedPlaylist] = useState<number | null>(null);
  const [libraryTab, setLibraryTab] = useState('playlists');
  const [query, setQuery] = useState('');
  // 같은 검색어를 다시 제출해도 서버 검색을 새로 실행하기 위한 판본.
  const [searchRevision, setSearchRevision] = useState(0);
  const [selectedArtist, setSelectedArtist] = useState<string | null>(null);
  const [moodFilter, setMoodFilter] = useState('전체');
  // 셔플은 재생 목록 안에서 중복 없이 섞어 재생한다. 반복과 함께 켤 수 있다.
  const [shuffle, setShuffle] = useState(false);
  const [repeatMode, setRepeatMode] = useState<RepeatMode>('off');
  // 자리마다 번호를 새로 만드는 일은 처음 한 번뿐이다. 매 렌더마다 만들면
  // 화면의 key 가 계속 바뀌어 목록이 통째로 다시 그려진다.
  const [queue, setQueue] = useState<QueueEntry[]>(() =>
    toQueueEntries(tracks.slice(0, QUEUE_LIMIT).map((t) => t.id)),
  );
  /**
   * 지금 듣고 있는 **자리**.
   *
   * 목록 밖의 곡(저장한 순간 등)을 듣는 중이면 null 이다. 담는 자리를
   * `afterCurrent` 로 고른 사람에게는 이 값이 있고 없고가 곧 갈림길이다.
   */
  const [currentUid, setCurrentUid] = useState<string | null>(null);
  const currentUidRef = useRef<string | null>(null);
  /** ✕ 로 짚어 둔 자리. 확인 대화상자를 지나는 동안만 들고 있는다. */
  const pendingRemoval = useRef<string | null>(null);
  /** 담은 곡이 들어갈 자리. 기기에 기억해 두고 화면에서 바꾼다. */
  const [queueInsertPosition, setInsertPosition] = useState<QueueInsertPosition>(readInsertPosition);
  /** 계정 큐를 이미 받아 왔는가. 카탈로그가 늦게 와도 한 번은 받아 오게 한다. */
  const queueAdopted = useRef(false);
  /**
   * 서버 큐의 판본. 저장할 때 함께 보내 다른 기기의 변경을 덮지 않게 한다.
   *
   * 아직 계정 큐를 읽지 못했으면 `null` 이다. **그 동안에는 저장하지 않는다** —
   * 판본을 모르는 채로 올리면 계정에 있던 큐를 화면의 초기값으로 지운다.
   */
  const queueRevision = useRef<number | null>(null);
  /**
   * 마지막으로 계정에 올린 큐. 지금 상태가 이것과 같으면 보낼 것이 없다.
   *
   * "고쳤다" 는 표시(dirty 플래그) 대신 **마지막으로 보낸 값과 비교**한다.
   * 표시 방식은 저장 요청이 도는 동안 들어온 변경을 놓치거나 두 번 보내기 쉽다.
   */
  const savedQueue = useRef<{ ids: number[]; currentId: number | null } | null>(null);
  /**
   * 아직 서버에 올리지 못한 큐. 화면을 떠나기 직전에 이것을 밀어낸다.
   *
   * 모아서 보내는 구조라 **보내기 전에 탭이 닫히면 그 변경이 통째로 사라진다.**
   * 순서를 바꾸자마자 새로고침하면 옛 순서로 돌아가던 것이 이것이었다.
   */
  const pendingQueueSave = useRef<
    { ids: number[]; currentId: number | null; revision: number } | null
  >(null);
  /** 재생 목록 독이 펼쳐져 있는가. */
  const [queueOpen, setQueueOpen] = useState(false);
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
      text: CHAT_GREETING,
    },
  ]);
  /**
   * 이어 가는 대화 세션 번호. 서버가 발급하고 응답으로 돌려준다.
   *
   * **`null` 이면 새 대화다** — 첫 턴이거나 사용자가 대화를 지운 뒤다. 번호를 여기서
   * 만들어 내지 않는다(그건 서버 몫이다). 이 값을 다음 요청에 그대로 실어야 한 대화가
   * `ai_chat_sessions` 한 행으로 남는다 — 안 실으면 매 턴 새 세션이 열린다
   * (S15P21A204-375).
   */
  const chatSessionRef = useRef<number | null>(null);
  const [momentRange, setMomentRange] = useState<number[]>([70, 92]);
  const [momentAnchorTime, setMomentAnchorTime] = useState(0);
  const [momentOpenRevision, setMomentOpenRevision] = useState(0);
  // 저장한 순간을 열어 고치는 중이면 그 id를 담는다. 새로 만들 때는 null.
  const [editingMomentId, setEditingMomentId] = useState<number | null>(null);
  const [momentSection, setMomentSection] = useState('코러스 2');
  const [playlistDialog, setPlaylistDialog] = useState(false);
  const [deletePlaylist, setDeletePlaylist] = useState<number | null>(null);
  const [renamePlaylist, setRenamePlaylist] = useState<number | null>(null);
  const [showAllSimilar, setShowAllSimilar] = useState(false);
  /*
   * 뒤로가기로 닫혀야 하는 오버레이들을 스택에 등록한다 (S15P21A204-410).
   *
   * 상태는 각자 쓰던 `useState` 그대로다 — 여기서는 "무엇이 열려 있는가"만
   * 적는다. **가장 위(최근에 연 것)** 가 뒤로가기 한 번에 닫히려면 이 순서가
   * 화면에 그려지는 순서와 같아야 하므로, 등록은 여기 한 곳에 모아 둔다
   * (호출 순서가 곧 스택 순서다).
   */
  useDismissableOverlay(loginDialog, () => setLoginDialog(false));
  useDismissableOverlay(playlistDialog, () => setPlaylistDialog(false));
  useDismissableOverlay(renamePlaylist !== null, () => setRenamePlaylist(null));
  useDismissableOverlay(deletePlaylist !== null, () => setDeletePlaylist(null));
  useDismissableOverlay(selectedArtist !== null, () => setSelectedArtist(null));
  useDismissableOverlay(queueOpen, () => setQueueOpen(false));
  useDismissableOverlay(chatOpen, () => setChatOpen(false));
  const activeRef = useRef(active);
  const mountedRef = useRef(true);
  const previewEnd = useRef<number | null>(null);
  const momentSequence = useRef<{ trackId: number; ranges: SavedMoment[]; index: number; mode: 'all' | 'single' } | null>(null);
  const [savedMomentPlayback, setSavedMomentPlayback] = useState<{ trackId: number; mode: 'all' | 'single' } | null>(null);
  /** 종료를 처리한 곡. 한 곡의 종료로 두 번 넘어가지 않게 한다. */
  const endedFor = useRef<number | null>(null);
  /** 마지막으로 알린 재생 오류. 같은 사정을 반복해서 띄우지 않는다. */
  const notifiedError = useRef<string | null>(null);
  // 셔플 한 바퀴에서 이미 들은 **자리**. 곡 번호로 세면 같은 곡을 두 번 담아도
  // 한 바퀴에 한 번만 울린다.
  const playedInCycle = useRef<Set<string>>(new Set());
  const requestId = useRef(0);
  const playbackSeek = useRef<Promise<void>>(Promise.resolve());
  const messageEndRef = useRef<HTMLDivElement>(null);
  /**
   * 대화 스크롤 영역(`.chat-messages`) 자체. 새 말이 오면 여기를 끝으로 내린다.
   *
   * `messageEndRef`(끝을 가리키는 sentinel)는 곡 카드까지 온전히 보이게 하지 못한다 —
   * 실측 546 / 564 (`S15P21A204-409`). 패널이 이 참조를 `ref` 로 직접 단다.
   */
  const chatBoxRef = useRef<HTMLDivElement>(null);
  const chatInputRef = useRef<HTMLInputElement>(null);
  const chatButtonRef = useRef<HTMLButtonElement>(null);
  /** 진행 중인 AI 요청. 화면을 떠나거나 다음 요청이 오면 끊는다. */
  const chatAbort = useRef<AbortController | null>(null);

  // 어디서 들어왔든 그 화면으로 돌아간다. 앱을 바로 열었으면 홈으로.
  const goBack = () => {
    // 열려 있는 오버레이가 있으면 **그것부터** 닫는다. 화면 헤더의 뒤로가기와
    // 안드로이드 소프트버튼이 같은 규칙을 따르게 한다 (S15P21A204-410).
    if (closeTopOverlay()) return;
    if (typeof window !== 'undefined' && window.history.length > 1) router.back();
    else router.push(pagePaths.home);
  };
  const go = (next: Page, preserveSearch = false) => {
    if (next === 'search' && !preserveSearch) setQuery('');
    // URL 직접 이동과 UI 이동 모두 같은 조건으로 보호한다. 실제 접근 권한 검증은 서버 책임이다.
    router.push(guardPath(pagePaths[next], !!sessionRef.current, onboardingRef.current));
  };
  const previousPlaybackPath = useRef(pathname);
  useEffect(() => {
    const previous = previousPlaybackPath.current;
    previousPlaybackPath.current = pathname;
    if (previous === pagePaths.moment && pathname !== pagePaths.moment) {
      // 편집 화면의 미리듣기 종료 조건이 일반 재생을 중간에 멈추지 않게 한다.
      previewEnd.current = null;
      momentSequence.current = null;
      setSavedMomentPlayback(null);
    }
  }, [pathname]);
  useEffect(() => {
    // 로그인 여부를 알기 전에는 판정하지 않는다. 알기도 전에 보내면 주소를 잃는다.
    if (!authChecked) return;
    // 예전 ?view= 링크는 호환하되 새로운 화면 이동은 경로 주소를 사용한다.
    const legacy = new URLSearchParams(window.location.search).get('view');
    const requested = legacy && Object.hasOwn(pagePaths, legacy)
      ? pagePaths[legacy as keyof typeof pagePaths] : pathname === '/mupid' ? '/home' : pathname;
    const target = guardPath(requested, !!demoSession, onboardingSubmitted);
    setPage(pageForPath(target));
    setLoginDialog(target === '/login' || target === '/signup');
    if (target !== pathname || legacy) router.replace(target);
  }, [authChecked, pathname, demoSession, onboardingSubmitted, router]);
  useEffect(() => {
    mountedRef.current = true;
    queueMicrotask(() => {
      if (!mountedRef.current) return;
      setReady(true);
    });
    return () => {
      mountedRef.current = false;
      chatAbort.current?.abort();
    };
  }, [initial.onboardingSubmitted]);
  useEffect(() => {
    if (!ready || !sessionRef.current) return;
    saveQueue.current = saveQueue.current.catch(() => {}).then(() =>
      mupidService.savePreferences({ onboardingSubmitted, nickname, moods, genres, liked, ignored, history, moments, playlists, lastPlayedId })
    ).catch(() => { if (mountedRef.current) setToast('변경 내용을 저장하지 못했어요. 다시 시도해 주세요.'); });
  }, [ready, onboardingSubmitted, nickname, moods, genres, liked, ignored, history, moments, playlists, lastPlayedId]);
  /**
   * 서버 추천을 한 번 받아 둔다.
   *
   * 실패해도 화면을 세우지 않는다 — 추천 서버가 아직 준비되지 않았거나(색인 미적재)
   * 배치가 한 번도 안 돌았을 수 있다. 그때는 카탈로그로 물러나고, 물러났다는 것은
   * `recommendationsReady` 가 말한다.
   */
  useEffect(() => {
    if (!ready || !sessionRef.current || localDemoEnabled()) return;
    const ac = new AbortController();
    void (async () => {
      const [today, next] = await Promise.all([
        fetchTodayMoments(ac.signal).catch(() => null),
        fetchNextTracks({ limit: 6 }, ac.signal).catch(() => null),
      ]);
      if (!mountedRef.current || ac.signal.aborted) return;
      if (today?.tracks.length) {
        setTodayMoments(today.tracks);
        setTodayMomentNote(today.note);
      }
      if (next?.length) setForYou(next);
      setRecommendationsReady(Boolean(today?.tracks.length || next?.length));
    })();
    return () => ac.abort();
  }, [ready]);

  /**
   * 고른 구간과 닮은 구간. 화면이 부를 때마다 받는다.
   *
   * 캐시하지 않는다 — 개인화가 없어 서버가 캐시할 수 있고, 여기서 또 들고 있으면
   * 구간을 옮겼을 때 옛 결과가 남는다.
   */
  // 화면이 `useEffect` 의존성에 넣는다. 렌더마다 새 함수면 구간을 옮기지 않아도 다시 묻는다.
  const loadSimilarSections = useCallback(
    async (musicId: number, sectionId: number, limit = 8) => {
      try {
        return await fetchSimilarSections(musicId, sectionId, limit);
      } catch {
        return [];
      }
    },
    [],
  );

  /**
   * 온보딩 결과를 저장한다. **계정당 한 번뿐이다.**
   *
   * 저장이 성공하기 전에는 홈으로 이동하거나 완료 표시를 남기지 않는다.
   * 서버 저장이 실패하면 던져서 화면이 알리게 한다 — 브라우저에만 남고 기기를
   * 바꾸면 사라지는 걸 조용히 넘기면 안 된다.
   *
   * 닉네임은 받지 않는다. 회원가입에서 이미 받았고, 고치는 길은 마이페이지의
   * `PATCH /api/v1/user` 하나다.
   */
  const completeOnboarding = async (selectedMoods: string[], selectedGenres: string[]) => {
    if (!sessionRef.current) throw new Error('로그인이 필요해요.');
    // 장르·분위기는 서버가 준 선택지에서 고른다. 데모 곡의 장르로 거르지 않는다.
    if (!selectedMoods.length || !selectedGenres.length)
      throw new Error('장르와 분위기를 선택해 주세요.');
    const value = { onboardingSubmitted: true, nickname, moods: selectedMoods, genres: selectedGenres, liked, ignored, history, moments, playlists, lastPlayedId };
    const save = saveQueue.current.catch(() => {}).then(() => mupidService.savePreferences(value));
    saveQueue.current = save;
    await save;
    if (demoSession?.kind === 'authenticated') {
      await saveOnboarding({ moods: selectedMoods, genres: selectedGenres });
    }
    setMoods(selectedMoods); setGenres(selectedGenres); setOnboardingSubmitted(true);
    onboardingRef.current = true;
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
  /**
   * 대화가 열리거나 새 말이 오가면 끝으로 내린다 (S15P21A204-409).
   *
   * **`smooth` 를 쓰지 않는다.** 이 환경에서 `scrollIntoView({behavior:'smooth'})` 는
   * 곧바로 아무것도 하지 않고 끝났다(3.5초를 기다려도 `scrollTop` 이 0 이었다).
   * 그래서 곡 카드가 화면 밖에 남았다 — 실측값은 `chat.tsx` 주석과 커밋 메시지에 있다.
   * 같은 일이 두 곳에서 벌어지면 원인을 찾느라 두 번 헤매게 되므로 여기도 함께 막는다.
   *
   * 패널의 스크롤 영역(`.chat-messages`)을 직접 내린다. 끝을 가리키는 sentinel 은
   * `block:'end'` 로도 끝까지 가지 못한다(546 / 564 — `chat.tsx` 참고).
   */
  useEffect(() => {
    if (!chatOpen) return;
    const box = chatBoxRef.current;
    if (!box) return;
    box.scrollTop = box.scrollHeight;
  }, [chatOpen, messages, chatLoading]);

  /**
   * 검색 확정.
   *
   * 엔터나 검색 버튼으로 **제출할 때만** 부른다. 글자마다 부르면 "ㅁ", "무",
   * "무피" 가 전부 검색어로 남아 최근 검색어가 앞자락으로 뒤덮인다.
   *
   * 이벤트를 큐에만 넣으면 5초 뒤에야 나가서, 방금 찾은 말이 최근 검색어에
   * 늦게 들어온다. 제출은 사용자가 끝냈다고 선언한 순간이므로 바로 흘려보낸다.
   */
  const submitSearch = (keyword: string) => {
    const value = keyword.trim();
    setQuery(value);
    setSearchRevision(revision => revision + 1);
    // 빈 검색은 검색어를 지우는 동작이다. 기록에 남길 것이 없다.
    if (!value || demoSession?.kind !== 'authenticated') return;
    analytics.search(value);
    void analytics.flush();
  };

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
   * 마이페이지에서 닉네임을 바꾼다.
   *
   * 계정에 먼저 저장하고, 성공한 뒤에야 화면을 바꾼다. 순서를 뒤집으면 서버가
   * 거절해도 바뀐 이름이 화면에 남아 저장된 것처럼 보인다. 실패는 던져서
   * 화면이 알리게 한다 — 브라우저 저장과 달리 조용히 넘어가면 안 된다.
   */
  const saveNickname = async (name: string) => {
    if (!sessionRef.current) throw new Error('로그인이 필요해요.');
    const saved = await updateNickname(name);
    if (!mountedRef.current) return saved;
    sessionRef.current = { ...sessionRef.current, nickname: saved };
    setNickname(saved);
    setDemoSession((current) => (current ? { ...current, nickname: saved } : current));
    return saved;
  };
  /*
   * 선호 분위기·장르를 나중에 고치는 길은 없앴다.
   *
   * 온보딩은 계정당 한 번이고 서버가 재제출을 409 로 막는다. 마이페이지의
   * 취향 칩이 누를 때마다 `saveOnboarding` 을 다시 불렀기 때문에 그대로 두면
   * 칩마다 실패 토스트가 떴다. 화면 쪽 칩도 함께 지웠다.
   *
   * 계정에 저장된 온보딩 **내용**을 되읽는 길도 없다. 진입 분기에 쓰는
   * "마쳤는가" 는 `GET /api/v1/user` 가 실어 보낸다.
   */
  /**
   * 계정에 저장된 좋아요·관심없음을 화면 상태로 가져온다.
   *
   * 브라우저에 있던 값은 덮는다. 계정 쪽이 기준이다 — 기기를 바꿔도 따라오는
   * 것이 이 API 의 목적이다. 지금 카탈로그에 없는 곡은 버려진다.
   */
  const adoptServerReactions = async (signal?: AbortSignal) => {
    if (localDemoEnabled()) return;
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
    if (localDemoEnabled()) return;
    const saved = await fetchHistory(tracks, signal);
    if (!saved?.length || signal?.aborted || !mountedRef.current) return;
    setHistory((current) =>
      [...saved, ...current.filter((id) => !saved.includes(id))].slice(0, HISTORY_LIMIT),
    );
  };
  /**
   * 계정에 저장된 플레이리스트를 가져온다. **서버가 유일한 출처다.**
   *
   * 계정이 비어 있으면 화면도 빈다. 예전에는 그때 이 기기에 남아 있던 목록을
   * 계정으로 올렸는데, 그 저장소가 계정별로 나뉘어 있지 않아 신규 가입자가
   * 앞사람의 목록(과 예전 빌드의 데모 목록)을 넘겨받았다. 그 길은 없앴다.
   */
  /*
   * 플레이리스트는 계정에 남는 기능이라 로컬 데모에 구현이 없다.
   *
   * 그 상태에서 "다시 시도해 주세요" 를 띄우면 몇 번이고 다시 누르게 된다.
   * 데모에서는 되지 않는 이유를 그대로 알린다.
   */
  const playlistFailure = (retryable: string) =>
    localDemoEnabled() ? '로컬 데모에서는 플레이리스트를 쓸 수 없어요. 서버 연결이 필요해요.' : retryable;
  const adoptServerPlaylists = async (signal?: AbortSignal) => {
    if (localDemoEnabled()) return;
    const saved = await playlistApi.fetchPlaylists(tracks, signal);
    if (!saved || signal?.aborted || !mountedRef.current) return;
    setPlaylists(saved);
  };
  /**
   * 계정에 저장된 재생 목록을 가져온다. **서버가 기준이다.**
   *
   * 로그인 전 화면이 들고 있던 큐(카탈로그 앞 100곡)는 사용자가 고른 것이 아니라
   * 그냥 채워 둔 기본값이라 덮어도 잃을 것이 없다. 다만 계정 큐가 **비어 있으면**
   * 덮지 않는다 — 처음 쓰는 사람에게 빈 목록을 안기는 대신 기본값을 그대로 둔다.
   *
   * 여기서 받은 판본이 들어와야 저장이 열린다.
   */
  const adoptServerQueue = async (signal?: AbortSignal) => {
    if (localDemoEnabled()) return;
    /*
     * **곡 목록이 없으면 아직 받지 않는다.** 서버 큐는 곡 번호라 카탈로그가
     * 있어야 화면 번호로 옮길 수 있는데, 로그인 직후에는 카탈로그를 아직
     * 받아오는 중이다. 그때 받아 오면 전부 걸러져 빈 큐로 읽히고, 그 빈 큐가
     * 곧바로 계정에 저장돼 **담아 둔 목록이 지워진다.**
     * 아래의 효과가 카탈로그가 도착한 뒤 다시 부른다.
     */
    if (!tracks.length) return;
    const saved = await queueApi.fetchQueue(signal);
    if (!saved || signal?.aborted || !mountedRef.current) return;
    // 계정 큐에는 카탈로그 밖의 곡(검색으로 찾아 담은 곡)이 섞여 있다. 먼저
    // 기억해 두지 않으면 화면이 그 줄을 그리지 못하고, 그렇게 줄어든 목록이
    // 곧바로 계정에 저장돼 담아 둔 곡이 지워진다.
    rememberTracks(saved.tracks);
    queueAdopted.current = true;
    queueRevision.current = saved.revision;
    /*
     * 계정 큐가 비어 있을 때 화면의 기본값(카탈로그 앞 100곡)을 그대로 둘지는
     * **판본으로 가른다.**
     *
     * 판본 0 은 한 번도 저장한 적이 없다는 뜻이다 — 처음 쓰는 사람에게 빈 목록을
     * 안기는 대신 기본값을 둔다. 판본이 1 이상이면 사용자가 만든 상태이므로 **빈
     * 것도 그대로 존중한다.** 예전에는 둘을 구분하지 않아, 목록을 통째로 비우고
     * 새로고침하면 담은 적 없는 곡이 우르르 되살아났다.
     */
    if (!saved.ids.length && saved.revision === 0) return;
    // 서버는 곡 번호만 들고 있다. 자리 번호는 이 기기에서 새로 붙인다.
    setQueue(toQueueEntries(saved.ids));
    // 방금 받아 온 것을 곧바로 되돌려 보내지 않는다. 재생 중인 곡은 화면 쪽이
    // 기준이라 비워 두고, 그 차이만 다음 저장에 실린다.
    savedQueue.current = { ids: saved.ids, currentId: null };
  };
  /**
   * 서버가 판정해서 돌려준 큐를 화면에 반영한다. 담기·빼기의 뒤처리다.
   *
   * 재생 중인 곡은 **마지막으로 올린 값을 그대로 둔다.** 여기서 지금 듣는 곡으로
   * 적어 버리면, 그 사이 곡이 넘어갔는데도 "이미 올린 것" 으로 보여 서버의
   * `current_music_id` 가 낡은 채로 남는다. 그러면 다음에 담는 곡의 자리가 틀어진다.
   */
  const adoptServerChange = (saved: queueApi.QueueSnapshot | null) => {
    if (!saved || !mountedRef.current) return;
    rememberTracks(saved.tracks);
    queueRevision.current = saved.revision;
    setQueue(toQueueEntries(saved.ids));
    savedQueue.current = { ids: saved.ids, currentId: savedQueue.current?.currentId ?? null };
  };
  const resetUnplayedAccount = () => {
    getPlayer()?.stop();
    activeRef.current = null;
    setActive(null);
    setLastPlayedId(null);
    setPlaying(false);
    setBuffering(false);
    setTime(0);
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
        const submitted = session.user.onboarding_submitted;
        if (!submitted) resetUnplayedAccount();
        const restored: AccountSession = { kind: 'authenticated', nickname: session.user.nickname, userId: session.user.user_id, onboardingSubmitted: submitted };
        // sessionRef 를 채워야 설정 저장과 온보딩 제출이 열린다.
        sessionRef.current = restored;
        setNickname(session.user.nickname);
        setDemoSession(restored);
        // 온보딩 여부는 계정 정보에 실려 온다. 따로 기다릴 요청이 없다.
        setOnboardingSubmitted(submitted);
        onboardingRef.current = submitted;
        void adoptServerReactions(abort.signal);
        void adoptServerHistory(abort.signal);
        void adoptServerPlaylists(abort.signal);
        void adoptServerQueue(abort.signal);
      })
      .catch(() => {})
      .finally(() => { if (!abort.signal.aborted) setAuthChecked(true); });
    return () => { abort.abort(); };
  }, []);
  const signInDemo = async (email: string, password: string) => {
    // 기존 호출부 호환을 위해 이름은 유지했지만 서버 인증을 그대로 거친다.
    const user = await login(email, password);
    // 온보딩 여부는 계정이 기준이다. 예전에는 서버가 주지 않아 이 기기에 남은
    // 값을 썼고, 그래서 다른 기기에서 마친 사람이 설문을 다시 봤다.
    const submitted = user.onboarding_submitted;
    if (!submitted) resetUnplayedAccount();
    const session: AccountSession = { kind: 'authenticated', nickname: user.nickname, userId: user.user_id, onboardingSubmitted: submitted };
    sessionRef.current = session;
    setOnboardingSubmitted(submitted);
    onboardingRef.current = submitted;
    setNickname(user.nickname);
    setDemoSession(session);
    setLoginDialog(false);
    setPage(submitted ? 'home' : 'welcome');
    router.replace(submitted ? '/home' : '/onboarding');
    setToast('뮤피드에 오신 걸 환영해요.');
    void adoptServerReactions();
    void adoptServerHistory();
    void adoptServerQueue();
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
  const finishSignOut = () => {
    setDemoSession(null);
    sessionRef.current = null;
    onboardingRef.current = false;
    setOnboardingSubmitted(false);
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
  };
  const signOutDemo = async () => {
    try { await logout(); } catch { setToast('로그아웃하지 못했어요. 다시 시도해 주세요.'); return; }
    finishSignOut();
    setToast('로그아웃했어요. 다시 로그인하면 이어서 들을 수 있어요.');
  };
  const withdrawMembership = async () => {
    await withdrawAccount();
    finishSignOut();
    setToast('회원탈퇴가 완료되었어요.');
  };

  const loadTrack = (track: Track) => {
    if (activeRef.current?.id === track.id) {
      // 같은 음원으로 다시 진입해도 모먼트에서 받은 추천 근거는 갱신한다.
      if (track.recommended || track.recommendationReason) {
        const updated = {
          ...activeRef.current,
          recommended: track.recommended ?? activeRef.current.recommended,
          recommendationReason: track.recommendationReason ?? activeRef.current.recommendationReason,
        };
        activeRef.current = updated;
        setActive(updated);
      }
      return;
    }
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
   * 로그인 뒤에 곡 목록이 들어오면 마지막으로 들었던 곡만 복원한다.
   *
   * 목록은 로그인한 사람만 받을 수 있어서, 비로그인으로 앱이 열릴 때는 비어
   * 있다가 로그인 뒤에 채워진다. `active` 는 처음 한 번만 정해지므로 여기서
   * 뒤늦게 들어온 목록을 받아 준다. 재생 이력이 없으면 빈 상태를 유지한다.
   * 복원하더라도 재생을 시작하지는 않는다.
   */
  useEffect(() => {
    if (active || !tracks.length) return;
    const pick = tracks.find((t) => t.id === lastPlayedId);
    if (!pick) return;
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
   * **부른 쪽이 출처를 알면 그 값이 이긴다.** 재생 목록(`QUEUE`)과 최근 들은
   * 음악(`RECENT`)은 어느 화면에서 눌러도 뜻이 같아 화면으로 갈음하면 안 된다 —
   * 이미 아는 곡을 스스로 다시 꺼낸 것을 추천 성과(`RECOMMEND`)로 세게 된다.
   *
   * 출처를 모르는 나머지만 지금 보고 있는 화면으로 갈음한다. 인플레이어 다음곡
   * 추천이 없어서 검색 결과를 이어 듣는 것도 `SEARCH` 다 (decision/0014).
   */
  const entryPathOf = (from?: EntryPath): EntryPath => {
    if (from) return from;
    if (page === 'search') return 'SEARCH';
    if (page === 'library') return 'PLAYLIST';
    return 'RECOMMEND';
  };
  /**
   * 지금 재생 위치(ms). 청취 이벤트가 싣는 `position_ms` 다.
   *
   * `time` 상태가 아니라 **재생기에서 실측한다.** 상태는 0.2초마다 따라오는 사본이라
   * 백그라운드 탭에서 갱신이 늘어지면 뒤처지고, 그 값으로 만든 구간 신호는 엉뚱한
   * 구간에 붙는다 (domain/listening-events.md "프론트가 지켜야 할 것").
   */
  const positionMs = () => Math.max(0, Math.round((getPlayer()?.store.get().positionSec ?? 0) * 1000));
  /** 화면이 쓰는 곡 번호(`id`)를 서버 음악 번호로 옮긴다. 이벤트는 서버 번호로 나간다. */
  const musicIdOf = (id: number) => tracks.find((t) => t.id === id)?.musicId;
  /**
   * 이벤트에 실을 플레이리스트 번호. 범위를 벗어나면 0("모름")을 보낸다.
   *
   * 이제 `Playlist.id` 는 서버의 `playlist_id` 라 이 검사는 늘 통과한다. 그래도
   * 남겨 둔 것은 여기서 걸러 내지 못한 값이 **그 이벤트가 실린 배치 전체를 400 으로
   * 떨어뜨리고**, 같이 실려 가던 재생 기록까지 날리기 때문이다. 화면 전용 번호를
   * 쓰던 시절(`Date.now()`, ≈1.7조)에 `listen_events.playlist_id`(UInt32)를 넘겨
   * 실제로 그렇게 됐다.
   *
   * 0 으로 보내도 손해가 적다. `PLAYLIST_ADD` 의 구간 신호(+0.8)는 `track_id` 와
   * `position_ms` 로 만들어지므로 그대로 남는다 — 어느 목록에 담았는지만 모른다.
   */
  const playlistIdOf = (id: number) =>
    Number.isInteger(id) && id > 0 && id <= 4294967295 ? id : 0;
  /**
   * 곡을 튼다. 재생 경로는 청크 스트리밍 하나뿐이다.
   *
   * 요청마다 번호를 매겨, 빠르게 곡을 바꿨을 때 **뒤늦게 끝난 이전 요청이
   * 지금 곡을 덮어쓰지 않게** 한다. 곡 변경이 간혹 먹지 않던 것이 이것 때문이다.
   */
  /**
   * 이번 재생이 재생 목록의 **어느 자리**인가.
   *
   * 자리를 아는 쪽(다음곡·재정렬된 목록에서 고른 곡)은 그 번호를 그대로 넘긴다.
   * 넘기지 않으면 여기서 고른다 — 듣던 곡을 그대로 다시 거는 경우(구간 미리듣기,
   * 한곡 반복, seek)에는 듣던 자리를 지키고, 그 밖에는 같은 곡이 담긴 첫 자리로
   * 본다. 목록에 없는 곡이면 자리가 없으므로 null 이다.
   */
  const resolveQueueUid = (track: Track, explicit?: string | null) => {
    if (explicit !== undefined) return explicit;
    const staying =
      activeRef.current?.id === track.id &&
      queue.some((entry) => entry.uid === currentUidRef.current && entry.trackId === track.id);
    if (staying) return currentUidRef.current;
    return queue.find((entry) => entry.trackId === track.id)?.uid ?? null;
  };
  const startPlayback = async (
    track: Track,
    start?: number,
    uid?: string | null,
    from?: EntryPath,
  ) => {
    if (typeof track.musicId !== 'number' && !track.localAudio) return;
    // 추천으로 시작한 재생이면 그 사실을 함께 남긴다. 이게 없으면 "이 추천으로 튼
    // 곡을 끝까지 들었는가" 를 물어볼 수 없어 추천 성과를 잴 방법이 없다.
    // 구간을 권한 추천이 먼저다. 구간 없이 곡만 권한 추천(오늘의 Moment)은 `recSource`
    // 에 온다 — 둘을 한 자리에 담으면 화면이 없는 구간을 그린다 (mupid-data.ts).
    const rec = track.recommended?.recommendationId
      ? {
          id: String(track.recommended.recommendationId),
          type: 'SECTION' as const,
          rank: track.recommended.rank,
        }
      : track.recSource
        ? {
            id: String(track.recSource.id),
            type: track.recSource.type,
            rank: track.recSource.rank,
          }
        : undefined;
    const slot = resolveQueueUid(track, uid);
    currentUidRef.current = slot;
    setCurrentUid(slot);
    if (typeof track.musicId === 'number') analytics.startPlay(
      { trackId: track.musicId, entryPath: entryPathOf(from), rec },
      start ?? 0,
    );
    const id = ++requestId.current;
    loadTrack(track);
    setLastPlayedId(track.id);
    setHistory((ids) =>
      [track.id, ...ids.filter((i) => i !== track.id)].slice(0, HISTORY_LIMIT),
    );
    const player = getPlayer();
    if (!player) return;
    try {
      if (track.localAudio) await player.loadLocal(track.id, track.localAudio, track.duration);
      else {
        // 구간 재생은 이전 위치에서 재생 상태를 내보내면 새 구간의 종료 조건에
        // 걸릴 수 있다. 먼저 멈춘 채 목적지로 옮긴 뒤 재생한다.
        if (start !== undefined) player.pause();
        await player.load(track.musicId!, { autoplay: start === undefined });
      }
      if (id !== requestId.current) return;
      // 같은 곡의 뒤쪽 순간에서 도입(0초)으로 돌아가는 경우에도 반드시 이동한다.
      // 0초를 생략하면 player.load가 같은 음원을 유지할 때 기존 위치에서 계속 재생된다.
      if (start !== undefined) {
        // 빠른 구간 전환은 이전 seek가 끝난 다음 처리한다. 엔진의 진행 중인
        // seek가 뒤늦게 paused를 내보내 새 재생을 멈추지 않도록 한다.
        const pending = playbackSeek.current.catch(() => {}).then(async () => {
          if (id !== requestId.current) return;
          await player.seek(start);
        });
        playbackSeek.current = pending;
        await pending;
        if (id !== requestId.current) return;
        await player.play();
      }
    } catch {
      if (id === requestId.current && mountedRef.current) {
        setToast('음원을 불러오지 못했어요. 다시 재생해 주세요.');
      }
    }
  };
  /**
   * 목록 밖에서 튼 곡을 큐에 놓는다. 이미 담겨 있으면 그 자리를 걷어 새 자리에
   * 다시 놓는다(중복 없음).
   *
   * **자리는 담기와 같은 설정(`queueInsertPosition`)이 정한다.** 예전에는 `'top'`
   * 이 박혀 있었다. 목록 밖에서 튼 곡이 큐에 들어오는 가장 흔한 길인데 그쪽이
   * 설정을 무시하니, 맨 아래를 골라 두어도 새 곡이 늘 맨 위에 나타났다 —
   * 게다가 방금 튼 곡이 맨 위에 서 있으므로 `afterCurrent` 로 담은 곡까지
   * 둘째 줄로 들어와, 세 갈래를 고른 표가 전부 "맨 위" 로 보였다
   * (S15P21A204-429, decision/0027).
   *
   * 자리가 어디든 **방금 튼 곡이 곧 재생 커서**다(`startPlayback` 이 이 자리를
   * 받는다). 그래서 맨 아래에 놓아도 그 곡이 바로 들리고, 이어지는 곡은 거기서
   * 부터 이어진다 — 0022 가 맨 위 삽입을 막은 이유(커서 뒤로 가서 영영 안 들림)
   * 는 이 길에 해당하지 않는다.
   *
   * 재생 버튼은 여기저기 흩어져 있다 — 곡 목록·검색 결과·오늘의 Moment·저장한
   * 순간. **어디서 눌렀든 들은 곡은 목록에 남아야 한다.** 한 곳에 모아 두지
   * 않으면 이번처럼 한 경로가 조용히 빠진다.
   */
  const placePlayedTrack = (track: Track) => {
    // 카탈로그 밖의 곡(검색 결과 등)일 수 있다. 자리만 만들고 곡을 기억하지
    // 않으면 그 자리는 곡으로 되돌려지지 못해 화면에서 빈다.
    rememberTracks([track]);
    const placed = placeInQueue(
      queue,
      track.id,
      queueInsertPosition,
      currentUidRef.current,
      QUEUE_LIMIT,
    );
    setQueue(placed.entries);
    return placed;
  };

  /**
   * 곡을 튼다.
   *
   * `queueUid` 는 **재생 목록 안에서** 누른 경우다. 그 자리를 그대로 두고 그
   * 자리를 튼다 — 목록에서 고른 줄이 누르자마자 자리를 옮기면 방금 어디를
   * 눌렀는지 놓친다. 목록 밖(검색·추천·상세)에서 튼 곡만 자리를 다시 잡고,
   * 그 자리는 담기와 같은 설정이 정한다(`placePlayedTrack`).
   */
  const play = (
    track: Track | null = activeRef.current,
    fromMoment = false,
    queueUid?: string,
    /*
     * 이 재생의 출처(`entry_path`). 누른 자리가 화면과 무관하게 뜻이 정해져 있을 때만
     * 넘긴다 — 재생 목록은 `QUEUE`, 최근 들은 음악은 `RECENT`. 빠지면 화면으로 갈음한다.
     */
    from?: EntryPath,
  ) => {
    // 곡이 하나도 없으면 누를 것도 없다.
    if (!track) return;
    // 목록 밖의 곡을 고르면 그 곡을 맨 앞에 두고 상한까지만 다시 채운다.
    // **담겨 있던 자리를 먼저 잇는다.** 예전에는 카탈로그 순서로 큐를 통째로
    // 새로 만들어서, 애써 끌어 바꿔 둔 재생 목록 순서가 목록 밖 곡을 한 번
    // 누르는 것만으로 사라졌다.
    /*
     * 지금 울리는 곡을 다시 누른 것인가. 그렇다면 아래에서 일시정지·재개로 갈린다.
     * **그 길에서는 큐를 건드리지 않는다** — 멈췄다 트는 것뿐인데 순서가 바뀌면
     * 듣던 자리를 잃는다.
     */
    const sameTrack = activeRef.current?.id === track.id;

    /*
     * 목록 밖에서 튼 곡은 **담겨 있든 아니든 고른 자리로** 놓는다. 담겨 있지
     * 않았으면 새로 놓고, 담겨 있었으면 그 자리를 걷어 옮긴다(중복 없음).
     *
     * 예전에는 담겨 있지 않은 곡을 틀면 카탈로그에서 상한까지 **나머지를 채웠다.**
     * 큐가 "다음에 들을 것" 을 담아 두는 임시 못이던 시절의 동작인데, 큐가 계정에
     * 남는 목록이 된 지금은 **재생 목록을 비우고 한 곡을 틀면 누르지도 않은 곡이
     * 우르르 담긴다.** 채우지 않는다.
     */
    const inQueue = queue.some((entry) => entry.trackId === track.id);

    /*
     * 아래에서 일시정지·재개로 갈릴 길인가. **같은 곡인지만으로는 가를 수 없다.**
     * 앱을 열면 마지막에 듣던 곡이 이미 `active` 인데 아직 울리지는 않는다. 그
     * 곡을 목록 밖에서 처음 누르면 "같은 곡" 이라는 이유로 큐를 건드리지 않아,
     * 자리를 잡지도 담기지도 않았다.
     *
     * 실제로 재생기가 그 곡을 들고 있을 때만 토글이다.
     *
     * **`fromMoment` 도 토글인지 아닌지를 가르지 않는다.** 예전에는 이 조건에
     * `!fromMoment` 가 붙어 있었다. 그런데 챗봇 추천 곡 줄은 구간 시작점에서
     * 틀려고 **늘** `fromMoment` 로 부른다. 그래서 그 줄의 재생 버튼은 토글
     * 경로에 영영 들어오지 못했고, 울리는 곡을 다시 눌러도 멈추는 대신 구간
     * 처음부터 다시 틀었다 — 버튼은 Pause 모양인데 동작은 재시작이었다
     * (S15P21A204-422).
     *
     * 가르는 것은 **재생기가 지금 이 곡을 들고 있는가** 하나다. 들고 있지 않으면
     * (앱을 막 열어 `active` 이기만 한 곡, 다른 곡) 아래에서 새로 튼다 — 추천
     * 으로 들어온 길이면 구간 시작점에서.
     */
    const status = getPlayer()?.store.get().status;
    const willToggle =
      sameTrack &&
      (status === 'playing' || status === 'buffering' || status === 'paused');

    // 하단 플레이어에서 일시정지·재개할 때는 저장 순간 시퀀스를 보존한다.
    // 새 재생을 시작할 때만 기존 전체/순간별 재생 모드를 끝낸다.
    //
    // **미리듣기 구간의 끝도 같이 보존한다.** 멈췄다 다시 트는 것뿐인데 끝을
    // 잃으면, 추천 구간을 듣다 한 번 멈춘 것만으로 곡이 끝까지 흘러간다.
    // 큐를 건드리지 않는 것과 같은 이유다 — 이 길은 아무것도 새로 정하지 않는다.
    if (!willToggle) {
      previewEnd.current = null;
      momentSequence.current = null;
      setSavedMomentPlayback(null);
    }

    let picked: string | undefined = queueUid;
    if (!queueUid && (!willToggle || !inQueue)) {
      // 큐 갱신은 다음 렌더에나 보이므로 이번 재생의 자리는 여기서 직접 넘긴다.
      const placed = placePlayedTrack(track);
      picked = placed.uid;
      /*
       * 듣던 곡을 그대로 이어 트는 길은 `startPlayback` 을 지나지 않는다.
       * 그 길에서도 방금 만든 자리가 지금 듣는 자리다.
       */
      if (willToggle) {
        currentUidRef.current = placed.uid;
        setCurrentUid(placed.uid);
      }
    }
    const player = getPlayer();
    // 지금 울리는 곡을 다시 누르면 멈춘다. 멈춘 곡을 누르면 **멈춘 자리에서**
    // 이어서 튼다 — 구간 처음으로 되감지 않는다.
    if (willToggle && player) {
      if (status === 'paused') void player.play();
      else player.pause();
      return;
    }
    // 듣던 곡을 두고 다른 곡을 고른 것은 그 곡을 넘긴 것이다. 다음곡 버튼으로 넘긴
    // 것과 구분해 `OTHER_TRACK` 으로 남긴다 — 분류는 ClickHouse 가 쿼리 시점에 한다.
    if (activeRef.current && activeRef.current.id !== track.id) {
      analytics.skip(positionMs(), 'OTHER_TRACK');
    }
    if (fromMoment && track.recommended) {
      // 추천은 시작 위치만 안내한다. 종료 제한을 두면 홈과 전체 플레이어에서도
      // 갑자기 일시정지된다. 구간 끝 정지는 명시적인 순간 미리듣기에만 둔다.
      void startPlayback(track, track.recommended.start, picked, from);
      return;
    }
    void startPlayback(track, fromMoment ? track.moment : undefined, picked, from);
  };
  const next = (direction = 1) => {
    previewEnd.current = null;
    momentSequence.current = null;
    setSavedMomentPlayback(null);
    // 화면에 보이는 목록과 같은 순서로 넘긴다. 끌어 바꾼 순서가 그대로 다음 곡이다.
    const pool = queueTracks;
    if (!pool.length) return;
    const candidates = pool.filter((t) => t.id !== activeRef.current?.id);
    const target =
      shuffle && candidates.length
        ? candidates[Math.floor(Math.random() * candidates.length)]
        : stepQueue(pool, currentUidRef.current, direction);
    if (!target) return;
    // 넘길 곡이 실제로 정해진 뒤에 남긴다. 이전·다음 어느 쪽이든 지금 곡을 끝까지
    // 듣지 않고 떠난 것이므로 스킵이다.
    analytics.skip(positionMs(), 'NEXT_BUTTON');
    // 다음 곡은 재생 목록이 고른다. 어느 화면에서 눌렀든 출처는 목록이다 — 이걸
    // 화면으로 갈음하면 같은 목록을 쭉 듣는 동안 곡마다 경로가 달라진다.
    void startPlayback(target, 0, target.uid, 'QUEUE');
  };
  /**
   * 화면에 보이는 재생 목록. 큐의 자리 순서 그대로이고, 건너뛴 곡과 카탈로그에서
   * 사라진 곡만 빠진다. 자리 번호(`uid`)를 곡에 붙여 내보내므로 같은 곡이 여러
   * 번 담겨 있어도 화면이 줄마다 다른 것으로 다룰 수 있다.
   */
  const queueTracks: QueueTrack[] = queue
    .map(({ uid, trackId }) => {
      const track = tracks.find((t) => t.id === trackId);
      return track ? { ...track, uid } : null;
    })
    .filter((t): t is QueueTrack => !!t && !ignored.includes(t.id))
    .slice(0, QUEUE_LIMIT);
  /**
   * 사용자가 재생 위치를 옮긴다. 화면의 막대와 잠금화면의 막대가 같은 길이다.
   *
   * **이미 들고 있는 곡이면 그 자리만 옮긴다.** 예전에는 `startPlayback` 을
   * 다시 불러 곡을 통째로 다시 걸었다. 막대를 끄는 동안 `change` 가 계속
   * 들어오는데 그때마다 청취 이벤트가 나가고 최근 감상에 쌓였고, 다시 건
   * 재생이 옛 자리에서 시작했다가 뒤늦게 목적지로 튀어 막대가 요동쳤다.
   */
  const seekByUser = (value: number) => {
    if (!Number.isFinite(value)) return;
    previewEnd.current = null;
    momentSequence.current = null;
    setSavedMomentPlayback(null);
    const track = activeRef.current;
    if (!track) return;
    const target = Math.max(0, Math.min(value, track.duration));
    const player = getPlayer();
    // 아직 이 곡을 들고 있지 않으면 그 자리에서 새로 튼다.
    if (!player || player.store.get().musicId !== track.musicId) {
      void startPlayback(track, target);
      return;
    }
    // 사용자가 직접 옮긴 seek 만 이벤트로 남는다. `startPlayback` 이 거는 seek 이나
    // 구간 미리듣기처럼 앱이 거는 이동은 여기를 지나지 않는다 — 되감아 다시 듣는
    // 행동(+0.6)을 앱의 동작과 섞으면 구간 선호 신호가 망가진다.
    analytics.seek(positionMs(), Math.round(target * 1000));
    setTime(target);
    void player.seek(target);
  };

  /**
   * 상단 알림과 잠금화면의 플레이어.
   *
   * 앱에서만 뜬다(웹에서는 `lib/platform/media-session.ts` 가 조용히 지나간다).
   * 버튼은 화면의 같은 자리를 그대로 부른다 — 알림에서 누른 것과 화면에서
   * 누른 것이 청취 이벤트에서도 구분되지 않아야 하기 때문이다.
   */
  useMediaSession({
    track: active,
    canPrevious: queueTracks.length > 1,
    canNext: queueTracks.length > 1,
    onPlay: () => void getPlayer()?.play(),
    onPause: () => getPlayer()?.pause(),
    onNext: () => next(1),
    onPrevious: () => next(-1),
    onSeek: seekByUser,
    onStop: () => {
      getPlayer()?.stop();
      setPlaying(false);
    },
  });

  /**
   * 청취 이벤트 자동 수집을 재생기에 붙인다.
   *
   * **아래 화면 구독보다 먼저 등록한다.** 스토어는 등록 순서대로 구독자를 부르는데,
   * 화면 구독이 먼저 돌면 곡이 끝나자마자 다음 곡을 걸어버려 `PLAY_END` 자리에
   * `PLAYBACK_ABANDON` 이 남는다. 끝까지 들은 것과 끊긴 것은 정반대의 신호다.
   */
  useEffect(() => {
    const player = getPlayer();
    if (!player) return;
    return bindPlayer(player, analytics);
  }, []);

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
      // 상태가 'error' 일 때만 알리면 안 된다. 회선이 끊겨 계속 재시도하는
      // 동안에는 status 가 'buffering' 으로 남아 있어(그래야 돌아왔을 때
      // 스스로 이어진다) 스피너만 돌고 이유가 보이지 않았다.
      //
      // 구독은 위치가 바뀔 때마다(200ms) 불리므로 **사정이 바뀐 순간에만**
      // 띄운다. 매번 부르면 토스트가 3.2초마다 깜빡인다.
      if (state.error && state.error !== notifiedError.current) {
        setToast(state.error);
      }
      notifiedError.current = state.error;

      // 구간 미리듣기는 끝 지점에서 멈춘다. 곡이 끝난 것이 아니므로
      // 다음 곡으로 넘어가지 않는다.
      const sequence = momentSequence.current;
      if (sequence && activeRef.current?.id === sequence.trackId && state.status === 'ended') {
        const finalRange = sequence.ranges.at(-1);
        momentSequence.current = null;
        setSavedMomentPlayback(null);
        setTime(finalRange?.end ?? state.positionSec);
        return;
      }
      if (
        sequence &&
        activeRef.current?.id === sequence.trackId &&
        state.status === 'playing'
      ) {
        const currentRange = sequence.ranges[sequence.index];
        if (currentRange && state.positionSec >= currentRange.end) {
          const nextRange = sequence.ranges[sequence.index + 1];
          if (!nextRange) {
            momentSequence.current = null;
            setSavedMomentPlayback(null);
            player.pause();
            setTime(currentRange.end);
            return;
          }
          sequence.index += 1;
          // 맞닿은 구간은 엔진을 그대로 흘려 보내고, 사이가 빈 구간만 건너뛴다.
          if (nextRange.start > currentRange.end) {
            setTime(nextRange.start);
            void player.seek(nextRange.start);
          }
          return;
        }
      }
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
   * 큐 변경을 모아서 계정에 올린다.
   *
   * 조작마다 보내지 않는다 — 끌어서 순서를 다섯 번 바꿔도 요청은 한 번이다.
   * 재생 중인 곡이 바뀔 때도 올린다. 그 값이 있어야 서버가 "담은 곡을 재생 중인
   * 곡 바로 아래에 넣는다" 를 판정할 수 있다.
   *
   * **판본을 모르는 동안에는 올리지 않는다.** 계정 큐를 아직 읽지 못한 채로
   * 올리면 저장돼 있던 큐를 화면의 초기값으로 지운다.
   */
  useEffect(() => {
    if (demoSession?.kind !== 'authenticated') return;
    const revision = queueRevision.current;
    if (revision === null) return;

    const currentId = active?.id ?? null;
    // 서버에 올리는 것은 곡 번호 순서뿐이다. 자리 번호는 이 기기에서만 쓴다.
    const ids = queue.map((entry) => entry.trackId);
    const last = savedQueue.current;
    const unchanged =
      !!last &&
      last.currentId === currentId &&
      last.ids.length === ids.length &&
      last.ids.every((id, index) => id === ids[index]);
    if (unchanged) return;
    // 타이머가 돌기 전에 화면을 떠날 수 있다. 떠날 때 밀어낼 수 있도록 적어 둔다.
    pendingQueueSave.current = { ids, currentId, revision };

    const timer = setTimeout(() => {
      /*
       * **보내기 시작할 때 지우지 않는다.** 요청이 도는 중에 새로고침하면 그
       * 요청은 문서와 함께 취소되는데, 그때 이미 지워 두었으면 떠날 때 밀어낼
       * 것이 없다고 판단해 변경이 사라진다. 서버가 받았다고 확인해 준 뒤에 지운다.
       *
       * 그래서 떠날 때 같은 내용을 한 번 더 보낼 수 있다. 판본이 함께 가므로
       * 앞의 것이 이미 성공했다면 뒤의 것은 409 로 거절되고 아무것도 바뀌지 않는다.
       */
      void queueApi.saveQueue(ids, currentId, revision, tracks).then((saved) => {
        if (!saved || !mountedRef.current) return;
        // 판본이 어긋나 다른 기기의 큐를 받아 왔을 수 있다. 그 목록에도 카탈로그
        // 밖의 곡이 있다.
        rememberTracks(saved.tracks);
        pendingQueueSave.current = null;
        queueRevision.current = saved.revision;
        savedQueue.current = { ids: saved.ids, currentId };
        /*
         * 서버가 다른 목록을 돌려주는 경우가 둘 있다. 판본이 어긋나 다른 기기의
         * 큐를 받아 왔거나, 지워진 곡이 걸러졌거나. 어느 쪽이든 서버가 기준이다.
         * 여기서 덮어도 `savedQueue` 가 같은 값이라 저장이 다시 돌지는 않는다.
         */
        const changed =
          saved.ids.length !== ids.length || saved.ids.some((id, index) => id !== ids[index]);
        if (changed) setQueue(toQueueEntries(saved.ids));
      });
    }, QUEUE_SAVE_DELAY);

    return () => clearTimeout(timer);
  }, [queue, active?.id, demoSession, tracks, rememberTracks]);

  /**
   * 카탈로그가 도착한 뒤에 계정 큐를 받아 온다.
   *
   * 로그인과 곡 목록은 따로 도착한다. 로그인 직후의 한 번은 카탈로그가 없어
   * 건너뛰므로(위 `adoptServerQueue`), 목록이 채워지면 여기서 이어받는다.
   * 한 번 받아 오면 다시 받지 않는다 — 다시 받으면 그 사이의 조작이 덮인다.
   */
  useEffect(() => {
    if (demoSession?.kind !== 'authenticated') return;
    if (queueAdopted.current || !tracks.length) return;
    void adoptServerQueue();
    // `adoptServerQueue` 는 매 렌더 새로 만들어져 의존성에 넣을 수 없다.
    // 이 효과가 보는 것은 "로그인했고 곡 목록이 왔는가" 두 가지뿐이다.
  }, [demoSession, tracks]); // eslint-disable-line react-hooks/exhaustive-deps

  /**
   * 화면을 떠나기 직전에 남은 저장을 밀어낸다.
   *
   * 없으면 **모으는 창(1.2초) 안에 일어난 마지막 변경이 통째로 사라진다.**
   * 순서를 바꾸자마자 새로고침하면 옛 순서로 돌아가던 것이 이것이었다.
   *
   * `beforeunload` 가 아니라 `visibilitychange`·`pagehide` 를 본다. 모바일
   * 브라우저는 탭을 버릴 때 `beforeunload` 를 부르지 않는 경우가 많고, 이 둘은
   * 탭 전환·앱 전환에서도 불려 더 일찍 안전해진다.
   *
   * 저장 요청 자체가 `keepalive` 라(`endpoints.ts`) 여기서 따로 지정하지 않는다.
   */
  useEffect(() => {
    const flush = () => {
      const pending = pendingQueueSave.current;
      if (!pending) return;
      // 한 번만 보낸다. 숨김 → 복귀 → 다시 숨김에서 같은 판본으로 두 번 보내면
      // 두 번째는 판본이 어긋나 거절된다.
      pendingQueueSave.current = null;
      void queueApi.saveQueue(pending.ids, pending.currentId, pending.revision, tracks);
    };
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') flush();
    };
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('pagehide', flush);
    return () => {
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('pagehide', flush);
    };
  }, [tracks]);

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
    // **듣고 있는 자리 다음부터** 센다. 예전에는 큐 맨 앞에서 세 곡을 집어서
    // 목록 뒤쪽을 듣는 동안에는 정작 다음에 울릴 곡이 준비되지 않았고, 순서를
    // 바꿔도 미리 받아 두는 곡이 그대로였다.
    const upcoming = upcomingQueue(queueTracks, currentUid)
      .filter((track) => isStreamed(track) && track.id !== active?.id)
      .map((track) => track.musicId!)
      .slice(0, 3);
    getPlayer()?.setQueue(upcoming);
  }, [queue, currentUid, active?.id, ignored, demoSession]);
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
    // 플레이리스트에 같은 곡이 두 번 들어 있어도 자리는 둘이다.
    const entries = toQueueEntries(playable.slice(0, QUEUE_LIMIT).map((t) => t.id));
    const firstIndex = playable.findIndex((t) => t === first);
    const firstUid = entries[firstIndex]?.uid ?? null;
    playedInCycle.current = new Set(firstUid ? [firstUid] : []);
    setQueue(entries);
    previewEnd.current = null;
    momentSequence.current = null;
    setSavedMomentPlayback(null);
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
    // 자동으로 이어지는 재생은 전부 재생 목록이 고른 것이다(한곡 반복 포함).
    if (repeatMode === 'one') { void startPlayback(current, 0, undefined, 'QUEUE'); return; }
    const uid = currentUidRef.current;
    if (uid) playedInCycle.current.add(uid);
    const pool = queueTracks;
    // **곡이 아니라 자리를 센다.** 곡 번호로 세던 때는 지금 자리를 맨 앞 중복으로
    // 착각해 목록 뒤쪽을 듣다가도 재생이 앞으로 되감겼고, 같은 곡을 두 번 담아도
    // 셔플 한 바퀴에 한 번만 울렸다.
    const index = queueIndexOf(pool, uid);
    let remaining = pool.filter(t => !playedInCycle.current.has(t.uid));
    const finished = shuffle ? remaining.length === 0 : index >= pool.length - 1;
    // 목록 끝에 닿았을 때, 전체 반복이면 한 바퀴를 다시 돌고 아니면 멈춘다.
    if (!pool.length || (finished && repeatMode === 'off')) {
      getPlayer()?.pause(); setPlaying(false); return;
    }
    if (finished) { playedInCycle.current.clear(); remaining = pool; }
    const target = shuffle ? remaining[Math.floor(Math.random() * remaining.length)] : stepQueue(pool, uid, 1);
    if (!target) { getPlayer()?.pause(); setPlaying(false); return; }
    playedInCycle.current.add(target.uid);
    void startPlayback(target, 0, target.uid, 'QUEUE');
  };
  // 구독자는 한 번만 등록되므로 최신 규칙을 참조로 넘긴다.
  onEndedRef.current = onEnded;
  const openTrack = (track: Track) => {
    previewEnd.current = null;
    momentSequence.current = null;
    setSavedMomentPlayback(null);
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
    const state = getPlayer()?.store.get();
    const position = activeRef.current?.id === track.id
      ? (state?.musicId === track.musicId ? state?.positionSec ?? time : time)
      : track.moment;
    const anchor = Math.max(0, Math.min(position, track.duration));
    setMomentAnchorTime(anchor);
    setMomentOpenRevision(value => value + 1);
    previewEnd.current = null;
    momentSequence.current = null;
    setSavedMomentPlayback(null);
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
      anchor,
    );
    setMomentRange([part.start, part.end]);
    setMomentSection(part.label);
    go('moment');
  };
  const previewMoment = () => {
    if (!activeRef.current) return;
    momentSequence.current = null;
    setSavedMomentPlayback(null);
    previewEnd.current = momentRange[1];
    void startPlayback(activeRef.current, momentRange[0]);
  };
  const playSavedMoment = (moment: SavedMoment) => {
    playSavedMoments([moment], moment, false);
  };
  const playSavedMoments = (momentsToPlay: SavedMoment[], startAt?: SavedMoment, toggle = true) => {
    if (!momentsToPlay.length) return;
    const mode = startAt ? 'single' : 'all';
    const ranges = momentPlaybackRanges(momentsToPlay, startAt);
    const first = ranges[0];
    const player = getPlayer();
    // 같은 곡의 같은 구간 묶음을 다시 누르면 멈춘다.
    if (
      toggle &&
      activeRef.current?.id === first.track.id &&
      momentSequence.current?.trackId === first.track.id &&
      momentSequence.current.mode === mode &&
      momentSequence.current.ranges[momentSequence.current.index]?.start === first.start &&
      player?.store.get().status === 'playing'
    ) {
      momentSequence.current = null;
      setSavedMomentPlayback(null);
      player.pause();
      return;
    }
    previewEnd.current = null;
    momentSequence.current = { trackId: first.track.id, ranges, index: 0, mode };
    setSavedMomentPlayback({ trackId: first.track.id, mode });
    // 같은 곡의 다른 순간을 누른 경우 기존 구간의 소리를 즉시 끊는다. 이후
    // startPlayback이 클릭한 시작점(0초 포함)으로 옮기고 다시 재생한다.
    if (activeRef.current?.id === first.track.id && player) player.pause();
    /*
     * 순간 재생도 **곡을 트는 것**이다. 오늘의 Moment·저장한 순간에서 누른 곡이
     * 재생 목록에 남지 않아 방금 들은 곡을 목록에서 찾을 수 없었다. 목록 밖에서
     * 튼 것과 같은 자리에 놓는다 — 담기와 같은 설정을 따른다.
     */
    void startPlayback(first.track, first.start, placePlayedTrack(first.track).uid);
  };
  const stopSavedMomentPlayback = (trackId: number, mode?: 'all' | 'single') => {
    const sequence = momentSequence.current;
    if (!sequence || sequence.trackId !== trackId || (mode && sequence.mode !== mode)) return;
    momentSequence.current = null;
    setSavedMomentPlayback(null);
    getPlayer()?.pause();
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
    markMoments([saved]);
    // 구간을 저장하면 그 곡을 좋아한 것으로 본다. 계정에도 같이 남겨야 한다.
    if (!liked.includes(saved.track.id)) toggleLike(saved.track.id);
    setToast('저장한 순간을 저장했어요. 내 플레이리스트에서 다시 만나요.');
  };

  /**
   * 저장한 순간을 `SECTION_MARK` 로 남긴다. 구간을 직접 찍는 행동이라 신호 중
   * 가장 강한 긍정(+1.5)이다.
   *
   * 순간은 초 단위이고 이벤트는 밀리초다. 범위와 함께 시작점을 `position_ms` 로
   * 싣는 것은 백엔드 구간 매핑이 그 한 점으로 `dictGet` 하기 때문이다 — SDK 가
   * 알아서 넣지만, 곡 번호는 여기서만 알 수 있어 직접 넘긴다.
   */
  const markMoments = (entries: SavedMoment[]) => {
    for (const entry of entries) {
      if (typeof entry.track.musicId !== 'number') continue;
      analytics.markSection(entry.start * 1000, entry.end * 1000, entry.track.musicId);
    }
  };
  const unmarkMoments = (entries: SavedMoment[]) => {
    for (const entry of entries) {
      if (typeof entry.track.musicId !== 'number') continue;
      analytics.unmarkSection(entry.start * 1000, entry.end * 1000, entry.track.musicId);
    }
  };

  /**
   * 저장한 순간을 더한다. 이미 같은 범위가 있으면 더하지도, 이벤트를 남기지도 않는다.
   *
   * 화면마다 `setMoments` 를 직접 부르던 것을 여기로 모았다. 흩어져 있으면 새 화면이
   * 하나 늘 때마다 구간 신호가 조용히 빠진다.
   */
  const addMoments = (entries: SavedMoment[]) => {
    const fresh = entries.filter(
      (entry) =>
        !moments.some(
          (v) => v.track.id === entry.track.id && v.start === entry.start && v.end === entry.end,
        ),
    );
    if (!fresh.length) return;
    markMoments(fresh);
    setMoments((list) => [...fresh, ...list]);
  };

  /** 저장한 순간을 뺀다. 지정 해제는 그만큼 강한 부정 신호(−1.5)라 반드시 남긴다. */
  const removeMoment = (id: number) => {
    const gone = moments.find((v) => v.id === id);
    if (!gone) return;
    unmarkMoments([gone]);
    setMoments((list) => list.filter((v) => v.id !== id));
  };

  /** 화면에서는 곡 단위로 지우되 저장과 해제 이벤트는 각 순간별로 처리한다. */
  const removeMoments = (ids: number[]) => {
    const gone = moments.filter((moment) => ids.includes(moment.id));
    if (!gone.length) return;
    unmarkMoments(gone);
    setMoments((list) => list.filter((moment) => !ids.includes(moment.id)));
  };

  /** 저장한 순간의 범위를 바꾼다. 옛 구간을 해제하고 새 구간을 찍는 것과 같다. */
  const replaceMoment = (id: number, part: { start: number; end: number; section: string }) => {
    const previous = moments.find((v) => v.id === id);
    if (!previous) return;
    unmarkMoments([previous]);
    markMoments([{ ...previous, ...part }]);
    setMoments((list) => list.map((v) => (v.id === id ? { ...v, ...part } : v)));
  };

  /** 곡의 순간 묶음을 고친 뒤에도 저장·이벤트는 구간별 행으로 유지한다. */
  const replaceMoments = (ids: number[], entries: SavedMoment[]) => {
    const previous = moments.filter((moment) => ids.includes(moment.id));
    if (!previous.length) return;
    unmarkMoments(previous);
    markMoments(entries);
    setMoments((list) => [...entries, ...list.filter((moment) => !ids.includes(moment.id))]);
  };

  /**
   * 플레이리스트에 곡을 담는다. 이미 있으면 아무 일도 하지 않는다.
   *
   * `PLAYLIST_ADD` 는 구간 신호(+0.8)로 이어지므로 재생 중이면 그 위치를 싣는다.
   */
  const addToPlaylist = async (playlistId: number, track: Track) => {
    const target = playlists.find((p) => p.id === playlistId);
    // 방금 만든 플레이리스트는 아직 상태에 없다. "찾지 못함" 과 "이미 담겨 있음" 은 다르다.
    if (target?.tracks.some((t) => t.id === track.id)) return;
    // 재생 위치는 지금 읽어 둔다. 서버 왕복을 기다린 뒤에 읽으면 그 사이 곡이 흐른다.
    const at = activeRef.current?.id === track.id ? positionMs() : 0;
    // 화면을 먼저 바꾼다. 담기는 누르자마자 반응해야 한다.
    setPlaylists((list) =>
      list.map((p) =>
        p.id === playlistId && !p.tracks.some((t) => t.id === track.id)
          ? { ...p, tracks: [...p.tracks, track] }
          : p,
      ),
    );
    try {
      // 서버에 담긴 뒤에 이벤트를 남긴다. 저장에 실패한 것을 신호(+0.8)로 세면 안 된다.
      if (await playlistApi.addTrack(playlistId, track)) {
        analytics.addToPlaylist(playlistIdOf(playlistId), at, track.musicId!);
      }
    } catch {
      if (!mountedRef.current) return;
      setPlaylists((list) =>
        list.map((p) =>
          p.id === playlistId ? { ...p, tracks: p.tracks.filter((t) => t.id !== track.id) } : p,
        ),
      );
      setToast(playlistFailure('플레이리스트에 담지 못했어요. 다시 시도해 주세요.'));
    }
  };

  /** 플레이리스트에서 곡을 뺀다. 화면에서 고른 id 목록을 그대로 받는다. */
  const removeFromPlaylist = async (playlistId: number, trackIds: number[]) => {
    const target = playlists.find((p) => p.id === playlistId);
    if (!target || !trackIds.length) return;
    const removed = target.tracks.filter((t) => trackIds.includes(t.id));
    if (!removed.length) return;
    const before = target.tracks;
    setPlaylists((list) =>
      list.map((p) =>
        p.id === playlistId ? { ...p, tracks: p.tracks.filter((t) => !trackIds.includes(t.id)) } : p,
      ),
    );
    try {
      await playlistApi.removeTracks(playlistId, removed);
      for (const track of removed) {
        if (typeof track.musicId === 'number') {
          analytics.removeFromPlaylist(playlistIdOf(playlistId), track.musicId);
        }
      }
    } catch {
      if (!mountedRef.current) return;
      // 순서까지 원래대로 되돌린다. 남은 것만 다시 넣으면 뺀 곡이 뒤로 밀린다.
      setPlaylists((list) => list.map((p) => (p.id === playlistId ? { ...p, tracks: before } : p)));
      setToast(playlistFailure('플레이리스트에서 빼지 못했어요. 다시 시도해 주세요.'));
    }
  };

  /** 플레이리스트 제목을 바꾼다. 화면을 먼저 바꾸고 실패하면 되돌린다. */
  const applyPlaylistRename = async (playlistId: number, name: string) => {
    const title = name.trim().slice(0, 40);
    const before = playlists.find((p) => p.id === playlistId);
    if (!title || !before || before.name === title) return;
    setPlaylists((list) => list.map((p) => (p.id === playlistId ? { ...p, name: title } : p)));
    try {
      await playlistApi.renamePlaylist(playlistId, title);
      setToast('플레이리스트 이름을 바꿨어요.');
    } catch {
      if (!mountedRef.current) return;
      setPlaylists((list) =>
        list.map((p) => (p.id === playlistId ? { ...p, name: before.name } : p)),
      );
      setToast(playlistFailure('이름을 바꾸지 못했어요. 다시 시도해 주세요.'));
    }
  };

  /**
   * 플레이리스트를 지운다. 서버는 소프트 삭제라 되살릴 수 있다.
   *
   * 수록곡마다 `PLAYLIST_REMOVE` 를 남기지는 않는다. 곡 하나를 빼는 것은 "이 곡은
   * 여기 어울리지 않는다" 는 곡에 대한 신호지만, 목록을 통째로 지우는 것은 "이 묶음이
   * 더는 필요 없다" 는 컬렉션에 대한 행동이다. 곡마다 부정 신호로 흩뿌리면 목록을
   * 자주 정리하는 사용자의 취향이 왜곡된다.
   */
  const removePlaylist = async (playlistId: number) => {
    const before = playlists;
    setPlaylists((list) => list.filter((p) => p.id !== playlistId));
    setSelectedPlaylist(null);
    try {
      await playlistApi.removePlaylist(playlistId);
      setToast('플레이리스트를 삭제했어요.');
    } catch {
      if (!mountedRef.current) return;
      setPlaylists(before);
      setToast(playlistFailure('플레이리스트를 삭제하지 못했어요. 다시 시도해 주세요.'));
    }
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
    // 듣고 있는 곡이면 지금 위치를, 목록에서 누른 것이면 0 을 싣는다. 0 이면 이벤트는
    // 남되 구간 신호가 만들어지지 않는다 — 곡 단위 신호로만 쓰인다.
    const at = activeRef.current?.id === id ? positionMs() : 0;
    const musicId = musicIdOf(id);
    if (musicId !== undefined) {
      if (wasLiked) analytics.unlike(at, 'TRACK', musicId);
      else analytics.like(at, 'TRACK', musicId);
      // 좋아요가 켜지면서 "관심 없음" 이 풀렸다면 그 해제도 남긴다.
      if (!wasLiked && wasIgnored) analytics.undislike('TRACK', musicId);
    }
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
    const at = activeRef.current?.id === id ? positionMs() : 0;
    const musicId = musicIdOf(id);
    if (musicId !== undefined) {
      if (wasLiked) analytics.unlike(at, 'TRACK', musicId);
      analytics.dislike(at, 'TRACK', musicId);
    }
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
    for (const id of before) {
      const musicId = musicIdOf(id);
      if (musicId !== undefined) analytics.undislike('TRACK', musicId);
    }
    setIgnored([]);
    if (demoSession?.kind !== 'authenticated' || !before.length) return;
    Promise.all(before.map((id) => pushReaction(tracks, id, null))).catch(() => {
      if (!mountedRef.current) return;
      setIgnored(before);
      setToast('숨긴 곡을 되돌리지 못했어요. 다시 시도해 주세요.');
    });
  };

  /** 숨긴 곡 하나를 되돌린다. */
  const restoreIgnored = (id: number) => {
    if (!ignored.includes(id)) return;
    const musicId = musicIdOf(id);
    if (musicId !== undefined) analytics.undislike('TRACK', musicId);
    setIgnored((ids) => ids.filter((i) => i !== id));
    if (demoSession?.kind !== 'authenticated') return;
    pushReaction(tracks, id, null).catch(() => {
      if (!mountedRef.current) return;
      setIgnored((ids) => (ids.includes(id) ? ids : [...ids, id]));
      setToast('숨긴 곡을 되돌리지 못했어요. 다시 시도해 주세요.');
    });
  };
  const openChat = () => setChatOpen(true);
  const closeChat = () => {
    setChatOpen(false);
    requestAnimationFrame(() => chatButtonRef.current?.focus());
  };

  /**
   * 대화를 지우고 새로 시작한다.
   *
   * 세션 번호를 비우는 것이 핵심이다 — 남겨 두면 다음 턴이 지운 대화에 이어 붙는다.
   * 번호를 지우면 서버가 다음 턴에 새 세션을 연다. 서버의 `history` 기반 맥락도 같이
   * 끊기도록 대화 목록을 인사 한 마디로 되돌린다.
   *
   * 진행 중인 요청은 끊는다. 안 끊으면 지운 뒤 도착한 답이 새 대화에 섞인다.
   */
  const startNewChat = () => {
    chatAbort.current?.abort();
    chatAbort.current = null;
    chatSessionRef.current = null;
    setMessages([{ id: Date.now(), role: 'assistant', text: CHAT_GREETING }]);
    setChatInput('');
    setChatLoading(false);
  };

  /**
   * AI 에게 한 마디 보낸다.
   *
   * 답은 서버가 만든다 (`POST /api/v1/ai/chat`). 예전의 인위적인 지연(650ms)은
   * 없앴다 — 실제로 수 초에서 수십 초가 걸리고, 그동안 패널이 "찾고 있어요" 를
   * 그린다. 화면을 떠나면 요청을 끊는다.
   *
   * **세션을 이어서 보낸다.** `chatSessionRef` 에 들고 있는 번호를 그대로 실으면 한
   * 대화가 `ai_chat_sessions` 한 행으로 남는다. 응답이 준 번호로 다시 채워 다음 턴에
   * 쓴다 (S15P21A204-375).
   */
  const sendMessage = (prompt = chatInput) => {
    if (!prompt.trim() || chatLoading) return;
    const text = prompt.trim().slice(0, 500),
      current = activeRef.current,
      // 이번에 보낼 말은 history 에 넣지 않는다. 서버가 message 로 따로 받는다.
      previous = messages;
    setMessages((list) => [...list, { id: Date.now(), role: 'user', text }]);
    setChatInput('');
    setChatLoading(true);
    chatAbort.current?.abort();
    const controller = new AbortController();
    chatAbort.current = controller;
    void (async () => {
      try {
        const answer = await requestChat({
          prompt: text,
          current,
          // 카탈로그를 넘기지 않는다 (S15P21A204-424). 서버가 곡을 통째로 실어 주므로
          // 화면이 자기가 들고 있는 앞쪽 100곡에서 다시 찾을 이유가 없어졌다 — 그게
          // 추천곡이 통째로 사라지던 원인이었다.
          //
          // **숨긴 곡은 여기서 빠지지 않는다.** 예전에는 카탈로그에서 미리 걷어내는
          // 것이 곧 제외였는데, 이제는 곡 목록이 그릴 때 거르고(`chat.tsx`) 몇 곡을
          // 가렸는지 `missingTracksNotice` 가 말한다. 서버는 아직 제외 목록을 받지 않는다.
          history: previous,
          // 이어 갈 세션. null 이면 서버가 새로 연다.
          sessionId: chatSessionRef.current,
          signal: controller.signal,
        });
        if (!mountedRef.current || controller.signal.aborted) return;
        // 다음 턴이 같은 세션으로 이어지도록 받은 번호를 들고 있는다.
        // 저장에 실패했으면 null 이고, 그때는 다음 턴에 새 세션이 열린다.
        chatSessionRef.current = answer.sessionId;
        setMessages((list) => [
          ...list,
          {
            id: Date.now() + 1,
            role: 'assistant',
            text: answer.text,
            tracks: answer.tracks.length ? answer.tracks : undefined,
            // 툴 호출 기록 (S15P21A204-409). **비어 있으면 넣지 않는다** — 툴 호출이 없던
            // 턴에 화면이 "도구 호출" 줄을 그리면 거짓말이 된다.
            toolCalls: answer.toolCalls.length ? answer.toolCalls : undefined,
          },
        ]);
      } catch (error) {
        if (!mountedRef.current || controller.signal.aborted) return;
        setToast(chatErrorMessage(error));
      } finally {
        if (mountedRef.current && chatAbort.current === controller) {
          chatAbort.current = null;
          setChatLoading(false);
        }
      }
    })();
  };
  /**
   * 플레이리스트를 만든다. 곡을 함께 주면 그 곡을 담은 채로 만든다.
   *
   * 곡을 담는 것은 목록을 만드는 것과 별개의 행동이라 `PLAYLIST_ADD` 를 따로 남긴다.
   */
  const createPlaylist = async (name: string, track?: Track) => {
    const title = name.trim().slice(0, 40);
    if (!title) return;
    try {
      // **번호는 서버가 정한다.** 예전에는 `Date.now()` 로 화면 전용 번호를 만들었는데,
      // 그 값(≈1.7조)은 청취 이벤트의 playlist_id(UInt32)에 들어가지 않아 그 이벤트가
      // 실린 배치가 통째로 거부됐다.
      const id = await playlistApi.createPlaylist(title);
      if (!mountedRef.current) return;
      setPlaylists((list) => [...list, { id, name: title, tracks: [] }]);
      setSelectedPlaylist(id);
      setPlaylistDialog(false);
      // 곡을 담는 것은 목록을 만드는 것과 별개의 행동이라 PLAYLIST_ADD 를 따로 남긴다.
      if (track) await addToPlaylist(id, track);
      if (mountedRef.current) setToast('새 플레이리스트를 만들었어요.');
    } catch {
      if (mountedRef.current) setToast(playlistFailure('플레이리스트를 만들지 못했어요. 다시 시도해 주세요.'));
    }
  };
  return {
    onboardingSubmitted,
    completeOnboarding,
    tracks,
    /**
     * 카탈로그 밖에서 받아 온 곡을 화면이 알려 준다.
     *
     * 검색 결과처럼 서버에서 곡을 직접 받아 그리는 화면은 그 목록을 여기에
     * 넘긴다. 담기·재생은 스스로도 기억하지만(`addToQueue`·`placePlayedTrack`),
     * 좋아요·상세·최근 기록처럼 **곡 번호로만 오가는** 기능들은 목록을 그린
     * 시점에 이미 알고 있어야 한다.
     */
    rememberTracks,
    todayMoments,
    todayMomentNote,
    forYou,
    recommendationsReady,
    loadSimilarSections,
    page,
    go,
    goBack,
    nickname,
    saveNickname,
    displayName: demoSession ? nickname : '게스트',
    demoSession,
    authReady: authChecked,
    loginDialog,
    setLoginDialog,
    loginTriggerRef,
    openLogin,
    signInDemo,
    signOutDemo,
    withdrawMembership,
    moods,
    genres,
    active,
    playing,
    setPlaying,
    buffering,
    time,
    liked,
    ignored,
    ignoreTrack,
    clearIgnored,
    restoreIgnored,
    history,
    // 한 곡이라도 재생한 적이 있으면 정지 상태여도 미니 플레이어를 유지한다.
    hasPlayed: lastPlayedId !== null,
    moments,
    setMoments,
    // 저장한 순간·플레이리스트 변경은 이 셋을 지난다. `setMoments`/`setPlaylists` 를
    // 화면에서 직접 부르면 구간 신호(SECTION_* · PLAYLIST_*)가 남지 않는다.
    addMoments,
    removeMoment,
    removeMoments,
    replaceMoment,
    replaceMoments,
    playlists,
    setPlaylists,
    addToPlaylist,
    removeFromPlaylist,
    applyPlaylistRename,
    removePlaylist,
    selectedPlaylist,
    setSelectedPlaylist,
    libraryTab,
    setLibraryTab,
    query,
    setQuery,
    searchRevision,
    submitSearch,
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
    momentAnchorTime,
    momentOpenRevision,
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
    /*
     * 재생 목록 독이 펼쳐져 있는가.
     *
     * 예전에는 보관함 안의 한 탭이라 재생 목록을 보려면 듣던 화면을 떠나야 했다.
     * 독은 지금 화면 위로 열린다.
     */
    queueOpen,
    setQueueOpen,
    toggleQueue: () => setQueueOpen(open => !open),
    // 지금 듣고 있는 자리. 화면이 어느 줄이 울리는 줄인지 이것으로 가린다.
    currentQueueUid: currentUid,
    /** 담은 곡이 들어갈 자리. 화면의 설정에서 바꾼다. */
    queueInsertPosition,
    setQueueInsertPosition: (position: QueueInsertPosition) => {
      setInsertPosition(position);
      try {
        localStorage.setItem(QUEUE_INSERT_KEY, position);
      } catch {
        // 저장이 막혀도 이번 세션 동안은 고른 대로 동작한다.
      }
    },
    /**
     * 보이는 줄들을 받은 순서대로 다시 놓는다.
     *
     * **자리 번호로 받는다.** 화면에서 감춘 자리(건너뛴 곡)는 원래 칸에 그대로
     * 두고 보이는 칸만 새 순서로 채운다 (`lib/queue.ts` 의 `reorderQueue`).
     */
    setQueueOrder: (orderedUids: string[]) => {
      setQueue(entries => reorderQueue(entries, orderedUids));
    },
    /**
     * 재생 목록에서 뺀다. 재생 중인 곡을 빼도 지금 재생은 그대로 이어진다.
     *
     * 줄에서 부르면 자리 번호가 붙어 와 **그 줄 하나만** 빠진다.
     */
    removeFromQueue: (track: Track & { uid?: string }) => {
      const uid = track.uid ?? queue.find(entry => entry.trackId === track.id)?.uid;
      pendingRemoval.current = null;
      if (!uid) return;
      setQueue(entries => removeQueueEntry(entries, uid));
      playedInCycle.current.delete(uid);
      setToast(`‘${track.title}’ 곡을 재생 목록에서 뺐어요.`);
    },
    /**
     * 줄의 ✕ 를 누른 **그 자리**를 일러둔다.
     *
     * 빼기는 확인 대화상자를 한 번 거치는데, 그 대화상자는 뺄 대상을 곡 번호로만
     * 들고 있다. 같은 곡이 두 자리에 담겨 있으면 곡 번호만으로는 누른 줄을 되찾을
     * 수 없어 두 자리가 함께 빠진다. 그래서 누른 줄을 여기 적어 두고 아래에서
     * 그 자리 하나만 뺀다. 체크 박스로 고르는 길은 곡 단위라 적지 않는다(null).
     */
    markQueueRemoval: (uid: string | null) => { pendingRemoval.current = uid; },
    /**
     * 고른 곡을 한 번에 뺀다. 전체 선택 후 삭제하는 흐름에 쓴다.
     *
     * 곡 번호로 받으므로 같은 곡이 여러 자리에 담겨 있으면 그 자리들이 함께
     * 빠진다 — 고르기 화면이 곡 단위로 체크하기 때문이고, 확인 문구도 "고른 N곡"
     * 으로 그 수를 미리 알려 준다. 줄의 ✕ 로 한 자리를 짚어 온 경우만 예외다.
     */
    removeManyFromQueue: (ids: number[]) => {
      if (!ids.length) return;
      const aimed = pendingRemoval.current;
      pendingRemoval.current = null;
      const uids =
        ids.length === 1 && aimed && queue.some(e => e.uid === aimed && e.trackId === ids[0])
          ? [aimed]
          : queue.filter(entry => ids.includes(entry.trackId)).map(entry => entry.uid);
      setQueue(entries => removeQueueEntries(entries, uids));
      uids.forEach(uid => playedInCycle.current.delete(uid));
      setToast(`${uids.length}곡을 재생 목록에서 뺐어요.`);
    },
    /**
     * 곡을 담는다. 들어갈 자리는 설정(`queueInsertPosition`)이 정한다.
     *
     * **같은 곡을 두 번 담지 않는다.** 이미 담긴 곡을 또 담으면 거절하지 않고
     * 그 자리를 걷어 새 자리로 옮긴다 — 방금 담은 곡이 목록 어딘가에 묻혀 있으면
     * 담은 것처럼 보이지 않는다.
     *
     * 100곡이 차 있어도 거절하지 않고 가장 오래 머문 자리를 밀어낸다.
     */
    addToQueue: (track: Track) => {
      // 담는 곡이 카탈로그 밖일 수 있다 — 검색 결과의 담기 버튼이 그 길이다.
      rememberTracks([track]);
      const result = placeInQueue(queue, track.id, queueInsertPosition, currentUidRef.current, QUEUE_LIMIT);
      setQueue(result.entries);
      if (result.evictedTrackId !== null) {
        queue
          .filter(entry => entry.trackId === result.evictedTrackId)
          .forEach(entry => playedInCycle.current.delete(entry.uid));
      }
      /*
       * 듣고 있던 자리를 걷어 냈으면 **재생 커서를 새 자리 번호로 옮긴다.**
       * 옮긴 자리는 번호를 새로 받으므로(0023 규칙 3) 그러지 않으면 화면이 울리는
       * 줄을 잃고(`currentQueueUid` 가 없는 번호를 가리킨다) 다음 곡 계산도 목록
       * 맨 앞에서 다시 시작한다 — 듣는 곡을 다시 담은 것만으로 그랬다.
       */
      if (result.replacedCurrent) {
        const before = currentUidRef.current;
        // 셔플 한 바퀴에 들었다는 표시도 새 번호로 물려준다. 잃으면 방금 들은
        // 곡이 그 바퀴에 한 번 더 걸린다.
        if (before && playedInCycle.current.delete(before)) playedInCycle.current.add(result.uid);
        currentUidRef.current = result.uid;
        setCurrentUid(result.uid);
      }

      // 알림은 한 줄뿐이다. 사용자가 몰랐을 법한 것부터 고른다 —
      // 설정대로 못 넣은 것 > 밀려난 곡 > 자리를 옮긴 것 > 그냥 담은 것.
      if (result.fellBack) {
        setToast('재생 중인 곡이 없어 맨 아래에 담았어요.');
      } else if (result.stayed) {
        setToast(`‘${track.title}’ 곡은 지금 듣는 곡이라 자리를 그대로 두었어요.`);
      } else if (result.evictedTrackId !== null) {
        setToast(`${QUEUE_LIMIT}곡이 초과되어 가장 오래된 곡이 삭제됩니다.`);
      } else if (result.moved) {
        setToast(`‘${track.title}’ 곡을 ${INSERT_POSITION_LABEL[queueInsertPosition]}로 옮겼어요.`);
      } else {
        setToast(`‘${track.title}’ 곡을 재생 목록에 담았어요.`);
      }

    },
    playPlaylist,
    selectedArtist,
    setSelectedArtist,
    showAllSimilar,
    setShowAllSimilar,
    messageEndRef,
    chatBoxRef,
    chatInputRef,
    chatButtonRef,
    play,
    next,
    seek: seekByUser,
    openTrack,
    openMoment,
    previewMoment,
    playSavedMoment,
    playSavedMoments,
    stopSavedMomentPlayback,
    savedMomentPlayback,
    saveMoment,
    toggleLike,
    openChat,
    closeChat,
    startNewChat,
    sendMessage,
    createPlaylist,
  };
}
export type MupidModel = ReturnType<typeof useMupid>;
