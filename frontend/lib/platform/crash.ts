/**
 * 화면(JS)에서 난 예외를 앱 셸(Rust)로 넘긴다.
 *
 * 티켓 S15P21A204-383 — *"Tauri Rust 예외는 물론 프론트엔드(JS) 예외도 기록한다."*
 * 이 파일이 그 **JS 쪽 입구**다. Rust 쪽은 `app/src/crash.rs` 가 맡는다.
 *
 * ## 왜 웹뷰가 직접 보내지 않는가
 *
 * 이 앱은 **웹뷰가 인터넷에 직접 나가지 않는다.** CSP 의 `connect-src` 가
 * `'self' ipc: http://ipc.localhost` 뿐이라(`app/tauri.conf.json`) 웹뷰에서
 * 수집기로 나가는 요청 자체가 차단된다. 그래서 화면은 예외를
 * `crash_report` 커맨드로 넘기고, 실제 전송은 Rust 가 한다 — HTTP 를 Rust 로
 * 옮긴 이유와 같다(`app/src/http.rs`).
 *
 * 웹 배포에서는 이 파일이 **아무 일도 하지 않는다**. `isTauri()` 가 거짓이라
 * `invoke` 를 부르지 않고, 보낼 곳도 없다. 그래서 웹 화면 코드는 이 존재를
 * 몰라도 된다.
 *
 * ## 이 파일은 예외를 절대 밖으로 던지지 않는다
 *
 * 예외를 처리하는 코드가 예외를 던지면, 그 예외가 다시 여기로 들어와
 * **무한 고리**가 된다. 그래서
 *
 * - `reportCrash` 는 `void` 다 — 약속을 돌려주지 않으므로 아무도
 *   `await` 하다 실패할 수 없다. 안에서 `.catch` 로 삼킨다.
 * - 핸들러를 거는 일 자체도 실패할 수 있는 지점마다 막아 둔다.
 * - **한 세션에 넘기는 건수를 제한한다**(`SESSION_LIMIT`). 렌더 루프에서
 *   매 프레임 예외가 나면 그대로 IPC 폭주가 된다. 상한을 넘으면 조용히
 *   버린다 — 마지막 한 건 때문에 화면이 멈추는 것보다 낫다.
 *
 * ## 무엇을 잡는가
 *
 * - `error` — 잡히지 않은 동기 예외 (`window.onerror` 와 같은 것)
 * - `unhandledrejection` — 처리되지 않은 `Promise` 거부
 *
 * 둘 다 **잡기만 하고 막지 않는다.** `preventDefault()` 를 부르지 않으므로
 * 개발자 도구의 경고는 그대로 남는다 — 기록과 디버깅은 다른 일이다.
 */

import { invoke, isTauri } from './bridge';

/**
 * 한 세션에 넘기는 최대 건수.
 *
 * 예외 폭주를 IPC 폭주로 바꾸지 않기 위한 상한이다. Rust 쪽 큐 상한
 * (`crash.rs` 의 `QUEUE_LIMIT`)과 별개로 여기서 먼저 막는다 — 넘기지 않는
 * 편이 IPC 비용을 아예 쓰지 않으므로 더 싸다.
 */
const SESSION_LIMIT = 50;

/** 본문 길이 상한. Rust 쪽 상한보다 **작다** — IPC 로 실어 보내는 값이다. */
const MESSAGE_LIMIT = 2000;
const STACK_LIMIT = 4000;

export type CrashPayload = {
  message: string;
  stack?: string;
  context?: string;
  atMs?: number;
};

/** 길면 자른다. Rust 쪽도 다시 자르지만, 안 보낼 것을 만들지 않는 편이 싸다. */
const cut = (text: string, limit: number): string =>
  text.length <= limit ? text : `${text.slice(0, limit)}…`;

/**
 * 지금 보고 있는 화면의 경로.
 *
 * 예외가 어느 화면에서 났는지가 진단의 절반이다. 스택만으로는 "재생 중" 인지
 * "보고서 화면" 인지 알 수 없다. 실패해도 빈 문자열이다.
 */
export function currentRoute(): string {
  if (typeof window === 'undefined') return '';
  try {
    return window.location?.pathname ?? '';
  } catch {
    return '';
  }
}

