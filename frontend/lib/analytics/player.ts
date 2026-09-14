import type { Tracker } from './tracker';

/**
 * 재생 하트비트 간격.
 *
 * 하트비트 하나가 `[from_ms, position_ms)` 연속 구간이라 간격을 바꿔도 뜻은 같고
 * 구간 경계 판정이 촘촘해지거나 성겨질 뿐이다. 3초로 당겨 봤다가 5초로 되돌렸다 —
 * 정확도 차이보다 로그가 불어나는 쪽이 먼저 체감됐다. 이벤트량은 간격에 반비례하므로
 * 이 값을 바꿀 때는 Kafka 프로듀서 압축과 ClickHouse `kafka_flush_interval_ms` 를
 * 함께 본다.
 *
 * 타이머를 돌리는 쪽이 이 모듈이라 값도 여기 둔다. 트래커는 "지금 위치" 만 받는다.
 */
export const HEARTBEAT_MS = 5000;

/**
 * 재생기 하나에 붙어 사용자의 의도가 개입하지 않는 이벤트를 자동으로 남긴다.
 *
 *   자동   PLAY_RESUME · PLAY_PAUSE · PLAY_END · PLAY_STALL · PLAY_ERROR · PLAY_PROGRESS
 *   수동   PLAY_START · TRACK_SKIP · SEEK_* · LIKE 계열 · SECTION_* · PLAYLIST_* · COMMENT_* · SEARCH
 *
 * 수동 쪽은 재생기 상태만 봐서는 판단할 수 없는 것들이다. 앱이 건 seek 과 사용자가 건
 * seek 은 엔진에서 똑같은 모양이고, 스킵은 무엇을 눌러 넘겼는지(`skip_trigger`)를
 * 엔진이 알 수 없다.
 *
 * **`<audio>` 가 아니라 `lib/player` 의 청크 스트리밍 엔진에 붙는다.** 이 앱에는
 * 미디어 엘리먼트가 없어서 `HTMLAudioElement` 의 `playing`/`waiting`/`ended` 이벤트를
 * 들을 자리가 없다. 대신 엔진이 들고 있는 상태 스토어의 전이를 같은 의미로 읽는다.
 *
 * 엔진 타입을 import 하지 않고 필요한 모양만 구조적으로 받는다. 이 모듈이
 * `lib/player` 에 묶이면 번들러 없이 `node --test` 로 돌릴 수 없게 된다.
 */
export type PlayerSnapshot = {
  status: 'idle' | 'loading' | 'buffering' | 'playing' | 'paused' | 'ended' | 'error';
  musicId: number | null;
  positionSec: number;
  error: string | null;
};

export type PlayerSource = {
  store: {
    get(): PlayerSnapshot;
    subscribe(listener: () => void): () => void;
  };
};

export function bindPlayer(player: PlayerSource, tracker: Tracker): () => void {
  // 엔진은 초 단위 실수를 들고 있다. 이벤트는 전부 밀리초로 나간다.
  const positionMs = () => Math.max(0, Math.round(player.store.get().positionSec * 1000));

  let previous = player.store.get().status;
  // PLAY_START 직후의 첫 재생은 재개가 아니다. 한 번 멈춘 뒤부터만 PLAY_RESUME 이다.
  let paused = false;
  /** 종료를 보고한 곡. 한 곡의 종료로 PLAY_END 를 두 번 내지 않는다. */
  let endedFor: number | null = null;

  const unsubscribe = player.store.subscribe(() => {
    const state = player.store.get();
    const status = state.status;
    if (status === previous) return;
    const from = previous;
    previous = status;

    if (status === 'playing') {
      endedFor = null;
      if (paused) {
        paused = false;
        tracker.resume(positionMs());
      }
      return;
    }
    if (status === 'paused') {
      // 곡이 끝나 멈춘 것은 PLAY_END 가 담당한다.
      if (from === 'ended') return;
      paused = true;
      tracker.pause(positionMs());
      return;
    }
    if (status === 'buffering') {
      // 재생 중에 버퍼가 떨어진 것만 버퍼링이다. 곡을 새로 걸 때의 buffering 은
      // 아직 한 번도 소리가 나지 않은 상태라 "끊겼다" 가 아니다.
      if (from === 'playing') tracker.stall(positionMs());
      return;
    }
    if (status === 'ended') {
      if (endedFor === state.musicId) return;
      endedFor = state.musicId;
      paused = false;
      tracker.endPlay(positionMs());
      return;
    }
    if (status === 'error') {
      paused = false;
      tracker.error(positionMs(), state.error ?? 'UNKNOWN');
    }
  });

  // 하트비트는 재생 중일 때만 돈다. 백그라운드 탭에서 타이머가 늘어져도, 매번
  // 재생 위치를 실측하고 서버는 [from_ms, position_ms) 구간을 받으므로 값이 정확하다.
  const beat = setInterval(() => {
    if (player.store.get().status === 'playing') tracker.heartbeat(positionMs());
  }, HEARTBEAT_MS);

  // 탭이 가려지면 아직 안 보낸 것을 밀어낸다. 다시 보일 때까지 큐에 남겨두면
  // 그대로 앱이 닫히면서 통째로 사라진다.
  const onVisibility = () => {
    if (document.visibilityState === 'hidden') void tracker.flush(true);
  };
  // 페이지가 사라진다. 재생 중이었다면 의도한 스킵이 아니라 끊긴 것이다.
  const onPageHide = () => {
    if (tracker.playId && player.store.get().status !== 'ended') {
      tracker.abandonPlay(positionMs());
    }
    void tracker.flush(true);
  };

  const canListen = typeof document !== 'undefined';
  if (canListen) {
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('pagehide', onPageHide);
  }

  return () => {
    clearInterval(beat);
    unsubscribe();
    if (canListen) {
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('pagehide', onPageHide);
    }
  };
}
