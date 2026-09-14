'use client';
import { useSyncExternalStore } from 'react';

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
