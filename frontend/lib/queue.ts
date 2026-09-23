/**
 * 재생 목록의 '자리'.
 *
 * 큐를 곡 번호로만 세면 같은 곡을 두 번 담을 수 없다. 두 자리가 같은 번호를
 * 갖는 순간 **어느 자리를 지웠는지, 어느 자리를 끌었는지, 지금 어느 자리를
 * 듣고 있는지** 를 구분할 길이 사라지기 때문이다. 그래서 곡이 아니라 자리에
 * 번호를 붙인다 — 큐의 단위는 곡이 아니라 자리다.
 */
export type QueueEntry = { uid: string; trackId: number };

/** 자리 번호가 붙은 것들의 공통 모양. 큐 항목이든, 곡을 붙여 화면에 내보낸 값이든. */
export type Keyed = { uid: string };

// 앱이 살아 있는 동안만 겹치지 않으면 된다. 큐는 저장되지 않으므로(preferences
// 스키마에 없다) 새로고침 뒤에 같은 번호가 다시 나와도 문제가 없다.
let counter = 0;

/** 새 자리 번호. */
export function newQueueUid(): string {
  counter += 1;
  return `q${counter}`;
}

/** 곡 하나를 담을 새 자리. */
export function queueEntry(trackId: number): QueueEntry {
  return { uid: newQueueUid(), trackId };
}

/** 곡 번호 목록을 자리 목록으로. 같은 번호가 섞여 있어도 각각 다른 자리가 된다. */
export function toQueueEntries(trackIds: number[]): QueueEntry[] {
  return trackIds.map(queueEntry);
}

/**
 * 보이는 자리들만 요청한 순서로 다시 놓는다.
 *
 * 화면은 건너뛴 곡(`ignored`)이나 곡 정보를 모르는 곡을 감추므로
 * `orderedUids` 는 큐의 일부다. 예전에는 보이는 자리를 앞으로 몰고 감춘 자리를
 * 뒤에 이어 붙였는데, 그러면 사용자가 건드린 적 없는 자리까지 큐 맨 뒤로
 * 밀려났다. 여기서는 **감춘 자리를 원래 칸에 그대로 두고** 보이는 칸만 요청한
 * 순서로 채운다.
 */
export function reorderQueue<T extends Keyed>(entries: T[], orderedUids: string[]): T[] {
  const byUid = new Map(entries.map((entry) => [entry.uid, entry]));
  // 큐에 없는 번호가 섞여 들어와도 칸 수가 어긋나지 않게 걸러 둔다.
  const ordered = orderedUids
    .map((uid) => byUid.get(uid))
    .filter((entry): entry is T => !!entry);
  const moving = new Set(ordered.map((entry) => entry.uid));
  let taken = 0;
  return entries.map((entry) => (moving.has(entry.uid) ? ordered[taken++] : entry));
}

/** 자리 하나만 뺀다. 같은 곡이 다른 자리에 또 담겨 있어도 그쪽은 남는다. */
export function removeQueueEntry<T extends Keyed>(entries: T[], uid: string): T[] {
  return entries.filter((entry) => entry.uid !== uid);
}

/** 고른 자리들을 한 번에 뺀다. */
export function removeQueueEntries<T extends Keyed>(entries: T[], uids: string[]): T[] {
  const gone = new Set(uids);
  return entries.filter((entry) => !gone.has(entry.uid));
}

/** 지금 듣고 있는 자리의 칸 번호. 목록 밖의 곡을 듣는 중이면 -1. */
export function queueIndexOf(items: Keyed[], uid: string | null | undefined): number {
  if (!uid) return -1;
  return items.findIndex((item) => item.uid === uid);
}

/**
 * 지금 자리에서 한 칸 움직인 자리.
 *
 * **자리 번호로 찾는다.** 곡 번호로 찾으면 같은 곡이 여러 번 담겼을 때 늘
 * 맨 앞 중복을 지금 자리로 착각해, 목록 뒤쪽을 듣다가도 재생이 앞으로
 * 되감겼다. 지금 자리를 모르면(목록 밖의 곡을 튼 직후) 목록의 끝에서 이어받는다.
 */
export function stepQueue<T extends Keyed>(
  items: T[],
  uid: string | null | undefined,
  direction = 1,
): T | null {
  if (!items.length) return null;
  const index = queueIndexOf(items, uid);
  if (index < 0) return items[direction >= 0 ? 0 : items.length - 1];
  return items[(index + direction + items.length) % items.length];
}

/**
 * 지금 자리 **다음에** 올 자리들을 순서대로.
 *
 * 미리 받아 둘 곡을 고를 때 쓴다. 예전에는 큐 맨 앞에서 세 곡을 집어서,
 * 목록 뒤쪽을 듣고 있으면 정작 다음에 울릴 곡이 준비되지 않았다.
 */
