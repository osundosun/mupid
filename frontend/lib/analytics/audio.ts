import { HEARTBEAT_MS, type Tracker } from './tracker';

/**
 * `<audio>` 하나에 붙어 사용자의 의도가 개입하지 않는 이벤트를 자동으로 남긴다.
 *
 *   자동   PLAY_RESUME · PLAY_PAUSE · PLAY_END · PLAY_STALL · PLAY_ERROR · PLAY_PROGRESS
 *   수동   PLAY_START · TRACK_SKIP · SEEK_* · LIKE 계열 · SECTION_* · PLAYLIST_* · COMMENT_* · SEARCH
 *
 * 수동 쪽은 오디오 엘리먼트만 봐서는 판단할 수 없는 것들이다. 예를 들어 `seeking` 은
 * 사용자가 슬라이더를 옮겼을 때와 앱이 `currentTime` 을 대입했을 때 똑같이 발생하고,
 * 스킵은 무엇을 눌러 넘겼는지(`skip_trigger`)를 엘리먼트가 알 수 없다.
 */
const MEDIA_ERROR: Record<number, string> = {
  1: 'MEDIA_ERR_ABORTED',
  2: 'MEDIA_ERR_NETWORK',
  3: 'MEDIA_ERR_DECODE',
  4: 'MEDIA_ERR_SRC_NOT_SUPPORTED',
};

export function bindAudio(audio: HTMLAudioElement, tracker: Tracker): () => void {
  // audio.currentTime 은 초 단위 실수다. 이벤트는 전부 밀리초로 나간다.
  const positionMs = () => Math.max(0, Math.round(audio.currentTime * 1000));
  let paused = false;

  const onPlaying = () => {
    if (!paused) return; // PLAY_START 직후의 첫 재생은 재개가 아니다.
    paused = false;
    tracker.resume(positionMs());
  };
  const onPause = () => {
    if (audio.ended || paused) return; // 자연 종료는 PLAY_END 가 담당한다.
    paused = true;
    tracker.pause(positionMs());
  };
  const onEnded = () => {
    paused = false;
    tracker.endPlay(positionMs());
  };
  const onWaiting = () => tracker.stall(positionMs());
  const onError = () =>
    tracker.error(positionMs(), MEDIA_ERROR[audio.error?.code ?? 0] ?? 'UNKNOWN');

  // 하트비트는 재생 중일 때만 돈다. 백그라운드 탭에서 타이머가 늘어져도, 매번
  // currentTime 을 실측하고 서버는 [from_ms, position_ms) 구간을 받으므로 값이 정확하다.
  const beat = setInterval(() => {
    if (!audio.paused && !audio.ended) tracker.heartbeat(positionMs());
  }, HEARTBEAT_MS);

  // 탭이 가려지면 아직 안 보낸 것을 밀어낸다. 다시 보일 때까지 큐에 남겨두면
  // 그대로 앱이 닫히면서 통째로 사라진다.
  const onVisibility = () => {
    if (document.visibilityState === 'hidden') void tracker.flush(true);
  };
  // 페이지가 사라진다. 재생 중이었다면 의도한 스킵이 아니라 끊긴 것이다.
  const onPageHide = () => {
    if (tracker.playId && !audio.ended) tracker.abandonPlay(positionMs());
    void tracker.flush(true);
  };

  audio.addEventListener('playing', onPlaying);
  audio.addEventListener('pause', onPause);
  audio.addEventListener('ended', onEnded);
  audio.addEventListener('waiting', onWaiting);
  audio.addEventListener('error', onError);
  document.addEventListener('visibilitychange', onVisibility);
  window.addEventListener('pagehide', onPageHide);

  return () => {
    clearInterval(beat);
    audio.removeEventListener('playing', onPlaying);
    audio.removeEventListener('pause', onPause);
    audio.removeEventListener('ended', onEnded);
    audio.removeEventListener('waiting', onWaiting);
    audio.removeEventListener('error', onError);
    document.removeEventListener('visibilitychange', onVisibility);
    window.removeEventListener('pagehide', onPageHide);
  };
}
