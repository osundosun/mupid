'use client';
import { useState, type ReactNode } from 'react';
import { Trash2 } from 'lucide-react';

// 목록에서 여러 곡을 한 번에 고르고 지우는 흐름.
// 평소에는 곡 수만 보여 주고, '선택'을 누르면 체크 박스와 전체 선택이 열린다.
export type TrackSelection = ReturnType<typeof useTrackSelection>;

export function useTrackSelection(ids: number[]) {
  const [active, setActive] = useState(false);
  const [marked, setMarked] = useState<number[]>([]);
  // 목록에서 사라진 곡은 선택에서도 빠진다.
  const picked = marked.filter((id) => ids.includes(id));
  const allPicked = ids.length > 0 && picked.length === ids.length;
  return {
    active,
    picked,
    allPicked,
    has: (id: number) => picked.includes(id),
    toggle: (id: number) =>
      setMarked((v) => (v.includes(id) ? v.filter((x) => x !== id) : [...v, id])),
    toggleAll: () => setMarked(allPicked ? [] : ids),
    start: () => { setMarked([]); setActive(true); },
    // 취소하면 고른 것도 함께 지워 처음 상태로 돌아간다.
    cancel: () => { setMarked([]); setActive(false); },
  };
}

export function SelectionToolbar({
  selection,
  total,
  onDelete,
  deleteLabel = '빼기',
  limit,
  children,
}: {
  selection: TrackSelection;
  total: number;
  onDelete: () => void;
  deleteLabel?: string;
  limit?: number;
  children?: ReactNode;
}) {
  return (
    <div className="select-toolbar">
      {selection.active ? (
        <>
          <label className="select-all">
            <input
              type="checkbox"
              className="select-box"
              checked={selection.allPicked}
              onChange={selection.toggleAll}
            />
            전체 선택
          </label>
          <span className="select-count" aria-live="polite">
            {selection.picked.length}곡 선택
          </span>
          <div className="select-actions">
            <button type="button" className="ghost-button" onClick={selection.cancel}>
              취소
            </button>
            <button
              type="button"
              className="danger-button"
              disabled={!selection.picked.length}
              onClick={onDelete}
            >
              <Trash2 />
              {deleteLabel}
            </button>
          </div>
        </>
      ) : (
        <>
          <span className="select-count">
            {total}곡
            {limit ? <em className="select-limit">· 최대 {limit}곡</em> : null}
          </span>
          <div className="select-actions">
            {children}
            <button
              type="button"
              className="ghost-button"
              disabled={!total}
              onClick={selection.start}
            >
              선택
            </button>
          </div>
        </>
      )}
    </div>
  );
}
