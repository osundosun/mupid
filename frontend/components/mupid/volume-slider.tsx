'use client';
import { usePlayerVolume } from '@/lib/player';

/**
 * 볼륨 막대.
 *
 * 값을 `useMupid` 가 아니라 **재생기에서 직접** 구독한다. 앱 모델에 두었을
 * 때는 한 칸 움직일 때마다 앱 전체가 다시 그려져 끄는 손이 뚝뚝 끊겼다
 * (한 칸에 32ms). 지금은 이 막대만 다시 그린다.
 *
 * 눈금은 1%다. 5%였을 때는 끌어도 계단처럼 뛰었다. 위치를 귀에 맞게 굽히는
 * 일은 재생기가 한다(`gainFor`).
 */
export function VolumeSlider() {
  const [volume, setVolume] = usePlayerVolume();
  return (
    <input
      type="range"
      aria-label="볼륨"
      min={0}
      max={1}
      step={0.01}
      value={volume}
      onChange={(event) => setVolume(Number(event.target.value))}
    />
  );
}
