import type {
  EventParams,
  EventType,
  FeedbackTarget,
  ListenEvent,
  PlayContext,
  SkipTrigger,
} from './types';
import type { Transport } from './transport';

/**
 * 서버가 아직 없을 때 쓰는 기본 싱크. 데모 모드에서 이벤트 흐름만 눈으로 확인한다.
 *
 * 이 파일이 값(value)을 relative import 하지 않는 것은 의도적이다.
 * `node --experimental-strip-types` 는 확장자 없는 상대 경로를 해석하지 못하는데,
 * 저장소의 import 관례는 확장자를 붙이지 않는다. 타입 import 는 지워지므로 안전하고,
 * 덕분에 이 모듈은 번들러 없이 `node --test` 로 그대로 돌릴 수 있다.
 */
export const consoleTransport: Transport = async (events) => {
  for (const event of events) console.info('[analytics]', event.event_type, event);
};

/** keepalive 본문이 64KB 로 제한되므로 배치를 작게 잡는다. */
const BATCH_SIZE = 20;
const FLUSH_MS = 5000;
export const HEARTBEAT_MS = 5000;
const STORE_KEY = 'mupid.analytics.pending.v1';
/** 오프라인이 길어져도 저장소를 무한정 먹지 않게 한다. 넘치면 오래된 것부터 버린다. */
const STORE_LIMIT = 500;

export type TrackerOptions = {
  transport?: Transport;
  /** 테스트에서 시계를 고정하기 위한 주입점. */
  now?: () => number;
  /** `null` 이면 저장소 백업을 쓰지 않는다. 생략하면 `localStorage` 가 있을 때만 쓴다. */
  storage?: Pick<Storage, 'getItem' | 'setItem'> | null;
};

const randomId = (): string => {
  const c = globalThis.crypto;
  if (c?.randomUUID) return c.randomUUID();
  // randomUUID 는 보안 컨텍스트를 요구하지만 getRandomValues 는 요구하지 않는다.
  const bytes = new Uint8Array(16);
  if (c?.getRandomValues) c.getRandomValues(bytes);
  else for (let i = 0; i < 16; i++) bytes[i] = Math.floor(Math.random() * 256);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
};

const isEvent = (value: unknown): value is ListenEvent =>
  !!value &&
  typeof value === 'object' &&
  typeof (value as ListenEvent).event_id === 'string' &&
  typeof (value as ListenEvent).event_type === 'string' &&
  typeof (value as ListenEvent).occurred_at === 'string';

const ms = (value: number) => Math.max(0, Math.round(value));

