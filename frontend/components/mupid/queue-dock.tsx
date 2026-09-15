'use client';
import { useEffect, useRef, useState } from 'react';
import { ChevronDown, ListMusic } from 'lucide-react';
import { QueueList } from './queue-list';
import { SelectionToolbar, useTrackSelection } from './track-selection';
import { EmptyState } from './primitives';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { QUEUE_LIMIT, type MupidModel } from '@/hooks/use-mupid';

/**
 * 재생 목록 독.
 *
 * 예전에는 보관함 안의 한 탭이라 **재생 목록을 보려면 듣던 화면을 떠나야 했다.**
 * 곡을 고르다 목록을 확인하면 고르던 자리를 잃었다. 독은 지금 화면 위로 열린다.
 *
 * 화면 너비에 따라 성격이 갈린다. 넓은 화면에서는 미니 플레이어 위에 붙어
 * 올라오는 패널이고, 좁은 화면에서는 아래에서 올라오는 시트다. 그 차이는 전부
 * CSS(`app/queue-dock.css`)에 있고 여기서는 같은 마크업 하나만 그린다 —
 * 두 벌로 나누면 목록 상태(선택·순서 바꾸기)가 둘로 갈라진다.
 */
export function QueueDock({ model: m }: { model: MupidModel }) {
  /*
   * 접혀 있으면 **그리지 않는다.** 화면 밖으로 밀어 두기만 하면 보이지 않는
   * 버튼들이 탭 순서에 남아 초점이 사라진 것처럼 보인다.
   *
   * 덤으로 목록 상태(고르던 곡·확인 대화상자)가 닫을 때 함께 사라진다. 다시
   * 열었을 때 예전 선택이 남아 있으면 무엇을 고른 상태인지 알 수 없다.
   */
  if (!m.queueOpen) return null;
  return <QueueDockPanel model={m} />;
}

function QueueDockPanel({ model: m }: { model: MupidModel }) {
  const closeRef = useRef<HTMLButtonElement>(null);
  const pick = useTrackSelection(m.queueTracks.map((t) => t.id));
  // 곡을 빼는 건 한 곡이든 여러 곡이든 똑같이 한 번 확인받는다.
  const [confirmRemove, setConfirmRemove] = useState<number[] | null>(null);
  const removeTargets = m.queueTracks.filter((t) => confirmRemove?.includes(t.id));

  // 열릴 때 닫기 버튼으로 초점을 옮긴다. 키보드로 연 사람이 독 안에서 이어가야 한다.
  useEffect(() => {
    closeRef.current?.focus();
  }, []);

  useEffect(() => {
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      // 확인 대화상자가 떠 있으면 그쪽이 먼저 닫혀야 한다.
      if (confirmRemove !== null) return;
      m.setQueueOpen(false);
    };
    document.addEventListener('keydown', escape);
    return () => document.removeEventListener('keydown', escape);
  }, [confirmRemove, m]);

  const count = m.queueTracks.length;

  return (
    <>
      {/*
        좁은 화면에서만 보이는 막. 시트 밖을 눌러 닫는 길을 준다.
        넓은 화면에서는 독이 화면을 가리지 않으므로 막도 두지 않는다 —
        목록을 열어 둔 채 다른 곳을 눌러 계속 쓸 수 있어야 한다.
      */}
      <button
        className="queue-dock-scrim"
        tabIndex={-1}
        aria-hidden="true"
        onClick={() => m.setQueueOpen(false)}
      />
      <section id="queue-dock" className="queue-dock is-open" aria-label="재생 목록">
        <header className="queue-dock-head">
          <h2>
            <ListMusic aria-hidden="true" />
            재생 목록
          </h2>
          <span className="queue-dock-count" aria-live="polite">
            {count}/{QUEUE_LIMIT}곡
          </span>
          <button
            ref={closeRef}
            className="icon-button"
            aria-label="재생 목록 접기"
            onClick={() => m.setQueueOpen(false)}
          >
            <ChevronDown />
          </button>
        </header>

        <div className="queue-dock-body">
          <p className="queue-dock-hint">
            {pick.active
              ? '뺄 곡을 고르세요. 전체 선택으로 한 번에 비울 수도 있어요.'
              : `손잡이를 끌어 순서를 바꿀 수 있어요. ${QUEUE_LIMIT}곡이 넘으면 맨 아래 곡부터 빠져요.`}
          </p>

          {!!count && (
            <SelectionToolbar
              selection={pick}
              total={count}
              limit={QUEUE_LIMIT}
              onDelete={() => setConfirmRemove(pick.picked)}
            />
          )}

          <QueueList
            model={m}
            selection={pick}
            onRemove={(track) => setConfirmRemove([track.id])}
          />

          {!count && (
            <EmptyState
              title="재생 목록이 비어 있어요"
              description="음악을 재생하면 이어 들을 곡이 여기에 쌓여요."
            >
              <button
                className="secondary-button"
                onClick={() => {
                  m.setQueueOpen(false);
                  m.go('player');
                }}
              >
                음악 들으러 가기
              </button>
            </EmptyState>
          )}
        </div>
      </section>

      <AlertDialog
        open={confirmRemove !== null}
        onOpenChange={(open) => { if (!open) setConfirmRemove(null); }}
      >
        <AlertDialogContent className="sky-dialog">
          <AlertDialogHeader>
            <AlertDialogTitle>
              {removeTargets.length === 1
                ? `‘${removeTargets[0].title}’ 곡을 뺄까요?`
                : `고른 ${removeTargets.length}곡을 뺄까요?`}
            </AlertDialogTitle>
            <AlertDialogDescription>
              재생 목록에서만 빠져요. 지금 듣고 있는 곡은 계속 재생돼요.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                const gone = confirmRemove ?? [];
                if (removeTargets.length === 1) m.removeFromQueue(removeTargets[0]);
                else m.removeManyFromQueue(gone);
                pick.cancel();
                setConfirmRemove(null);
              }}
            >
              빼기
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
