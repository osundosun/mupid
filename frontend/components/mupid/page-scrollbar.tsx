'use client';
import { useEffect, useRef, useState } from 'react';

export function PageScrollbar() {
  const [metrics, setMetrics] = useState({ height: 0, thumb: 0, top: 0, max: 0, value: 0 });
  const track = useRef<HTMLDivElement>(null);
  const drag = useRef<{ y: number; scroll: number } | null>(null);
  useEffect(() => {
    const media = matchMedia('(min-width: 768px) and (hover: hover) and (pointer: fine) and (forced-colors: none)');
    let frame = 0;
    const sync = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        const root = document.documentElement;
        root.classList.toggle('custom-page-scrollbar', media.matches);
        const height = root.clientHeight;
        const max = Math.max(0, root.scrollHeight - height);
        const thumb = Math.min(height, Math.max(36, height * height / Math.max(height, root.scrollHeight)));
        setMetrics({ height, thumb, max, value: window.scrollY, top: max ? window.scrollY / max * (height - thumb) : 0 });
      });
    };
    const resize = new ResizeObserver(sync);
    resize.observe(document.body);
    resize.observe(document.documentElement);
    window.addEventListener('scroll', sync, { passive: true });
    window.addEventListener('resize', sync);
    media.addEventListener('change', sync);
    sync();
    return () => {
      cancelAnimationFrame(frame);
      resize.disconnect();
      window.removeEventListener('scroll', sync);
      window.removeEventListener('resize', sync);
      media.removeEventListener('change', sync);
      document.documentElement.classList.remove('custom-page-scrollbar');
    };
  }, []);
  const scrollTo = (top: number) => window.scrollTo({ top: Math.max(0, Math.min(metrics.max, top)), behavior: 'instant' });
  return <div ref={track} className="page-scrollbar" style={{ visibility: metrics.max ? 'visible' : 'hidden' }}
    onPointerDown={event => {
      if (event.button !== 0) return;
      event.preventDefault();
      const y = event.clientY;
      let scroll = window.scrollY;
      if (!(event.target instanceof Element && event.target.closest('.page-scrollbar-thumb'))) {
        scroll = (y - metrics.thumb / 2) / Math.max(1, metrics.height - metrics.thumb) * metrics.max;
        scrollTo(scroll);
        scroll = Math.max(0, Math.min(metrics.max, scroll));
      }
      drag.current = { y, scroll };
      event.currentTarget.setPointerCapture(event.pointerId);
    }}
    onPointerMove={event => {
      if (drag.current) scrollTo(drag.current.scroll + (event.clientY - drag.current.y) / Math.max(1, metrics.height - metrics.thumb) * metrics.max);
    }}
    onPointerUp={event => { drag.current = null; if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId); }}
    onPointerCancel={() => { drag.current = null; }} onLostPointerCapture={() => { drag.current = null; }}>
    <div className="page-scrollbar-thumb" role="scrollbar" aria-label="페이지 스크롤" aria-controls="page-content"
      aria-orientation="vertical" aria-valuemin={0} aria-valuemax={Math.round(metrics.max)} aria-valuenow={Math.round(metrics.value)}
      tabIndex={metrics.max ? 0 : -1} style={{ height: metrics.thumb, transform: `translateY(${metrics.top}px)` }}
      onKeyDown={event => {
        const targets: Record<string, number> = { ArrowDown: metrics.value + 40, ArrowUp: metrics.value - 40, PageDown: metrics.value + metrics.height * .9, PageUp: metrics.value - metrics.height * .9, Home: 0, End: metrics.max };
        if (event.key in targets) { event.preventDefault(); scrollTo(targets[event.key]); }
      }} />
  </div>;
}
