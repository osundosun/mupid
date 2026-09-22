'use client';
import { useEffect, useState } from 'react';
import { api } from '@/lib/api/session';

/** Keep code names in the model; resolve server display names only for rendering. */
export function GenreName({ genre }: { genre: string }) {
  const [names, setNames] = useState<Record<string, string>>({});
  useEffect(() => {
    const controller = new AbortController();
    void api.music.filterOptions(controller.signal).then(options => {
      if (!controller.signal.aborted) {
        setNames(Object.fromEntries(options.genres.map(item => [item.name, item.display_name?.trim() || item.name.replaceAll('_', ' ')])));
      }
    }).catch(() => { /* Keep a readable fallback if the options endpoint is unavailable. */ });
    return () => controller.abort();
  }, []);
  return <>{names[genre] || genre.replaceAll('_', ' ')}</>;
}
