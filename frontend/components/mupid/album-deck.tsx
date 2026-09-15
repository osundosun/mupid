'use client';
import { useEffect, useState } from 'react';
import { ArrowUpRight, Pause, Play } from 'lucide-react';
import {
  Carousel,
  CarouselContent,
  CarouselItem,
  type CarouselApi,
} from '@/components/ui/carousel';
import type { MupidModel } from '@/hooks/use-mupid';
import type { Track } from '@/lib/mupid-data';
import { AlbumArt } from './primitives';

export function AlbumDeck({
  model: m,
  items,
  label,
}: {
  model: MupidModel;
  items: Track[];
  label: string;
}) {
  const [api, setApi] = useState<CarouselApi>();
  const [index, setIndex] = useState(0);
  const [reducedMotion, setReducedMotion] = useState(true);
  const selectedIndex = Math.min(index, Math.max(0, items.length - 1));
  const selected = items[selectedIndex];

  useEffect(() => {
    const preference = window.matchMedia('(prefers-reduced-motion: reduce)');
    const update = () => setReducedMotion(preference.matches);
    update();
    preference.addEventListener('change', update);
    return () => preference.removeEventListener('change', update);
  }, []);

  useEffect(() => {
    if (!api) return;
    const update = () => setIndex(api.selectedScrollSnap());
    update();
    api.on('select', update);
    api.on('reInit', update);
    return () => {
      api.off('select', update);
      api.off('reInit', update);
    };
  }, [api]);

  if (!selected)
    return <p className="album-deck-empty">지금 표시할 추천곡이 없어요.</p>;
  const playing = m.active?.id === selected.id && m.playing;

  return (
    <Carousel
      className="album-deck"
      aria-label={label}
      setApi={setApi}
      opts={{
        align: 'center',
        containScroll: false,
        loop: false,
        duration: reducedMotion ? 0 : 28,
      }}
    >
      <CarouselContent className="album-deck-track">
        {items.map((track, i) => (
          <CarouselItem
            key={track.id}
            className="album-deck-slide"
            aria-label={`${i + 1} / ${items.length} · ${track.title}`}
            data-position={
              i === selectedIndex
                ? 'current'
                : i < selectedIndex
                  ? 'before'
                  : 'after'
            }
          >
            <button
              className="album-deck-pick"
              aria-label={`${track.title}, ${track.artist} 앨범 선택`}
              aria-pressed={i === selectedIndex}
              onClick={() => api?.scrollTo(i, reducedMotion)}
            >
              <AlbumArt track={track} />
            </button>
          </CarouselItem>
        ))}
      </CarouselContent>
      <div className="album-deck-detail">
        <div className="album-deck-copy" key={selected.id}>
          <span className="album-deck-mood">{selected.mood}</span>
          <h3>{selected.title}</h3>
          <p>{selected.artist}</p>
        </div>
        <button
          className="icon-button album-deck-play"
          aria-label={`${selected.title} ${playing ? '일시정지' : '재생'}`}
          onClick={() => m.play(selected)}
        >
          {playing ? (
            <Pause fill="currentColor" />
          ) : (
            <Play fill="currentColor" />
          )}
        </button>
        <button
          className="icon-button album-deck-open"
          aria-label={`${selected.title} 곡 자세히 보기`}
          onClick={() => m.openTrack(selected)}
        >
          <ArrowUpRight />
        </button>
      </div>
    </Carousel>
  );
}
