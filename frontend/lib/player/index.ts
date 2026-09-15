'use client';

/**
 * 플레이어 공개 표면.
 *
 * 화면은 이 파일만 import 한다. 엔진 내부(링버퍼·디코더·스케줄러)는 바뀌어도
 * 여기 계약이 유지되면 화면을 건드릴 일이 없다.
 */

import { useEffect } from 'react';
import { useStore } from '../store';
import { DEFAULT_VOLUME, getPlayer, type PlayerState, type PlayerStatus } from './engine';

export { getPlayer, PlayerEngine } from './engine';
export type { PlayerState, PlayerStatus } from './engine';
export { isOpusDecodingSupported } from './decoder';
export { parseChunk, ChunkFormatError } from './rtp';
export { RingBuffer } from './ring-buffer';

const idle: PlayerState = {
  status: 'idle',
  musicId: null,
  positionSec: 0,
  durationSec: 0,
  bufferedSec: 0,
  volume: DEFAULT_VOLUME,
  error: null,
  supported: false,
};

export type PlayerApi = PlayerState & {
  /** 스피너를 띄워야 하는 상태인가. */
  readonly isBusy: boolean;
  load(musicId: number, options?: { autoplay?: boolean }): Promise<void>;
  play(): Promise<void>;
  pause(): void;
  seek(positionSec: number): Promise<void>;
  stop(): void;
  setVolume(value: number): void;
  /** 다음에 재생할 곡들. 앞 5초를 미리 받아 둔다. */
  setQueue(musicIds: number[]): void;
};

const noop = async () => {};

/** 플레이어가 없을 때(SSR·미지원) 쓰는 읽기 전용 스토어. */
const idleStore = {
  get: () => idle,
  set: () => {},
  subscribe: () => () => {},
};

/**
 * 볼륨만 구독한다.
 *
 * `usePlayer()` 는 상태 전체를 보므로 재생 위치가 0.2초마다 흐르는 동안 계속
 * 다시 그린다. 볼륨 막대가 그걸 타면 **앱 전체가 매 칸마다 다시 그려져** 끄는
 * 손이 뚝뚝 끊긴다. 볼륨은 엔진이 들고 있는 값이니 여기만 따로 구독한다.
 */
export function usePlayerVolume(): [number, (value: number) => void] {
  const player = getPlayer();
  const volume = useStore(player?.store ?? idleStore, (state) => state.volume);
  return [volume, player ? player.setVolume.bind(player) : () => {}];
}

/**
 * 플레이어 상태를 구독한다.
 *
 * 마운트 시점에 AudioContext 를 만든다. 브라우저 정책상 소리는 사용자
 * 제스처가 있어야 나지만, 그래프를 미리 세워 두면 첫 재생이 즉시 시작된다.
 */
export function usePlayer(): PlayerApi {
  const player = getPlayer();
  useEffect(() => {
    player?.prepare();
  }, [player]);

  const state = useStore(
    player?.store ?? { get: () => idle, set: () => {}, subscribe: () => () => {} },
    (value) => value,
  );

  return {
    ...state,
    isBusy: state.status === 'loading' || state.status === 'buffering',
    load: player ? player.load.bind(player) : noop,
    play: player ? player.play.bind(player) : noop,
    pause: player ? player.pause.bind(player) : () => {},
    seek: player ? player.seek.bind(player) : noop,
    stop: player ? player.stop.bind(player) : () => {},
    setVolume: player ? player.setVolume.bind(player) : () => {},
    setQueue: player ? player.setQueue.bind(player) : () => {},
  };
}
