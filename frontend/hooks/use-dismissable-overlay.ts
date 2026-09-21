'use client';
import { useEffect, useRef } from 'react';
import { registerOverlay } from '@/lib/overlay-stack';

/**
 * 열려 있는 동안 "내가 열려 있다"고 스택에 등록한다 (S15P21A204-410).
 *
 * 안드로이드 소프트버튼이 **최상단 오버레이를 닫게** 하는 배선이다. 쓰는 쪽은
 * 한 줄만 더한다:
 *
 *     const [open, setOpen] = useState(false);
 *     useDismissableOverlay(open, () => setOpen(false));
 *
 * ## 왜 additive 인가
 *
 * 열림 상태는 **그대로 각자의 `useState` 에 둔다.** 상태를 여기로 옮기거나
 * URL·히스토리로 빼면 30개가 넘는 오버레이를 한꺼번에 손대게 되고, Escape·
 * 바깥클릭 핸들러와 어긋날 위험이 커진다. 여기서는 "무엇이 열려 있는가"만
 * 받아 적는다.
 *
 * ## 닫기 함수는 최신 것을 쓴다
 *
 * 등록은 열림 전이에만 일어나므로, 그때 잡은 함수가 오래된 클로저일 수 있다
 * (예: 이름을 바꾸는 대상 id 가 바뀐 뒤). ref 로 최신 것을 들고 있는다.
 */
export function useDismissableOverlay(open: boolean, close: () => void): void {
  const latest = useRef(close);
  // 그릴 때가 아니라 커밋 뒤에 갱신한다 — 등록 효과보다 먼저 선언해 두어야
  // 같은 커밋에서 최신 함수를 들고 등록한다.
  useEffect(() => { latest.current = close; });

  useEffect(() => {
    if (!open) return;
    return registerOverlay(() => latest.current());
  }, [open]);
}
