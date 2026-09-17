'use client';
import { useEffect, useRef } from 'react';
import { getPlayer } from '@/lib/player';

export function AudioVisualizer({ playing }: { playing: boolean }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const element = canvas.current;
    const ctx = element?.getContext('2d');
    if (!element || !ctx) return;
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
    let frame = 0;
    const values = new Uint8Array(128);
    const draw = () => {
      const width = element.clientWidth;
      const height = 48;
      const ratio = window.devicePixelRatio || 1;
      if (element.width !== Math.round(width * ratio)) element.width = Math.round(width * ratio);
      element.height = height * ratio;
      ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
      ctx.clearRect(0, 0, width, height);
      const analyser = getPlayer()?.getAnalyser();
      if (playing && analyser && !reduced.matches) analyser.getByteFrequencyData(values);
      else values.fill(0);
      const count = 40;
      const step = width / count;
      for (let i = 0; i < count; i++) {
        const bin = Math.min(127, Math.floor(Math.pow(i / (count - 1), 1.8) * 110) + 1);
        const size = 3 + values[bin] / 255 * 39;
        ctx.fillStyle = playing ? '#85aed7' : '#d3e1ef';
        ctx.beginPath();
        ctx.roundRect(i * step + 1, (height - size) / 2, Math.max(1, step - 3), size, 2);
        ctx.fill();
      }
      if (playing && !reduced.matches && !document.hidden) frame = requestAnimationFrame(draw);
    };
    const refresh = () => { cancelAnimationFrame(frame); draw(); };
    draw();
    document.addEventListener('visibilitychange', refresh);
    reduced.addEventListener('change', refresh);
    return () => { cancelAnimationFrame(frame); document.removeEventListener('visibilitychange', refresh); reduced.removeEventListener('change', refresh); };
  }, [playing]);
  return <canvas ref={canvas} style={{ width: '100%', height: 48, display: 'block', marginBlock: 12 }} role="img" aria-label="현재 재생 소리에 반응하는 오디오 비주얼라이저" />;
}
