'use client';
import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';

type Bar = { el: HTMLElement; horizontal: boolean; x: number; y: number; length: number; thumb: number; offset: number; max: number; value: number };
export function InnerScrollbars() {
  const [bars, setBars] = useState<Bar[]>([]);
  useEffect(() => {
    const media = matchMedia('(hover: hover) and (pointer: fine) and (forced-colors: none)');
    const tracked = new Set<HTMLElement>();
    let frame = 0;
    const refresh = () => {
      frame = 0;
      if (!media.matches) { tracked.forEach(el => el.classList.remove('custom-inner-scroll')); tracked.clear(); setBars([]); return; }
      const next: Bar[] = [];
      // 스크롤바는 body 포털이므로 모달 뒤의 영역도 위에 그려질 수 있다.
      // 가장 위에 열린 대화상자 안의 스크롤만 노출한다.
      const dialogs = Array.from(document.querySelectorAll<HTMLElement>(
        '[data-slot="dialog-content"][data-open], [data-slot="alert-dialog-content"][data-open]',
      )).filter(el => el.getClientRects().length > 0 && !el.closest('[aria-hidden="true"], [inert]'));
      const topDialog = dialogs.sort((a, b) =>
        (Number.parseInt(getComputedStyle(a).zIndex) || 0) - (Number.parseInt(getComputedStyle(b).zIndex) || 0),
      ).at(-1);
      const menuBounds = Array.from(document.querySelectorAll<HTMLElement>('.nav-account-panel, .recent-search-panel, .chat-panel, .queue-dock, .queue-dock-scrim'))
        .filter(menu => menu.getClientRects().length > 0)
        .map(menu => ({ element: menu, bounds: menu.getBoundingClientRect() }));
      tracked.forEach(el => { if (!el.isConnected) { resize.unobserve(el); tracked.delete(el); } });
      document.body.querySelectorAll<HTMLElement>('*').forEach(el => {
        if (el.closest('[data-custom-scrollbar], .sky-cursor-overlay, .page-scrollbar') || ['INPUT','SVG'].includes(el.tagName)) return;
        const style = getComputedStyle(el);
        const vertical = /auto|scroll/.test(style.overflowY) && el.scrollHeight > el.clientHeight + 1;
        const horizontal = /auto|scroll/.test(style.overflowX) && el.scrollWidth > el.clientWidth + 1;
        if (!vertical && !horizontal) { el.classList.remove('custom-inner-scroll'); tracked.delete(el); return; }
        el.classList.add('custom-inner-scroll'); if (!tracked.has(el)) resize.observe(el); tracked.add(el);
        if (topDialog && !topDialog.contains(el)) return;
        if (el.closest('[aria-hidden="true"], [inert]')) return;
        const rect = el.getBoundingClientRect();
        if (!rect.width || !rect.height || style.visibility === 'hidden') return;
        let top = Math.max(0, rect.top), bottom = Math.min(innerHeight, rect.bottom);
        let left = Math.max(0, rect.left), right = Math.min(innerWidth, rect.right);
        if (el.matches('.selected-moment-messages')) {
          top = Math.max(top, rect.top + 10);
          bottom = Math.min(bottom, rect.bottom - 10);
          right = Math.min(right, rect.right - 4);
        }
        // Portal scrollbars must stay inside the dialog's rounded corners.
        if (el.matches('.demo-login-dialog, .moment-comment-dialog, .explore-category-card, .explore-filter-sidebar')) {
          const inset = Math.max(16, parseFloat(style.borderTopRightRadius) || 0,
            parseFloat(style.borderBottomRightRadius) || 0);
          top = Math.max(top, rect.top + inset);
          bottom = Math.min(bottom, rect.bottom - inset);
          right = Math.min(right, rect.right - 6);
        }
        for (let parent = el.parentElement; parent; parent = parent.parentElement) {
          const ps = getComputedStyle(parent), pr = parent.getBoundingClientRect();
          if (/auto|scroll|hidden|clip/.test(ps.overflowY)) { top = Math.max(top, pr.top); bottom = Math.min(bottom, pr.bottom); }
          if (/auto|scroll|hidden|clip/.test(ps.overflowX)) { left = Math.max(left, pr.left); right = Math.min(right, pr.right); }
        }
        // Body portals do not inherit the content's stacking context. Keep their
        // hit area below the sticky navigation as well as their visible track.
        if (el.closest('.main-content')) {
          const shell = el.closest('.mupid-shell');
          const header = shell?.querySelector('.app-header');
          if (header) {
            const nav = header.getBoundingClientRect();
            if (nav.height > 0 && nav.right > left && nav.left < right) {
              top = Math.max(top, nav.bottom);
            }
          }
          shell?.querySelectorAll('.mobile-nav, .mini-player').forEach(dock => {
            const bounds = dock.getBoundingClientRect();
            if (bounds.height > 0 && bounds.right > left && bounds.left < right) {
              bottom = Math.min(bottom, bounds.top);
            }
          });
        }
        if (bottom <= top || right <= left) return;
        for (const h of [false, true]) {
          if (h ? !horizontal : !vertical) continue;
          const length = h ? right-left : bottom-top;
          // These body portals otherwise paint above the header's dropdowns.
          // Temporarily hide intersecting tracks while the menu is open.
          const x = h ? left : right - 10;
          const y = h ? bottom - 10 : top;
          if (menuBounds.some(({ element, bounds: menu }) => !element.contains(el) &&
            !(element.matches('.queue-dock-scrim') && el.closest('.queue-dock')) &&
            x < menu.right && x + (h ? length : 10) > menu.left &&
            y < menu.bottom && y + (h ? 10 : length) > menu.top)) continue;
          const size = h ? el.clientWidth : el.clientHeight;
          const total = h ? el.scrollWidth : el.scrollHeight;
          const value = h ? el.scrollLeft : el.scrollTop;
          const thumb = Math.min(length, Math.max(24, length * size / total));
          next.push({ el, horizontal: h, x: h ? left : right-10, y: h ? bottom-10 : top, length, thumb, max: total-size, value, offset: value/(total-size)*(length-thumb) });
        }
      });
      setBars(next);
    };
    const schedule = () => { if (!frame) frame = requestAnimationFrame(refresh); };
    const observer = new MutationObserver(records => {
      if (records.some(r => !(r.target instanceof Element && r.target.closest('[data-custom-scrollbar]')))) schedule();
    });
    observer.observe(document.body, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['data-open', 'data-closed', 'aria-hidden', 'inert'] });
    const resize = new ResizeObserver(schedule); resize.observe(document.body);
    document.addEventListener('scroll', schedule, true);
    window.addEventListener('resize', schedule);
    document.addEventListener('animationend', schedule, true);
    document.addEventListener('pointerover', schedule, { passive: true });
    media.addEventListener('change', schedule);
    schedule();
    return () => { cancelAnimationFrame(frame); observer.disconnect(); resize.disconnect(); tracked.forEach(el => el.classList.remove('custom-inner-scroll')); document.removeEventListener('scroll', schedule, true); window.removeEventListener('resize', schedule); document.removeEventListener('animationend', schedule, true); document.removeEventListener('pointerover', schedule); media.removeEventListener('change', schedule); };
  }, []);
  return <>{bars.map((bar, i) => createPortal(<div data-custom-scrollbar="" data-filter-scroll={bar.el.closest('.explore-filter-sidebar, .moment-comment-dialog') ? 'true' : undefined} data-chat-scroll={bar.el.closest('.chat-panel') ? 'true' : undefined} className="inner-scrollbar" style={{ left: bar.x, top: bar.y, width: bar.horizontal ? bar.length : 10, height: bar.horizontal ? 10 : bar.length }}
    ref={node => {
      if (!node) return;
      const wheel = (event: WheelEvent) => { event.preventDefault(); event.stopPropagation(); const scale = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? bar.length : 1; bar.el.scrollBy({ top: bar.horizontal ? 0 : event.deltaY * scale, left: (bar.horizontal ? event.deltaY || event.deltaX : event.deltaX) * scale, behavior: 'instant' }); };
      node.addEventListener('wheel', wheel, { passive: false });
      return () => node.removeEventListener('wheel', wheel);
    }}
    onPointerDown={event => {
      if (event.button !== 0) return;
      event.preventDefault();
      const track = event.currentTarget;
      const start = bar.horizontal ? event.clientX : event.clientY;
      const set = (value: number) => { if (bar.horizontal) bar.el.scrollLeft = value; else bar.el.scrollTop = value; };
      let value = bar.horizontal ? bar.el.scrollLeft : bar.el.scrollTop;
      if (event.target === track) { value = Math.max(0, Math.min(bar.max, (start-(bar.horizontal ? bar.x : bar.y)-bar.thumb/2)/Math.max(1,bar.length-bar.thumb)*bar.max)); set(value); }
      track.setPointerCapture(event.pointerId);
      const move = (e: PointerEvent) => set(value + ((bar.horizontal ? e.clientX : e.clientY)-start)/Math.max(1,bar.length-bar.thumb)*bar.max);
      const end = () => { track.removeEventListener('pointermove',move); track.removeEventListener('pointerup',end); track.removeEventListener('pointercancel',end); track.removeEventListener('lostpointercapture',end); };
      track.addEventListener('pointermove',move); track.addEventListener('pointerup',end); track.addEventListener('pointercancel',end); track.addEventListener('lostpointercapture',end);
    }}><div role="scrollbar" aria-label="영역 스크롤" aria-orientation={bar.horizontal ? 'horizontal' : 'vertical'} aria-valuemin={0} aria-valuemax={Math.round(bar.max)} aria-valuenow={Math.round(bar.value)} tabIndex={0}
    onKeyDown={event => { const delta = ['ArrowDown','ArrowRight'].includes(event.key) ? 40 : ['ArrowUp','ArrowLeft'].includes(event.key) ? -40 : 0; if (delta) { event.preventDefault(); bar.el.scrollBy(bar.horizontal ? {left:delta} : {top:delta}); } }}
    style={{ position:'absolute', borderRadius:999, background:'var(--inner-scroll-thumb, #8daee0)', left:bar.horizontal?bar.offset:bar.el.closest('.explore-filter-sidebar, .moment-comment-dialog')?3:2, top:bar.horizontal?(bar.el.closest('.explore-filter-sidebar, .moment-comment-dialog')?3:2):bar.offset, width:bar.horizontal?bar.thumb:bar.el.closest('.explore-filter-sidebar, .moment-comment-dialog')?4:6, height:bar.horizontal?(bar.el.closest('.explore-filter-sidebar, .moment-comment-dialog')?4:6):bar.thumb }} /></div>, document.body, String(i)))}</>;
}
