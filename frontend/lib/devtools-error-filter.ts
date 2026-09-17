/** Temporary workaround for Chrome DevTools' injected web-vitals bug.
 * https://github.com/GoogleChrome/web-vitals/issues/792
 * Only suppress the reported anonymous-script signature, never app errors.
 */
export function filterDevToolsStartTimeError(event: ErrorEvent): void {
  const error = event.error;
  if (!(error instanceof TypeError) ||
      error.message !== "Cannot read properties of undefined (reading 'startTime')") return;
  if (event.filename && !/^(?:VM\d+|<anonymous>)$/.test(event.filename)) return;
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
