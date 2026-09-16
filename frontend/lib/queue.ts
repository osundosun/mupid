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
 * 화면은 건너뛴 곡(`ignored`)이나 카탈로그에서 사라진 곡을 감추므로
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

  const currentIndex = currentUid ? rest.findIndex((entry) => entry.uid === currentUid) : -1;
  const fellBack = position === 'afterCurrent' && currentIndex < 0;
  const at =
    position === 'top' ? 0
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

  return { entries: next, moved, fellBack, evictedTrackId, uid: placed.uid };
}
