/**
 * 앱 셸의 뒤로가기 이벤트를 오버레이 스택에 연결한다 (S15P21A204-410).
 *
 * ## 규칙
 *
 *     closeTopOverlay() 가 무언가를 닫았으면 → 끝
 *     아니면                                → 히스토리 뒤로가기
 *
 * ## 리스너를 **동적으로** 붙였다 뗐다 하는 이유
 *
 * Tauri 의 `AppPlugin`(안드로이드)은 `back-button` 리스너의 **유무**로 갈린다.
 *
 *     리스너 없음 → webView.canGoBack() ? goBack() : activity.onBackPressed()
 *     리스너 있음 → 웹으로 이벤트를 던진다
 *
 * 그래서 열려 있는 오버레이가 없을 때 리스너를 달아 두면, 웹이 앱을 닫아 줄
 * 방법이 없어(그 통로는 ACL 로 막혀 있다 — `lib/overlay-stack.ts` 주석 참조)
 * **히스토리 바닥에서 뒤로가기가 아무 일도 하지 않는다.** 반대로 열려 있는
 * 동안에만 달아 두면 그 둘 다 네이티브 기본 동작 그대로 남는다:
 *
 * - 모달 없이 뒤로가기 → 이전 화면 (웹뷰 히스토리)
 * - 히스토리 바닥에서 뒤로가기 → 앱 종료
 *
 * `handleBackNavigation`(Wry 의 자체 콜백)은 **false 그대로 둔다.** true 로
 * 되돌리면 Wry 가 먼저 `goBack()` 을 해 버려 방금 연 모달이 다시 씹힌다.
 *
 * ## 왜 순수 함수인가
 *
 * React 없이 시험하려고 어댑터를 밖에서 받는다(`frontend/tests/back-button.test.mjs`).
 * 붙였다 떼는 순서와 "무엇을 먼저 닫는가" 는 실기기 없이도 확인할 수 있어야 한다.
 */
import {
  closeTopOverlay,
  overlayDepth,
  subscribeOverlayDepth,
} from './overlay-stack';

export type BackButtonAdapter = {
  /** 네이티브 리스너를 붙인다. 돌려주는 함수가 떼는 함수다. */
  attach: (handler: () => void) => Promise<() => void>;
  /** 웹이 아무것도 닫지 못했을 때. 히스토리 뒤로가기로 내려간다. */
  onUnhandled: () => void;
  /** 붙이지 못했을 때 남길 말. 웹·데스크톱에서는 붙일 것이 없어 실패한다. */
  log?: (message: string) => void;
};

/**
 * 연결한다. 돌려주는 함수를 부르면 끊는다.
 *
 * 붙이는 일은 `invoke` 왕복이라 비동기다. 그 사이에 스택이 바뀔 수 있으므로
 * **붙이고 나서 다시 한 번 깊이를 확인한다** — 기다리는 동안 마지막 모달이
 * 닫혔는데 리스너가 남으면, 그 뒤로가기가 네이티브 기본 경로로 못 내려간다.
 */
export function connectBackButton(adapter: BackButtonAdapter): () => void {
  let detach: (() => void) | null = null;
  let attaching = false;
  let live = true;

  const handle = () => {
    if (closeTopOverlay()) return;
    adapter.onUnhandled();
  };

  const reconcile = () => {
    if (!live) return;
    if (overlayDepth() === 0) {
      detach?.();
      detach = null;
      return;
    }
    if (detach || attaching) return;
    attaching = true;
    adapter
      .attach(handle)
      .then((off) => {
        attaching = false;
        if (!live || overlayDepth() === 0) {
          off();
          return;
        }
        detach = off;
      })
      .catch((error: unknown) => {
        attaching = false;
        adapter.log?.(
          `뒤로가기 리스너를 등록하지 못했다: ${
            error instanceof Error ? error.message : String(error)
          }`,
        );
      });
  };

  const unsubscribe = subscribeOverlayDepth(reconcile);
  reconcile();
  return () => {
    live = false;
    unsubscribe();
    detach?.();
    detach = null;
  };
}