/**
 * 무엇이든 사람이 읽을 수 있는 한 줄로 바꾼다.
 *
 * `Promise` 가 문자열·숫자·객체 무엇으로든 거부될 수 있다. `JSON.stringify`
 * 는 순환 참조에서 던지므로 막아 둔다 — 여기서 던지면 위의 무한 고리다.
 */
export function describe(value: unknown): string {
  if (typeof value === 'string') return value;
  if (value === null || value === undefined) return String(value);
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  try {
    const encoded = JSON.stringify(value);
    if (typeof encoded === 'string') return encoded;
  } catch {
    // 순환 참조. 아래로 내려간다.
  }
  return Object.prototype.toString.call(value);
}

/**
 * 예외 하나를 셸로 넘긴다.
 *
 * **돌려주는 것이 없다.** 부르는 쪽이 실패를 기다릴 수 없게 만들어, 보고
 * 실패가 화면 동작에 끼어들 여지를 없앤다.
 *
 * 앱이 아니거나(웹 배포) 메시지가 비어 있으면 아무 일도 하지 않는다.
 */
export function reportCrash(payload: CrashPayload): void {
  if (!isTauri()) return;

  const message = cut((payload.message ?? '').trim(), MESSAGE_LIMIT);
  // 빈 메시지는 보고로 가치가 없다. Rust 쪽에서도 걸러지지만 여기서 끊는다.
  if (!message) return;

  void invoke('crash_report', {
    value: {
      message,
      stack: payload.stack ? cut(payload.stack, STACK_LIMIT) : undefined,
      context: payload.context ?? currentRoute(),
      atMs: payload.atMs ?? Date.now(),
    },
  }).catch(() => {
    // **삼킨다.** 키가 없거나 오프라인이거나 앱이 곧 끝날 수 있다. 이 앱은
    // 오프라인에서도 재생되어야 하므로 보내지 못한 예외 하나가 화면을
    // 방해해서는 안 된다. 재시도도 하지 않는다.
  });
}

/**
 * 전역 예외 처리기를 건다. 돌려주는 함수를 부르면 끊는다.
 *
 * 가장 바깥(`components/mupid-app.tsx`)에서 한 번만 건다 — 여러 번 걸면
 * 같은 예외가 두 번 보고된다.
 *
 * 앱이 아니어도 **구독은 걸어 둔다.** 웹뷰가 통로를 심는 시점과 화면이
 * 뜨는 시점의 순서를 보장할 수 없어서, 미리 걸어 두고 `isTauri()` 판단은
 * 실제로 예외가 났을 때 한다(`watchSafeArea` 와 같은 판단).
 */
export function watchCrashReports(): () => void {
  if (typeof window === 'undefined') return () => {};

  let left = SESSION_LIMIT;
  const take = (): boolean => {
    if (left <= 0) return false;
    left -= 1;
    return true;
  };

  const onError = (event: ErrorEvent) => {
    if (!take()) return;
    const thrown = event.error as { stack?: string; message?: string } | undefined;
    // `event.timeStamp` 는 **페이지 로드 기준** 상대 시각이라 epoch ms 가
    // 아니다. 그대로 넘기면 대시보드의 시간축이 조용히 어긋난다.
    reportCrash({
      message: event.message || thrown?.message || '알 수 없는 오류',
      stack:
        thrown?.stack ??
        (event.filename
          ? `${event.filename}:${event.lineno ?? 0}:${event.colno ?? 0}`
          : undefined),
      context: currentRoute(),
      atMs: Date.now(),
    });
  };

  const onRejection = (event: PromiseRejectionEvent) => {
    if (!take()) return;
    const reason = event.reason;
    const thrown = reason instanceof Error ? reason : undefined;
    reportCrash({
      message: thrown?.message || describe(reason),
      stack: thrown?.stack,
      context: currentRoute(),
      atMs: Date.now(),
    });
  };

  window.addEventListener('error', onError);
  window.addEventListener('unhandledrejection', onRejection);
  return () => {
    window.removeEventListener('error', onError);
    window.removeEventListener('unhandledrejection', onRejection);
  };
}

/** 시험에서 세션 상한을 확인할 때 쓴다. 프로덕션 경로는 쓰지 않는다. */
export const CRASH_SESSION_LIMIT = SESSION_LIMIT;
export const CRASH_MESSAGE_LIMIT = MESSAGE_LIMIT;
export const CRASH_STACK_LIMIT = STACK_LIMIT;
