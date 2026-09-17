/** Temporary workaround for Chrome DevTools' injected web-vitals bug.
 * https://github.com/GoogleChrome/web-vitals/issues/792
 * Only suppress the reported anonymous-script signature, never app errors.
 */
export function filterDevToolsStartTimeError(event: ErrorEvent): void {
  const error = event.error;
  if (!(error instanceof TypeError) ||
      error.message !== "Cannot read properties of undefined (reading 'startTime')") return;
  /*
   * `filename` 으로는 거르지 않는다.
   *
   * 콘솔에 `VM14033:2` 로 찍히는 값이 `VM\d+` 와 어긋나면 여기서 그냥 빠져나가,
   * 정작 막아야 할 오류를 놓쳤다. 이 값이 어떤 모양으로 오는지는 브라우저와
   * 주입 방식에 달려 있어 기댈 수 없다. 안전은 아래 스택 서명이 맡는다 —
   * 함수 이름과 위치까지 같고 모든 칸이 익명 스크립트인 것만 막으므로 앱에서
   * 난 오류가 걸릴 일은 없다.
   */
  const frames = (error.stack ?? '').split('\n').slice(1).filter(line => line.trim());
  if (!frames.length ||
      !/^\s*at et\.reportAllChanges \(<anonymous>:2:19429\)$/.test(frames[0]) ||
      !frames.every(line => /^\s*at (?:[^()]+ \()?<anonymous>:\d+:\d+\)?$/.test(line))) return;
  event.preventDefault();
}

export function watchDevToolsErrors(): () => void {
  window.addEventListener('error', filterDevToolsStartTimeError);
  return () => window.removeEventListener('error', filterDevToolsStartTimeError);
}
