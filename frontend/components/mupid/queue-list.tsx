'use client';
import { useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { GripVertical, X } from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
import type { TrackSelection } from './track-selection';
import { TrackRow } from './primitives';

// 재생 목록은 순서가 곧 다음에 들을 곡이라 끌어서 바꿀 수 있어야 한다.
// 포인터 이벤트 하나로 마우스와 터치를 함께 처리하고, 키보드는 위/아래 화살표로 옮긴다.
export function QueueList({
  model: m,
  selection,
  onRemove,
}: {
  model: MupidModel;
  selection: TrackSelection;
  onRemove: (track: (typeof m.queueTracks)[number]) => void;
}) {
  const listRef = useRef<HTMLDivElement>(null);
  const [dragId, setDragId] = useState<number | null>(null);
  const [liveIds, setLiveIds] = useState<number[] | null>(null);

  const baseIds = m.queueTracks.map((t) => t.id);
  const ids = liveIds ?? baseIds;
  const tracks = ids
    .map((id) => m.queueTracks.find((t) => t.id === id))
    .filter((t): t is (typeof m.queueTracks)[number] => !!t);

  const move = (id: number, to: number) => {
    const from = baseIds.indexOf(id);
    const target = Math.max(0, Math.min(baseIds.length - 1, to));
    if (from < 0 || from === target) return;
    const next = baseIds.slice();
    next.splice(from, 1);
    next.splice(target, 0, id);
    m.setQueueOrder(next);
    m.setToast(`${target + 1}번째로 옮겼어요.`);
  };

  const onPointerDown = (id: number) => (event: ReactPointerEvent<HTMLButtonElement>) => {
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    setDragId(id);
    setLiveIds(baseIds);
  };

  const onPointerMove = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (dragId === null || !liveIds) return;
    const rows = [...(listRef.current?.querySelectorAll('[data-queue-id]') ?? [])];
    const from = liveIds.indexOf(dragId);
    let to = from;
    rows.forEach((row, index) => {
      const box = row.getBoundingClientRect();
      if (event.clientY >= box.top && event.clientY <= box.bottom) to = index;
    });
    if (to === from) return;
    const next = liveIds.slice();
    next.splice(from, 1);
    next.splice(to, 0, dragId);
    setLiveIds(next);
  };

  const onPointerUp = () => {
    if (dragId !== null && liveIds) {
      const to = liveIds.indexOf(dragId);
      setDragId(null);
      setLiveIds(null);
      move(dragId, to);
      return;
    }
    setDragId(null);
    setLiveIds(null);
  };

  // 고르는 중에는 손잡이를 접어 둔다. 순서 바꾸기와 섞이면 헷갈린다.
  const picking = selection.active;

  return (
    <div className={`track-list queue-list ${picking ? 'is-picking' : ''}`} ref={listRef}>
      {tracks.map((track, index) => (
        <div
          key={track.id}
          data-queue-id={track.id}
          className={`queue-item ${dragId === track.id ? 'is-dragging' : ''}`}
        >
          {!picking && (
            <button
              className="queue-handle"
              aria-label={`${track.title} 순서 바꾸기. ${index + 1}번째, 위아래 화살표로 이동`}
              onPointerDown={onPointerDown(track.id)}
              onPointerMove={onPointerMove}
              onPointerUp={onPointerUp}
              onPointerCancel={onPointerUp}
              onKeyDown={(event) => {
                if (event.key === 'ArrowUp') { event.preventDefault(); move(track.id, index - 1); }
                if (event.key === 'ArrowDown') { event.preventDefault(); move(track.id, index + 1); }
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
            action={
              <button
                className="icon-button"
                aria-label={`${track.title} 재생 목록에서 빼기`}
                onClick={() => onRemove(track)}
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
