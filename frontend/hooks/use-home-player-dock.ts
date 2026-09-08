'use client';
import { useEffect, useState, useSyncExternalStore } from 'react';

const desktopQuery = '(min-width: 1024px)';
const subscribeDesktop = (notify: () => void) => {
  const media = window.matchMedia(desktopQuery);
  media.addEventListener('change', notify);
  return () => media.removeEventListener('change', notify);
};
export function useDesktopPlayerDock() {
  return useSyncExternalStore(
    subscribeDesktop,
    () => window.matchMedia(desktopQuery).matches,
    () => false,
  );
}

// Show one set of playback controls, then restore the dock below the listening room.
export function useHomePlayerDock(page: string) {
  const [controlsVisible, setControlsVisible] = useState(false);
  useEffect(() => {
    if (page !== 'home' || typeof IntersectionObserver === 'undefined') return;
    const controls = document.getElementById('home-listening-controls');
    if (!controls) return;
    const observer = new IntersectionObserver(
      ([entry]) => {
        // Never remove a dock button while a keyboard user has focus inside it.
        const dockFocused = document
          .querySelector('.mini-player')
          ?.contains(document.activeElement);
        setControlsVisible(
          entry.isIntersecting &&
            entry.intersectionRatio >= 0.7 &&
            !dockFocused,
        );
      },
      { threshold: [0, 0.7, 1], rootMargin: '0px 0px -100px 0px' },
    );
    observer.observe(controls);
    return () => observer.disconnect();
  }, [page]);
  return page === 'home' && controlsVisible;
}
