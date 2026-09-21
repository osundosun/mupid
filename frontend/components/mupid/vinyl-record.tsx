'use client';
import Image, { type ImageProps } from 'next/image';
import { useEffect, useRef } from 'react';
import type { Track } from '@/lib/mupid-data';
import { AlbumArt } from './primitives';

/** The supplied photograph's spindle is at (607, 601), not its canvas centre.
 * Rotate only the vinyl, keeping the album label and tonearm stationary.
 * A permanently mounted animation preserves its angle across pause/resume.
 */
export function VinylRecord({ playing, track, className = '', ...imageProps }: ImageProps & { playing: boolean; track: Track | null }) {
  const disc = useRef<HTMLSpanElement>(null);
  const motion = useRef({ angle: 0, speed: 0 });
  useEffect(() => {
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
    let frame = 0;
    let last = 0;
    const tick = (now: number) => {
      const dt = last ? Math.min((now - last) / 1000, 0.05) : 0;
      last = now;
      const state = motion.current;
      // A gentle 18-second revolution with gradual motor acceleration and coast-down.
      const target = playing ? 20 : 0;
      const previousSpeed = state.speed;
      state.speed += (target - state.speed) * (1 - Math.exp(-dt / (playing ? 0.65 : 0.8)));
      if (!playing && state.speed < 0.08) state.speed = 0;
      state.angle = (state.angle + (previousSpeed + state.speed) * 0.5 * dt) % 360;
      if (disc.current) disc.current.style.transform = `rotate(${state.angle}deg)`;
      if (playing || state.speed > 0) frame = requestAnimationFrame(tick);
    };
    const syncMotion = () => {
      cancelAnimationFrame(frame);
      last = 0;
      if (reduced.matches) motion.current.speed = 0;
      else frame = requestAnimationFrame(tick);
    };
    syncMotion();
    reduced.addEventListener('change', syncMotion);
    return () => {
      cancelAnimationFrame(frame);
      reduced.removeEventListener('change', syncMotion);
    };
  }, [playing]);
  return (
    <span className={`record-motion ${className}`} data-playing={playing}>
      <Image {...imageProps} className="record-photo" />
      <span ref={disc} className="record-disc-rotation" aria-hidden="true" />
      <span className="record-disc-light" aria-hidden="true" />
      <span className="record-label-window" aria-hidden="true">
        <AlbumArt track={track} />
      </span>
      <span className="record-tonearm" aria-hidden="true" />
      <span className="record-spindle" aria-hidden="true" />
    </span>
  );
}
