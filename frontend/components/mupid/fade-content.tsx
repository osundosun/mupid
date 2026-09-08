'use client';
import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';

/** Keep the outgoing content mounted until its opacity transition completes. */
export function FadeContent({ contentKey, children }: { contentKey: string; children: ReactNode }) {
  const element = useRef<HTMLDivElement>(null);
  const latest = useRef({ key: contentKey, children });
  latest.current = { key: contentKey, children };
  const [shown, setShown] = useState(latest.current);
  const previous = useRef(children);
  if (shown.key === contentKey) previous.current = children;

  useLayoutEffect(() => {
    const node = element.current;
    if (!node) return;
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      if (shown.key !== contentKey) setShown(latest.current);
      return;
    }
    let cancelled = false;
    const outgoing = shown.key !== contentKey;
    const animation = node.animate(
      outgoing ? [{ opacity: 1 }, { opacity: 0 }] : [{ opacity: 0 }, { opacity: 1 }],
      { duration: outgoing ? 140 : 220, easing: 'ease-in-out', fill: 'both' },
    );
    animation.finished.then(() => {
      if (!cancelled && outgoing) setShown(latest.current);
    }).catch(() => {});
    return () => { cancelled = true; animation.cancel(); };
  }, [contentKey, shown.key]);

  return <div ref={element} className="explore-fade-content">
    {shown.key === contentKey ? children : previous.current}
  </div>;
}
