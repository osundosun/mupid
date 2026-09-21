'use client';
import { useEffect, useRef } from 'react';

export function SkyCursor() {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const cursor = ref.current;
    if (!cursor) return;
    const media = matchMedia('(hover: hover) and (pointer: fine)');
    let position: { x: number; y: number } | null = null;
    let frame = 0;
    const updateHover = (target: Element | null) => {
      cursor.dataset.hover = String(Boolean(target?.closest('button:not(:disabled), a[href], [role="button"], input[type="range"], summary')));
    };
    const scroll = () => {
      if (!position || frame) return;
      frame = requestAnimationFrame(() => {
        frame = 0;
        if (position) updateHover(document.elementFromPoint(position.x, position.y));
      });
    };
    const hide = () => {
      position = null;
      cursor.style.visibility = 'hidden';
      document.documentElement.classList.remove('sky-cursor-active');
    };
    const move = (event: PointerEvent) => {
      if (!media.matches || event.pointerType !== 'mouse') { hide(); return; }
      // 브라우저가 그리는 스크롤바에는 CSS cursor:none이 적용되지 않는다.
      // 해당 영역에서는 오버레이를 숨겨 네이티브 커서와 겹치지 않게 한다.
      const root = document.documentElement;
      if (event.clientX >= root.clientWidth || event.clientY >= root.clientHeight) {
        hide();
        return;
      }
      const target = event.target instanceof Element ? event.target : null;
      if (target instanceof HTMLElement && target !== root && target !== document.body &&
          (target.offsetWidth > target.clientWidth || target.offsetHeight > target.clientHeight)) {
        const rect = target.getBoundingClientRect();
        const x = event.clientX - rect.left - target.clientLeft;
        const y = event.clientY - rect.top - target.clientTop;
        if ((target.scrollHeight > target.clientHeight && x >= target.clientWidth) ||
            (target.scrollWidth > target.clientWidth && y >= target.clientHeight)) {
          hide();
          return;
        }
      }
      position = { x: event.clientX, y: event.clientY };
      updateHover(target);
      cursor.style.transform = `translate3d(${event.clientX}px, ${event.clientY}px, 0)`;
      cursor.style.visibility = 'visible';
      document.documentElement.classList.add('sky-cursor-active');
    };
    // 요소 경계의 pointerout은 페이지 이탈이 아니다. 실제 뷰포트 이탈만 처리한다.
    const leave = (event: PointerEvent) => {
      if (!event.relatedTarget || event.clientX <= 0 || event.clientY <= 0 ||
          event.clientX >= document.documentElement.clientWidth ||
          event.clientY >= document.documentElement.clientHeight) hide();
    };
    window.addEventListener('pointermove', move, { passive: true, capture: true });
    window.addEventListener('pointerover', move, { passive: true, capture: true });
    window.addEventListener('scroll', scroll, { passive: true, capture: true });
    document.documentElement.addEventListener('pointerleave', leave);
    window.addEventListener('blur', hide);
    media.addEventListener('change', hide);
    return () => {
      hide();
      cancelAnimationFrame(frame);
      window.removeEventListener('scroll', scroll, true);
      window.removeEventListener('pointermove', move, true);
      window.removeEventListener('pointerover', move, true);
      document.documentElement.removeEventListener('pointerleave', leave);
      window.removeEventListener('blur', hide);
      media.removeEventListener('change', hide);
    };
  }, []);
  return <div ref={ref} className="sky-cursor-overlay" aria-hidden="true" />;
}