export function createTracker(options: TrackerOptions = {}) {
  const transport = options.transport ?? consoleTransport;
  const now = options.now ?? (() => Date.now());
  const storage =
    options.storage === undefined
      ? typeof localStorage === 'undefined'
        ? null
        : localStorage
      : options.storage;

  let pending: ListenEvent[] = [];
  let sending = false;
  let timer: ReturnType<typeof setTimeout> | null = null;

  // 재생 하나의 상태. play_id 는 PLAY_START 에서 발급하고 재생이 끝날 때까지 유지한다.
  let play: (PlayContext & { id: string }) | null = null;
  /** 직전 재생 포인트(ms). 다음 하트비트가 여기서부터 들었다고 보고한다. */
  let pointerMs = 0;
  let pointerAt = 0;

  const persist = () => {
    if (!storage) return;
    try {
      storage.setItem(STORE_KEY, JSON.stringify(pending));
    } catch {
      /* 저장소가 막혀 있어도 수집은 계속한다. 유실 위험만 남는다. */
    }
  };

  const restore = () => {
    if (!storage) return;
    try {
      const raw: unknown = JSON.parse(storage.getItem(STORE_KEY) || '[]');
      if (Array.isArray(raw)) pending = raw.filter(isEvent).slice(-STORE_LIMIT);
    } catch {
      /* 손상된 값은 버린다. */
    }
  };

  const schedule = () => {
    if (timer) return;
    timer = setTimeout(() => {
      timer = null;
      void flush();
    }, FLUSH_MS);
  };

  const flush = async (leaving = false): Promise<void> => {
    if (timer) {
      clearTimeout(timer);
      timer = null;
    }
    if (sending || !pending.length) return;
    sending = true;
    const batch = pending.slice(0, BATCH_SIZE);
    pending = pending.slice(batch.length);
    persist();
    try {
      await transport(batch, leaving);
    } catch {
      // 실패한 배치를 앞에 되돌린다. occurred_at 은 이미 각인돼 있으므로 몇 분 뒤에
      // 도착해도 발생 시각은 정확하다. 중복 재전송은 서버가 event_id 로 걸러낸다.
      pending = [...batch, ...pending].slice(-STORE_LIMIT);
      persist();
    } finally {
      sending = false;
    }
    if (pending.length) schedule();
  };

  /**
   * 이벤트를 큐에 넣는다. `occurred_at` 은 **여기서** 찍는다 — 전송 시각이 아니라
   * 발생 시각이다. 배치로 묶여 나가도 각 이벤트는 자기 시각을 들고 간다.
   */
  const track = (
    type: EventType,
    params: EventParams = {},
    extra: Partial<ListenEvent> = {},
    withPlay = true,
  ) => {
    const event: ListenEvent = {
      event_id: randomId(),
      event_type: type,
      occurred_at: new Date(now()).toISOString(),
      ...params,
      ...extra,
    };
    if (withPlay && play) {
      event.play_id = play.id;
      event.track_id ??= play.trackId;
      // entry_path 를 재생의 모든 이벤트에 싣는다. 스킵 해석과 커버리지가 진입 경로를
      // 필요로 하는데, 그때마다 PLAY_START 를 되짚는 JOIN 을 두지 않기 위해서다.
      event.entry_path = play.entryPath;
    }
    pending.push(event);
    if (pending.length > STORE_LIMIT) pending = pending.slice(-STORE_LIMIT);
    persist();
    if (pending.length >= BATCH_SIZE) void flush();
    else schedule();
    return event;
  };

  const movePointer = (positionMs: number) => {
    pointerMs = positionMs;
    pointerAt = now();
  };

  /**
   * 포인터부터 현재 위치까지를 "연속으로 들었다" 고 보고하고 포인터를 옮긴다.
   *
   * 재생 위치가 경과 시간보다 훨씬 많이 뛰었으면 추적하지 못한 점프로 보고 버린다.
   * 보고하지 않더라도 포인터는 항상 옮긴다 — 다음 하트비트가 듣지 않은 구간까지
   * 들었다고 보고하면 안 되기 때문이다.
   */
  const commitProgress = (positionMs: number) => {
    if (!play) return;
    const at = now();
    const advance = positionMs - pointerMs;
    if (advance > 0 && advance <= (at - pointerAt) * 2 + 2000) {
      track('PLAY_PROGRESS', { from_ms: ms(pointerMs), position_ms: ms(positionMs) });
    }
    pointerMs = positionMs;
    pointerAt = at;
  };

  const closePlay = (type: 'PLAY_END' | 'PLAYBACK_ABANDON', positionMs: number) => {
    if (!play) return;
    commitProgress(positionMs);
    const event = track(type, { position_ms: ms(positionMs) });
    play = null;
    return event;
  };

  const feedback = (
    type: 'LIKE' | 'UNLIKE' | 'DISLIKE' | 'UNDISLIKE',
    positionMs: number,
    target: FeedbackTarget,
    trackId?: number,
  ) =>
    track(
      type,
      { position_ms: ms(positionMs), target },
      trackId === undefined ? {} : { track_id: trackId },
    );

  restore();

  return {
    /**
     * 재생 하나를 시작한다. 이전 재생이 아직 열려 있으면 `PLAYBACK_ABANDON` 으로 닫는다 —
     * 사용자가 넘긴 것이라면 앱이 먼저 `skip()` 을 부르므로, 여기 걸린다는 건 재생이
     * 설명 없이 끊겼다는 뜻이다. 스킵으로 오해해 부정 신호를 주면 안 된다.
     */
    startPlay(context: PlayContext, positionMs = 0) {
      if (play) closePlay('PLAYBACK_ABANDON', pointerMs);
      play = { ...context, id: randomId() };
      movePointer(positionMs);
      return track(
        'PLAY_START',
        { position_ms: ms(positionMs) },
        context.rec && {
          rec_id: context.rec.id,
          rec_type: context.rec.type,
          rec_rank: context.rec.rank,
        },
      );
    },

    /** 곡이 끝까지 재생됐다. 스킵과 구분된다. */
    endPlay: (positionMs: number) => closePlay('PLAY_END', positionMs),

    /** 앱이 닫혀 재생이 끊겼다. 의도한 스킵이 아니므로 부정 신호가 아니다. */
    abandonPlay: (positionMs: number) => closePlay('PLAYBACK_ABANDON', positionMs),

    pause(positionMs: number) {
      commitProgress(positionMs);
      return track('PLAY_PAUSE', { position_ms: ms(positionMs) });
    },

    resume(positionMs: number) {
      // 멈춰 있는 동안 위치가 옮겨졌을 수 있으므로 포인터를 현재로 맞춘다.
      movePointer(positionMs);
      return track('PLAY_RESUME', { position_ms: ms(positionMs) });
    },

    /** 버퍼링. "안 들린 것" 을 "안 좋아한 것" 으로 세지 않기 위해 남긴다. */
    stall: (positionMs: number) => track('PLAY_STALL', { position_ms: ms(positionMs) }),

    error: (positionMs: number, reason: string) =>
      track('PLAY_ERROR', { position_ms: ms(positionMs), reason }),

    /** 5초마다 호출한다. `audio.currentTime` 을 **매번 실제로 읽어서** 넘긴다. */
    heartbeat: (positionMs: number) => commitProgress(positionMs),

    /**
     * 사용자가 재생 위치를 옮겼다. 프로그램이 건 seek 과 사용자가 건 seek 은
     * 오디오 엘리먼트만 봐서는 구분할 수 없어 앱이 직접 호출한다.
     */
    seek(fromMs: number, toMs: number) {
      commitProgress(fromMs);
      const event = track(toMs < fromMs ? 'SEEK_BACK' : 'SEEK_FORWARD', {
        from_ms: ms(fromMs),
        position_ms: ms(toMs),
      });
      movePointer(toMs);
      return event;
    },

    /**
     * 스킵은 **원시 신호만** 남긴다 — 무엇으로 넘겼는지(`skip_trigger`)와 언제
     * 넘겼는지(`position_ms`). EARLY / AFTER_HOOK / LATE 분류는 ClickHouse
     * `v_track_skips` 가 쿼리 시점에 계산한다. 프론트는 "이 구간을 전에 들었는지" 를
     * 알 수 없고, 알 필요도 없다.
     */
    skip(positionMs: number, trigger: SkipTrigger) {
      if (!play) return;
      commitProgress(positionMs);
      const event = track('TRACK_SKIP', {
        position_ms: ms(positionMs),
        skip_trigger: trigger,
      });
      play = null;
      return event;
    },

    // 재생 중이 아닐 때(라이브러리 목록 등)는 trackId 를 직접 준다. 없으면 곡을 특정할 수
    // 없어 이벤트는 남지만 구간 신호가 만들어지지 않는다.
    like: (positionMs: number, target: FeedbackTarget = 'TRACK', trackId?: number) =>
      feedback('LIKE', positionMs, target, trackId),
    unlike: (positionMs: number, target: FeedbackTarget = 'TRACK', trackId?: number) =>
      feedback('UNLIKE', positionMs, target, trackId),
    dislike: (positionMs: number, target: FeedbackTarget = 'TRACK', trackId?: number) =>
      feedback('DISLIKE', positionMs, target, trackId),
    undislike: (target: FeedbackTarget = 'TRACK', trackId?: number) =>
      feedback('UNDISLIKE', 0, target, trackId),

    // 범위와 함께 position_ms 에 시작점을 넣는다. 백엔드의 구간 매핑
    // (mv_user_section_signals) 이 position_ms 한 점으로 dictGet 하고
    // `position_ms > 0` 을 요구하기 때문에, 이걸 빼면 가장 강한 긍정 신호(+1.5)가
    // 아예 만들어지지 않는다. 범위가 여러 구간에 걸치면 시작점이 속한 구간만 잡힌다.
    markSection: (startMs: number, endMs: number) =>
      track('SECTION_MARK', {
        start_ms: ms(startMs),
        end_ms: ms(endMs),
        position_ms: ms(startMs),
      }),
    unmarkSection: (startMs: number, endMs: number) =>
      track('SECTION_UNMARK', {
        start_ms: ms(startMs),
        end_ms: ms(endMs),
        position_ms: ms(startMs),
      }),

    addToPlaylist: (playlistId: number, positionMs: number, trackId?: number) =>
      track(
        'PLAYLIST_ADD',
        { playlist_id: playlistId, position_ms: ms(positionMs) },
        trackId === undefined ? {} : { track_id: trackId },
      ),
    removeFromPlaylist: (playlistId: number, trackId?: number) =>
      track(
        'PLAYLIST_REMOVE',
        { playlist_id: playlistId },
        trackId === undefined ? {} : { track_id: trackId },
      ),

    addComment: (content: string, positionMs: number, commentId?: number) =>
      track('COMMENT_ADD', {
        content,
        position_ms: ms(positionMs),
        ...(commentId !== undefined && { comment_id: commentId }),
      }),
    deleteComment: (commentId: number) => track('COMMENT_DELETE', { comment_id: commentId }),

    /** 검색은 재생 밖의 행동이라 재생 맥락을 붙이지 않는다. */
    search: (query: string) => track('SEARCH', { search_query: query }, {}, false),

    flush,

    /** 아직 나가지 않은 이벤트. 테스트·디버그용이다. */
    peek: () => [...pending],
    get playId() {
      return play?.id ?? null;
    },
  };
}

export type Tracker = ReturnType<typeof createTracker>;
