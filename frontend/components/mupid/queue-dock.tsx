'use client';
import { useEffect, useRef, useState } from 'react';
import { useDismissableOverlay } from '@/hooks/use-dismissable-overlay';
import { Check, ChevronDown, ListMusic, ListEnd } from 'lucide-react';
import { QueueList } from './queue-list';
import { useTrackSelection } from './track-selection';
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
  /** 담을 위치 고르기가 펼쳐져 있는가. 머리글 버튼 아래로 열린다. */
  const [positionOpen, setPositionOpen] = useState(false);
  /*
   * 뒤로가기 순서 (S15P21A204-410). 확인 대화상자 → 위치 고르기 → 독 순으로
   * 닫힌다. Escape 핸들러가 쓰던 우선순위와 같은 순서이고, 등록 순서가 곧
   * "가장 위" 순서다(나중에 등록한 것이 먼저 닫힌다).
   *
   * 독 자체(열려 있는 동안)는 `hooks/use-mupid.ts` 가 같은 상태로 등록한다 —
   * 여기서 또 등록하면 같은 것이 스택에 두 번 오른다. 그래서 여기서는 독
   * **위에** 겹치는 둘만 맡는다.
   */
  useDismissableOverlay(confirmRemove !== null, () => setConfirmRemove(null));
  useDismissableOverlay(positionOpen, () => setPositionOpen(false));
  const positionRef = useRef<HTMLDivElement>(null);
  const removeTargets = m.queueTracks.filter((t) => confirmRemove?.includes(t.id));

  // 열릴 때 닫기 버튼으로 초점을 옮긴다. 키보드로 연 사람이 독 안에서 이어가야 한다.
  useEffect(() => {
    if (!open) return;
    const opener = document.activeElement;
    closeRef.current?.focus();
    return () => {
      if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
    };
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      // 확인 대화상자가 떠 있으면 그쪽이 먼저 닫혀야 한다.
      if (confirmRemove !== null) return;
      // 담을 위치를 펼쳐 둔 상태면 그것부터 접는다. 한 번에 둘을 닫으면
      // 목록을 계속 보려던 사람의 독까지 사라진다.
      if (positionOpen) {
        setPositionOpen(false);
        return;
      }
      m.setQueueOpen(false);
    };
    document.addEventListener('keydown', escape);
    return () => document.removeEventListener('keydown', escape);
  }, [open, confirmRemove, positionOpen, m]);

  // 바깥을 누르면 접는다. 목록을 누르려다 열어 둔 것을 그냥 두면 가린다.
  useEffect(() => {
    if (!positionOpen) return;
    const outside = (event: PointerEvent) => {
      if (!positionRef.current?.contains(event.target as Node)) setPositionOpen(false);
    };
    document.addEventListener('pointerdown', outside);
    return () => document.removeEventListener('pointerdown', outside);
  }, [positionOpen]);

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
        aria-label="재생 목록 닫기"
        inert={!open}
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
            {pick.active ? `${pick.picked.length}곡 선택` : `${count}/${QUEUE_LIMIT}곡`}
          </span>

          {/*
            버튼은 한 묶음으로 오른쪽에 붙인다. 곡 수는 제목 옆에 두어 "무엇을
            몇 곡" 이 한눈에 이어지게 하고, 누를 것들은 반대쪽에 모은다.

            **고르기 도구도 여기서 바뀐다.** 목록 위에 도구 줄을 끼워 넣으면 '선택'
            을 누르는 순간 목록 전체가 그 높이만큼 아래로 밀린다. 머리글 안에서
            버튼만 갈아 끼우면 목록의 시작 위치가 그대로다.
          */}
          <div className="queue-head-actions">
            {pick.active ? (
              <>
                <label className="queue-head-check">
                  <input
                    type="checkbox"
                    checked={pick.allPicked}
                    onChange={pick.toggleAll}
                  />
                  전체
                </label>
                <button
                  className="queue-head-action is-danger"
                  disabled={!pick.picked.length}
                  onClick={() => setConfirmRemove(pick.picked)}
                >
                  빼기
                </button>
                <button className="queue-head-action" onClick={pick.cancel}>
                  취소
                </button>
              </>
            ) : (
              <button
                className="queue-head-action"
                disabled={!count}
                onClick={pick.start}
              >
                선택
              </button>
            )}

          {/*
            담을 위치는 머리글의 버튼으로 접어 둔다. 라디오 세 개를 목록 위에
            펼쳐 두었더니 정작 보러 온 목록이 밀려났다 — 자주 바꾸는 값이 아니라
            평소에는 지금 값만 보이면 된다.

            고르는 중에는 감춘다. 곡을 빼려는 참에 "어디에 담을지" 는 쓸 일이 없고,
            머리글 한 줄에 버튼이 넷이면 좁은 화면에서 넘친다.
          */}
          <div
            className="queue-insert-position"
            ref={positionRef}
            hidden={pick.active}
          >
            <button
              className="queue-insert-toggle"
              aria-haspopup="menu"
              aria-expanded={positionOpen}
              aria-label={`담을 위치: ${INSERT_POSITION_LABEL[m.queueInsertPosition]}. 바꾸기`}
              onClick={() => setPositionOpen(value => !value)}
            >
              <ListEnd aria-hidden="true" />
              <span>{INSERT_POSITION_LABEL[m.queueInsertPosition]}</span>
            </button>
            {positionOpen && (
              <div className="queue-insert-menu" role="menu">
                <p className="queue-insert-menu-title">담을 위치</p>
                {QUEUE_INSERT_CHOICES.map(({ value, label }) => (
                  <button
                    key={value}
                    role="menuitemradio"
                    aria-checked={m.queueInsertPosition === value}
                    onClick={() => {
                      m.setQueueInsertPosition(value);
                      setPositionOpen(false);
                    }}
                  >
                    <Check aria-hidden="true" />
                    {label}
                  </button>
                ))}
              </div>
            )}
            </div>

            <button
              ref={closeRef}
              className="icon-button"
              aria-label="재생 목록 접기"
              onClick={() => m.setQueueOpen(false)}
            >
              <ChevronDown />
            </button>
          </div>
        </header>

        {/*
          본문에는 목록만 둔다. 안내 문구도 도구 줄도 없다 — 무엇이든 목록 위에
          끼워 넣으면 그것이 나타나고 사라질 때마다 목록이 위아래로 밀린다.
          고르기에 필요한 것은 전부 머리글에 있다.
        */}
        <div className="queue-dock-body">

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
