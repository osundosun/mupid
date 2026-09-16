'use client';
import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { GripVertical, X } from 'lucide-react';
import type { MupidModel, QueueTrack } from '@/hooks/use-mupid';
import type { TrackSelection } from './track-selection';
import { TrackRow } from './primitives';

// 재생 목록은 순서가 곧 다음에 들을 곡이라 끌어서 바꿀 수 있어야 한다.
// 포인터 이벤트 하나로 마우스와 터치를 함께 처리하고, 키보드는 위/아래 화살표로 옮긴다.
//
// 줄을 가리키는 것은 곡 번호가 아니라 **자리 번호**(`track.uid`)다. 같은 곡을 여러
// 번 담을 수 있게 되면서 곡 번호로는 어느 줄을 끌었는지 알 수 없어졌다 — 곡 번호로
// 찾으면 두 번째 줄을 끌어도 늘 첫 번째 줄이 움직였다.
export function QueueList({
  model: m,
  selection,
  onRemove,
}: {
  model: MupidModel;
  selection: TrackSelection;
  onRemove: (track: QueueTrack) => void;
}) {
  const listRef = useRef<HTMLDivElement>(null);
  const [dragUid, setDragUid] = useState<string | null>(null);
  const [liveUids, setLiveUids] = useState<string[] | null>(null);

  const baseUids = m.queueTracks.map((t) => t.uid);
  const uids = liveUids ?? baseUids;
  const tracks = uids
    .map((uid) => m.queueTracks.find((t) => t.uid === uid))
    .filter((t): t is QueueTrack => !!t);

  const move = (uid: string, to: number) => {
    const from = baseUids.indexOf(uid);
    const target = Math.max(0, Math.min(baseUids.length - 1, to));
    if (from < 0 || from === target) return;
    const next = baseUids.slice();
    next.splice(from, 1);
    next.splice(target, 0, uid);
    m.setQueueOrder(next);
    m.setToast(`${target + 1}번째로 옮겼어요.`);
    // 화살표로 옮기면 줄이 통째로 자리를 옮기는데, 그때 손잡이가 DOM 에서 밀려나며
    // 포커스를 잃는다. 연달아 누르지 못하면 키보드로는 한 칸밖에 못 옮긴다.
    requestAnimationFrame(() => {
      listRef.current
        ?.querySelector<HTMLButtonElement>(`[data-queue-uid="${uid}"] .queue-handle`)
        ?.focus();
    });
  };

  const onPointerDown = (uid: string) => (event: ReactPointerEvent<HTMLButtonElement>) => {
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    setDragUid(uid);
    setLiveUids(baseUids);
  };

  const onPointerMove = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (dragUid === null || !liveUids) return;
    const rows = [...(listRef.current?.querySelectorAll('[data-queue-uid]') ?? [])];
    const from = liveUids.indexOf(dragUid);
    let to = from;
    rows.forEach((row, index) => {
      const box = row.getBoundingClientRect();
      if (event.clientY >= box.top && event.clientY <= box.bottom) to = index;
    });
    if (to === from) return;
    const next = liveUids.slice();
    next.splice(from, 1);
    next.splice(to, 0, dragUid);
    setLiveUids(next);
  };

  const onPointerUp = () => {
    if (dragUid !== null && liveUids) {
      const to = liveUids.indexOf(dragUid);
      setDragUid(null);
      setLiveUids(null);
      move(dragUid, to);
      return;
    }
    setDragUid(null);
    setLiveUids(null);
  };

  // 고르는 중에는 손잡이를 접어 둔다. 순서 바꾸기와 섞이면 헷갈린다.
  const picking = selection.active;
  // 체크 박스로 고르는 길은 곡 단위다. 앞서 ✕ 로 짚어 둔 자리가 남아 있으면
  // 고른 곡 중 한 자리만 빠지므로 여기서 지운다.
  useEffect(() => {
    if (picking) m.markQueueRemoval(null);
  }, [picking]);

  return (
    <div className={`track-list queue-list ${picking ? 'is-picking' : ''}`} ref={listRef}>
      {tracks.map((track, index) => (
        <div
          key={track.uid}
          data-queue-uid={track.uid}
          data-queue-id={track.id}
          className={`queue-item ${dragUid === track.uid ? 'is-dragging' : ''} ${
            m.currentQueueUid === track.uid ? 'is-current' : ''
          }`}
          // 같은 곡이 여러 번 담겨 있으면 제목만으로는 어느 줄이 울리는 줄인지
          // 알 수 없다. 지금 듣고 있는 자리만 표시한다.
          aria-current={m.currentQueueUid === track.uid ? 'true' : undefined}
        >
          {/*
            줄 맨 앞 칸. 평소에는 끌기 손잡이가, 고르는 중에는 체크 박스가 선다.
            **같은 칸을 나눠 쓴다** — 체크를 줄 안에 따로 두면 그 폭만큼 커버와
            제목이 옆으로 밀린다.
          */}
          {picking ? (
            <input
              type="checkbox"
              className="queue-check"
              checked={selection.has(track.id)}
              aria-label={`${track.title} 선택`}
              onChange={() => selection.toggle(track.id)}
            />
          ) : (
            <button
              className="queue-handle"
              aria-label={`${track.title} 순서 바꾸기. ${index + 1}번째, 위아래 화살표로 이동`}
              onPointerDown={onPointerDown(track.uid)}
              onPointerMove={onPointerMove}
              onPointerUp={onPointerUp}
              onPointerCancel={onPointerUp}
              onKeyDown={(event) => {
                if (event.key === 'ArrowUp') { event.preventDefault(); move(track.uid, index - 1); }
                if (event.key === 'ArrowDown') { event.preventDefault(); move(track.uid, index + 1); }
              }}
            >
              <GripVertical aria-hidden="true" />
            </button>
          )}
          <TrackRow
            track={track}
            model={m}
            selected={selection.has(track.id)}
            onSelect={picking ? () => selection.toggle(track.id) : undefined}
            // 체크는 위의 손잡이 칸이 들고 있다. 줄 안에도 그리면 두 개가 된다.
            showCheck={false}
            action={
              <button
                className="icon-button"
                aria-label={`${track.title} 재생 목록에서 빼기`}
                // 확인 대화상자는 뺄 대상을 곡 번호로만 들고 간다. 같은 곡이 두 줄에
                // 담겨 있으면 그것만으로는 누른 줄을 되찾지 못하므로 자리를 함께 짚어 둔다.
                onClick={() => { m.markQueueRemoval(track.uid); onRemove(track); }}
              >
                <X />
              </button>
            }
          />
        </div>
      ))}
    </div>
  );
}
