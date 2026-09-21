'use client';
import { useEffect, useRef } from 'react';
import { connectBackButton } from '@/lib/back-button';
import { onBackButtonPress } from '@/lib/platform/bridge';

/**
 * 앱 셸의 안드로이드 뒤로가기를 오버레이 스택에 잇는다 (S15P21A204-410).
 *
 * **앱 전체에 한 번만** 건다(루트 셸). 오버레이마다 걸면 같은 이벤트에 여러
 * 번 반응해 한 번의 뒤로가기로 모달 두셋이 함께 닫힌다.
 *
 * 웹·데스크톱에서는 `onBackButtonPress` 가 붙이지 않고 빈 정리 함수를
 * 돌려주므로 아무 일도 일어나지 않는다(회귀 없음).
 *
 * @param onUnhandled 웹이 아무것도 닫지 못했을 때. 히스토리 뒤로가기로 내려간다.
 */
export function useBackButtonBridge(onUnhandled: () => void): void {
  // 다시 그려질 때마다 다시 연결하지 않는다 — 연결은 한 번이면 되고, 그때 잡은
  // 함수는 ref 로 최신을 본다.
  const latest = useRef(onUnhandled);
  // 그릴 때가 아니라 커밋 뒤에 갱신한다. 읽는 곳은 뒤로가기 콜백뿐이라
  // 커밋 이후에만 필요하다.
  useEffect(() => { latest.current = onUnhandled; });

  useEffect(
    () =>
      connectBackButton({
        attach: onBackButtonPress,
        onUnhandled: () => latest.current(),
        log: (message) => console.warn(message),
      }),
    [],
  );
}