export function upcomingQueue<T extends Keyed>(items: T[], uid: string | null | undefined): T[] {
  const index = queueIndexOf(items, uid);
  if (index < 0) return items.slice();
  return [...items.slice(index + 1), ...items.slice(0, index)];
}

/** 담은 곡이 큐의 어디로 들어갈지. 사용자가 고른다. */
export type QueueInsertPosition = 'bottom' | 'top' | 'afterCurrent';

/** `placeInQueue` 가 알려 주는, 담는 과정에서 실제로 일어난 일. */
export type QueuePlacement = {
  entries: QueueEntry[];
  /** 이미 담겨 있던 곡이라 자리를 옮긴 것이다. */
  moved: boolean;
  /** `afterCurrent` 인데 듣고 있는 자리가 없어 맨 아래로 내려갔다. */
  fellBack: boolean;
  /**
   * 걷어 낸 자리가 **지금 듣고 있던 자리**다.
   *
   * 자리를 옮기면 번호가 새로 붙으므로(`uid`) 호출부는 **재생 커서를 새 번호로
   * 옮겨야 한다.** 그러지 않으면 듣고 있는 곡을 다시 담은 것만으로 화면이 울리는
   * 줄을 잃고(`currentQueueUid` 가 없는 번호를 가리킨다), 다음 곡 계산도 목록
   * 맨 앞에서 다시 시작한다.
   */
  replacedCurrent: boolean;
  /**
   * 제자리에 다시 놓았다 — `afterCurrent` 인데 담는 곡이 **곧 지금 듣는 곡**인
   * 경우다. 옮겼다고 알리면 거짓말이 되므로 호출부가 문구를 가른다.
   */
  stayed: boolean;
  /** 상한이 차서 밀려난 곡. 없으면 null. */
  evictedTrackId: number | null;
  /** 새로 놓인 자리의 번호. */
  uid: string;
};

/**
 * 곡 하나를 큐에 놓는다.
 *
 * **같은 곡을 두 번 담지 않는다.** 이미 담겨 있으면 그 자리를 걷어내고 새 자리에
 * 다시 놓는다 — "또 담기" 는 거절이 아니라 **맨 최신 자리로 옮기기** 다. 방금 담은
 * 곡이 목록 어딘가에 묻혀 있으면 담긴 것처럼 보이지 않기 때문이다.
 *
 * 상한을 넘기면 **새 곡이 들어온 반대쪽 끝**에서 뺀다. 그쪽이 가장 오래 머문
 * 자리다 — 맨 아래로 담으면 위쪽이, 맨 위로 담으면 아래쪽이 묵는다.
 * `afterCurrent` 는 가운데로 들어오므로 재생 커서에서 가장 먼 아래쪽을 뺀다.
 * 지금 울리는 자리와 방금 놓은 자리는 건너뛴다 — 방금 놓은 것을 도로 빼면
 * 담기 버튼이 아무 일도 하지 않는 것처럼 보인다.
 *
 * **담는 곡이 지금 듣는 곡 자신이면 `afterCurrent` 의 기준점은 그 곡이다.**
 * 그 자리를 걷어 낸 뒤에 기준점을 찾으면 없으므로, 예전에는 "재생 중인 곡이
 * 없다" 로 읽어 맨 아래로 내리고 그렇게 알렸다 — 플레이어의 곡 더보기로 담으면
 * 담는 대상이 언제나 듣고 있는 곡이라 **늘 그 길로 빠졌다.** 자기 다음은 곧
 * 제자리이므로 걷어 낸 그 칸에 다시 놓는다 (S15P21A204-429).
 */
export function placeInQueue(
  entries: QueueEntry[],
  trackId: number,
  position: QueueInsertPosition,
  currentUid: string | null,
  limit: number,
): QueuePlacement {
  const moved = entries.some((entry) => entry.trackId === trackId);
  const rest = entries.filter((entry) => entry.trackId !== trackId);
  const placed = queueEntry(trackId);

  /*
   * 걷어 낸 자리 가운데 지금 듣던 자리가 있는가. 있으면 `rest` 에서 그 칸이
   * 몇 번째였는지 함께 센다 — 같은 곡이 여러 자리에 있던 큐(예전 저장본)에서도
   * 칸이 어긋나지 않게 앞쪽에서 빠진 수를 덜어 낸다.
   */
  const heldIndex = currentUid
    ? entries.findIndex((entry) => entry.uid === currentUid && entry.trackId === trackId)
    : -1;
  const replacedCurrent = heldIndex >= 0;
  const heldRestIndex = replacedCurrent
    ? heldIndex - entries.slice(0, heldIndex).filter((entry) => entry.trackId === trackId).length
    : -1;

  const currentIndex = currentUid ? rest.findIndex((entry) => entry.uid === currentUid) : -1;
  const stayed = position === 'afterCurrent' && replacedCurrent;
  const fellBack = position === 'afterCurrent' && currentIndex < 0 && !stayed;
  const at =
    position === 'top' ? 0
      : stayed ? heldRestIndex
        : position === 'afterCurrent' && currentIndex >= 0 ? currentIndex + 1
          : rest.length;

  const next = [...rest.slice(0, at), placed, ...rest.slice(at)];

  let evictedTrackId: number | null = null;
  if (next.length > limit) {
    const fromTop = position === 'bottom' || fellBack;
    const order = fromTop
      ? next.map((_, index) => index)
      : next.map((_, index) => next.length - 1 - index);
    for (const index of order) {
      const entry = next[index];
      if (entry.uid === placed.uid || entry.uid === currentUid) continue;
      evictedTrackId = entry.trackId;
      next.splice(index, 1);
      break;
    }
  }

  return {
    entries: next,
    moved,
    fellBack,
    replacedCurrent,
    stayed,
    evictedTrackId,
    uid: placed.uid,
  };
}

