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
import {
  INSERT_POSITION_LABEL,
  QUEUE_LIMIT,
  type MupidModel,
  type QueueInsertPosition,
} from '@/hooks/use-mupid';

/**
 * 고를 수 있는 담기 위치. 기본값인 '맨 아래' 를 먼저 둔다.
 *
 * '재생 중인 곡 다음' 은 듣고 있는 곡이 없으면 맨 아래로 내려간다 — 그때는
 * 모델이 토스트로 알린다. 고를 수 없게 막지는 않는다. 곧 곡을 틀 사람에게는
 * 미리 골라 두는 것이 자연스럽다.
 */
const QUEUE_INSERT_CHOICES: { value: QueueInsertPosition; label: string }[] = [
  { value: 'bottom', label: INSERT_POSITION_LABEL.bottom },
  { value: 'top', label: INSERT_POSITION_LABEL.top },
  { value: 'afterCurrent', label: INSERT_POSITION_LABEL.afterCurrent },
];

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
   * 접힌 뒤에는 **그리지 않는다.** 화면 밖으로 밀어 두기만 하면 보이지 않는
   * 버튼들이 탭 순서에 남아 초점이 사라진 것처럼 보인다.
   *
   * 덤으로 목록 상태(고르던 곡·확인 대화상자)가 닫을 때 함께 사라진다. 다시
   * 열었을 때 예전 선택이 남아 있으면 무엇을 고른 상태인지 알 수 없다.
   *
   * 다만 **닫는 움직임이 끝나기를 기다렸다가** 지운다. 펼 때만 움직이고 접을 때
   * 즉시 사라지면 같은 동작인데 한쪽만 뚝 끊긴다.
   */
  const [rendered, setRendered] = useState(m.queueOpen);

  // 효과가 아니라 렌더 중에 맞춘다. 효과로 하면 한 번 그린 뒤에 다시 그리게 되고,
  // 그 사이 한 프레임 동안 독이 없는 화면이 나가 여는 움직임이 처음부터 시작하지 않는다.
  if (m.queueOpen && !rendered) setRendered(true);

  if (!rendered) return null;
  return (
    <QueueDockPanel
      model={m}
      open={m.queueOpen}
      onClosed={() => setRendered(false)}
    />
  );
}

function QueueDockPanel({
  model: m,
  open,
  onClosed,
}: {
  model: MupidModel;
  open: boolean;
  onClosed: () => void;
}) {
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
    if (!open) return;
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      // 확인 대화상자가 떠 있으면 그쪽이 먼저 닫혀야 한다.
      if (confirmRemove !== null) return;
      m.setQueueOpen(false);
    };
    document.addEventListener('keydown', escape);
    return () => document.removeEventListener('keydown', escape);
  }, [open, confirmRemove, m]);

  /*
   * 닫는 움직임이 끝나면 지운다.
   *
   * 여는 애니메이션도 같은 사건을 내므로 **닫는 중일 때만** 본다. 움직임을 줄여
   * 달라고 한 사람에게도 이 사건은 나게 두었다(길이만 1ms) — 아예 없애면
   * 독이 닫히지 않는다.
   */
  const onAnimationEnd = () => {
    if (!open) onClosed();
  };

  const count = m.queueTracks.length;

  return (
    <>
      {/*
        좁은 화면에서만 보이는 막. 시트 밖을 눌러 닫는 길을 준다.
        넓은 화면에서는 독이 화면을 가리지 않으므로 막도 두지 않는다 —
        목록을 열어 둔 채 다른 곳을 눌러 계속 쓸 수 있어야 한다.
      */}
      <button
        className={`queue-dock-scrim ${open ? '' : 'is-closing'}`}
        tabIndex={-1}
        aria-hidden="true"
        onClick={() => m.setQueueOpen(false)}
      />
      <section
        id="queue-dock"
        className={`queue-dock ${open ? 'is-open' : 'is-closing'}`}
        aria-label="재생 목록"
        onAnimationEnd={onAnimationEnd}
        // 닫히는 동안에는 안의 버튼을 누를 수도, 탭으로 짚을 수도 없게 한다.
        // 사라지는 중인 목록에서 곡을 빼는 일이 생기면 안 된다.
        inert={!open}
      >
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
              : `손잡이를 끌어 순서를 바꿀 수 있어요. ${QUEUE_LIMIT}곡이 넘으면 가장 오래 담긴 곡부터 빠져요.`}
          </p>

          {/*
            담은 곡이 어디로 들어갈지 고른다. 목록 바로 위에 두는 것은,
            담기 버튼이 흩어져 있어(곡 목록·플레이어·추천) 그 옆마다 붙일 수 없고
            결과가 보이는 자리에서 고르는 편이 무엇이 달라지는지 읽히기 때문이다.
          */}
          {!pick.active && (
            <fieldset className="queue-insert-position">
              <legend>담을 위치</legend>
              {QUEUE_INSERT_CHOICES.map(({ value, label }) => (
                <label key={value} className={m.queueInsertPosition === value ? 'is-picked' : ''}>
                  <input
                    type="radio"
                    name="queue-insert-position"
                    value={value}
                    checked={m.queueInsertPosition === value}
                    onChange={() => m.setQueueInsertPosition(value)}
                  />
                  {label}
                </label>
              ))}
            </fieldset>
          )}

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
