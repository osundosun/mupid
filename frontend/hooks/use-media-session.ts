'use client';

/**
 * 재생 상태를 상단 알림과 잠금화면에 이어 준다.
 *
 * 값을 올려보내는 쪽은 **React 상태가 아니라 재생기 스토어를 구독한다.** 화면이
 * 다시 그려지는 것과 소리가 흐르는 것은 주기가 다르고, 앱이 뒤로 가 있을 때는
 * React 쪽이 아예 멈춰 있을 수도 있기 때문이다. 스토어는 0.2초마다 울리지만
 * 실제로 IPC 를 타는 것은 곡·재생 상태가 바뀌거나 위치가 어긋났을 때뿐이다
 * (`lib/platform/media-session.ts` 의 `shouldPush`).
 */

import { useEffect, useRef } from 'react';
import type { Track } from '@/lib/mupid-data';
import { getPlayer } from '@/lib/player';
import { createMediaSession, onMediaControl } from '@/lib/platform';

export type MediaSessionModel = {
  /** 지금 걸려 있는 곡. 없으면 알림을 거둔다. */
  track: Track | null;
  canPrevious: boolean;
  canNext: boolean;
  onPlay(): void;
  onPause(): void;
  onNext(): void;
  onPrevious(): void;
  onSeek(positionSec: number): void;
  onStop(): void;
};

export function useMediaSession(model: MediaSessionModel): void {
  // 매 렌더마다 바뀌는 콜백을 구독에 그대로 물리면 0.2초마다 구독을 다시 건다.
  // 최신 값만 여기 담아 두고, 구독은 한 번만 건다. 담는 것은 렌더 중이 아니라
  // 효과에서 한다 — 렌더 중에 ref 를 건드리면 React 가 렌더를 버릴 때 어긋난다.
  const latest = useRef(model);
  const push = useRef<() => void>(() => {});
  useEffect(() => {
    latest.current = model;
  });

  useEffect(() => {
    const player = getPlayer();
    if (!player) return;
    const session = createMediaSession();

    push.current = () => {
      const { track, canPrevious, canNext } = latest.current;
      const state = player.store.get();
      // 아직 한 곡도 걸지 않았거나(`idle`) 재생을 닫은 뒤다. 곡 하나가
      // 골라져 있다는 것만으로 알림을 띄우면, 앱을 켜자마자 듣지도 않은 곡이
      // 잠금화면에 올라앉는다.
      if (!track || state.musicId === null) {
        void session.clear();
        return;
      }
      // 화면은 곡을 먼저 바꾸고 재생기는 조금 뒤에 따라온다. 그 틈에 올려보내면
      // **새 곡 제목에 옛 곡의 재생 위치**가 붙어 잠금화면 막대가 한 번 튄다.
      // 재생기가 이 곡을 집을 때까지는 직전 상태를 그대로 둔다.
      if (track.musicId !== state.musicId) return;
      void session.update({
        title: track.title,
        artist: track.artist,
        album: track.album,
        // 길이는 곡 정보 쪽이 맞다. 재생기는 첫 청크를 받기 전까지 0 이고,
        // 길이가 0 이면 잠금화면에서 막대가 사라진다.
        durationSec: track.duration,
        positionSec: state.positionSec,
        // 버퍼를 기다리는 동안에도 사용자에게는 재생 중이다. 여기서 멈춤으로
        // 보이면 잠금화면 버튼이 회선이 흔들릴 때마다 깜빡인다.
        playing: state.status === 'playing' || state.status === 'buffering',
        artwork: track.image || null,
        canPrevious,
        canNext,
      });
    };

    push.current();
    const unsubscribe = player.store.subscribe(() => push.current());
    return () => {
      push.current = () => {};
      unsubscribe();
      void session.clear();
    };
  }, []);

  // 곡 제목이나 대기열이 바뀐 것은 재생기 스토어가 모른다. 그때는 여기서
  // 한 번 밀어 준다 — 그러지 않으면 다음 위치 틱까지 알림이 옛 곡을 보여 준다.
  useEffect(() => {
    push.current();
  }, [model.track?.id, model.canPrevious, model.canNext]);

  useEffect(
    () =>
      onMediaControl((control) => {
        const handlers = latest.current;
        switch (control.action) {
          case 'play':
            handlers.onPlay();
            break;
          case 'pause':
            handlers.onPause();
            break;
          case 'next':
            handlers.onNext();
            break;
          case 'previous':
            handlers.onPrevious();
            break;
          case 'seek':
            handlers.onSeek(control.positionSec);
            break;
          case 'stop':
            handlers.onStop();
            break;
        }
      }),
    [],
  );
}