/**
 * 자동 추천을 판정하는 순간의 재생 상태. 요청 전과 응답 후를 비교한다.
 *
 * `playback` 은 재생을 새로 걸 때마다 오르는 번호다 — 같은 곡을 다시 틀어도 바뀌므로
 * "사용자가 그 사이 무언가를 틀었다" 를 곡 번호보다 정확히 가린다.
 */
export type AutoRecommendSnapshot = {
  enabled: boolean;
  playback: number;
  currentUid: string | null;
  queue: QueueEntry[];
};

/**
 * 추천 곡을 받아 **큐 맨 아래에** 이어 붙인다.
 *
 * 꺼져 있으면 묻지도 않는다. 응답을 기다리는 사이 이어듣기를 껐거나, 다른 곡을 틀었거나,
 * 큐가 바뀌었으면 결과를 버린다 — 늦게 온 추천이 사용자가 방금 만든 상태를 덮으면
 * 안 된다. 실패와 빈 응답도 조용히 null 이다(재시도·대체곡 없음).
 *
 * **한 곡이 아니라 받은 만큼 붙인다.** 한 곡씩 붙이면 곡이 끝날 때마다 추천을 새로
 * 물어야 하는데, 그 응답이 몇 초씩 걸려 목록 끝마다 소리가 끊긴다. 미리 여러 곡을
 * 받아 두면 기존 오디오 미리 받기(`upcomingQueue` 로 고른 다음 3곡)가 그대로 데워 준다.
 */
export async function autoRecommendAtEnd<T extends { id: number }>(
  read: () => AutoRecommendSnapshot,
  recommend: () => Promise<T[]>,
  limit: number,
): Promise<{ tracks: T[]; entries: QueueEntry[]; firstUid: string } | null> {
  const before = read();
  if (!before.enabled) return null;
  let found: T[];
  try {
    found = await recommend();
  } catch {
    return null;
  }
  const after = read();
  if (
    !after.enabled ||
    after.playback !== before.playback ||
    after.currentUid !== before.currentUid ||
    after.queue !== before.queue
  ) {
    return null;
  }
  if (!found.length) return null;
  let entries = after.queue;
  let firstUid = '';
  for (const track of found) {
    const placed = placeInQueue(entries, track.id, 'bottom', after.currentUid, limit);
    entries = placed.entries;
    if (!firstUid) firstUid = placed.uid;
  }
  return { tracks: found, entries, firstUid };
}

/** 자동 스크롤이 반응하는 위아래 가장자리 폭(px). */
export const AUTO_SCROLL_EDGE = 56;
/** 한 프레임에 밀 수 있는 최대 거리(px). */
export const AUTO_SCROLL_MAX_STEP = 16;

/**
 * 끄는 손이 목록의 위아래 가장자리에 닿았을 때 한 프레임에 밀 거리.
 *
 * 가운데에서는 0 이고, 가장자리로 깊이 들어갈수록 빨라진다 — 끝을 살짝 넘긴
 * 정도면 천천히 기어가야 놓을 자리를 겨눌 수 있고, 완전히 벗어나면 100곡짜리
 * 목록도 한 번에 가로지를 만큼 빨라야 한다. 위로 밀 때는 음수다.
 */
export function autoScrollDelta(pointerY: number, top: number, bottom: number): number {
  if (pointerY < top + AUTO_SCROLL_EDGE) {
    const depth = Math.min(top + AUTO_SCROLL_EDGE - pointerY, AUTO_SCROLL_EDGE);
    return -Math.ceil((depth / AUTO_SCROLL_EDGE) * AUTO_SCROLL_MAX_STEP);
  }
  if (pointerY > bottom - AUTO_SCROLL_EDGE) {
    const depth = Math.min(pointerY - (bottom - AUTO_SCROLL_EDGE), AUTO_SCROLL_EDGE);
    return Math.ceil((depth / AUTO_SCROLL_EDGE) * AUTO_SCROLL_MAX_STEP);
  }
  return 0;
}
