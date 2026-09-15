/**
 * 상단 알림·잠금화면 플레이어의 화면 쪽.
 *
 * 소리는 웹뷰가 내므로 안드로이드는 무엇이 울리는지 알지 못한다. 곡과 재생
 * 상태를 여기서 올려보내면 Rust(`app/src/media.rs`)가 `MediaSession` 에 옮겨
 * 담고, 알림 그늘과 잠금화면에 플레이어가 뜬다. 버튼을 누르면 그 반대 방향으로
 * 이벤트가 돌아온다.
 *
 * 웹에서는 아무 일도 하지 않는다 — `navigator.mediaSession` 은 `<audio>` 로
 * 소리를 낼 때만 브라우저 UI 를 띄우는데 이 플레이어는 Web Audio 로 직접 그린다.
 */

import { invoke, isTauri, listen } from './bridge';

export type NowPlaying = {
  title: string;
  artist: string;
  album: string;
  durationSec: number;
  positionSec: number;
  playing: boolean;
  /**
   * 앨범 아트. 화면이 쓰는 주소 그대로 넘긴다.
   *
   * 앱에서 이 값은 `toImageSource` 를 지난 `mupidimg` 주소인데, 그 스킴은
   * 웹뷰 안에서만 통한다. 원래 주소로 되돌리는 일은 Rust 가 한다
   * (`app/src/image.rs`) — 그 스킴을 만든 쪽이 푸는 것도 맡는다.
   */
  artwork: string | null;
  canPrevious: boolean;
  canNext: boolean;
};

export type MediaControl = {
  action: 'play' | 'pause' | 'next' | 'previous' | 'stop' | 'seek';
  /** `seek` 일 때의 목적지(초). 그 밖에는 0. */
  positionSec: number;
};

/** Rust 가 버튼을 실어 보내는 이벤트 이름. `app/src/media.rs` 와 같아야 한다. */
const CONTROL_EVENT = 'media-session://control';

/**
 * 재생 위치를 다시 올려보내는 기준(초).
 *
 * 안드로이드는 마지막으로 받은 위치와 재생 속도로 **스스로 위치를 굴린다.**
 * 그래서 0.2초마다 올려보낼 필요가 없다 — 버퍼를 기다리느라 실제 위치가
 * 어긋났을 때만 고쳐 주면 된다. 매 틱마다 보내면 곡 하나에 IPC 가 천 번 넘게
 * 오간다.
 */
const DRIFT_SEC = 2;

type Sent = { state: NowPlaying; at: number };

/** 다시 올려보내야 하는가. */
export function shouldPush(previous: Sent | null, next: NowPlaying, at: number): boolean {
  if (!previous) return true;
  const last = previous.state;
  if (
    last.title !== next.title ||
    last.artist !== next.artist ||
    last.album !== next.album ||
    last.artwork !== next.artwork ||
    last.durationSec !== next.durationSec ||
    last.playing !== next.playing ||
    last.canPrevious !== next.canPrevious ||
    last.canNext !== next.canNext
  ) {
    return true;
  }
  // 멈춰 있으면 시스템도 위치를 굴리지 않는다. 그때는 위치 자체를 비교한다.
  const elapsed = last.playing ? (at - previous.at) / 1000 : 0;
  const expected = last.positionSec + elapsed;
  return Math.abs(next.positionSec - expected) > DRIFT_SEC;
}

export type MediaSession = {
  update(state: NowPlaying): Promise<void>;
  clear(): Promise<void>;
};

/**
 * 네이티브 플레이어에 상태를 올려보내는 통로.
 *
 * `now` 는 시험에서 시계를 대신 넣기 위한 것이다.
 */
export function createMediaSession(options: { now?: () => number } = {}): MediaSession {
  const now = options.now ?? (() => Date.now());
  let sent: Sent | null = null;
  // 올려보내는 중에 다음 상태가 들어와도 순서가 뒤집히지 않게 한 줄로 세운다.
  let queue: Promise<void> = Promise.resolve();

  const run = (task: () => Promise<unknown>): Promise<void> => {
    queue = queue.then(task).then(
      () => {},
      (error) => {
        // 알림이 하나 늦는 것뿐이다. 재생까지 멈출 이유는 없다.
        console.warn('[media-session]', error);
      },
    );
    return queue;
  };

  return {
    update(state) {
      if (!isTauri()) return Promise.resolve();
      const at = now();
      if (!shouldPush(sent, state, at)) return Promise.resolve();
      sent = { state, at };
      return run(() => invoke('media_session_update', { state }));
    },
    clear() {
      if (!isTauri()) return Promise.resolve();
      if (!sent) return Promise.resolve();
      sent = null;
      return run(() => invoke('media_session_clear'));
    },
  };
}

/**
 * 알림·잠금화면·이어폰 버튼을 받는다. 돌려주는 함수를 부르면 끊는다.
 *
 * 구독이 붙기 전에 끊으라고 할 수도 있다(빠른 화면 이동). 그래서 붙은 뒤에
 * 다시 한 번 확인하고, 이미 끊겼으면 그 자리에서 되돌린다.
 */
export function onMediaControl(handler: (control: MediaControl) => void): () => void {
  if (!isTauri()) return () => {};
  let stop: (() => void) | null = null;
  let cancelled = false;
  void listen<{ action: string; positionMs: number }>(CONTROL_EVENT, (payload) => {
    handler({
      action: payload.action as MediaControl['action'],
      positionSec: payload.positionMs > 0 ? payload.positionMs / 1000 : 0,
    });
  })
    .then((unlisten) => {
      if (cancelled) unlisten();
      else stop = unlisten;
    })
    .catch((error) => console.warn('[media-session]', error));
  return () => {
    cancelled = true;
    stop?.();
    stop = null;
  };
}
