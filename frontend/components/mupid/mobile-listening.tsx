'use client';
import { VinylRecord } from './vinyl-record';
import { Field } from '@base-ui/react/field';
import { ArrowUpRight, AudioWaveform, ListMusic, Sparkles } from 'lucide-react';
import { Slider } from '@/components/ui/slider';
import type { MupidModel } from '@/hooks/use-mupid';
import { formatTime } from '@/lib/mupid-data';
import { LikeButton, PlayerControls } from './primitives';
import { PlaybackStatus } from './playback-status';

export function MobileListening({ model: m }: { model: MupidModel }) {
  return (
    <section className="mobile-listening" aria-label="지금 듣는 음악">
      <div className="mobile-listening-topline">
        <span>
          <PlaybackStatus playing={m.playing} />
          NOW PLAYING
        </span>
        <button className="text-link" onClick={() => m.go('player')}>
          플레이어 <ArrowUpRight aria-hidden="true" />
        </button>
      </div>
      <button
        className="mobile-record-stage"
        aria-label={`${m.active.title} 플레이어 열기`}
        onClick={() => m.go('player')}
      >
        <VinylRecord
          playing={m.playing}
          track={m.active}
          unoptimized
          className="mobile-record-art"
          src="/sky-vinyl.webp"
          alt=""
          width={440}
          height={440}
          priority
        />
      </button>
      <div className="mobile-listening-detail">
        <div className="mobile-song-heading">
          <div key={m.active.id} className="mobile-song-copy">
            <p className="mobile-album-name">{m.active.album}</p>
            <h2>{m.active.title}</h2>
            <p>{m.active.artist}</p>
          </div>
          <LikeButton model={m} />
        </div>
        <Field.Root className="mobile-home-seek">
          <Field.Label className="sr-only">메인 플레이어 재생 위치</Field.Label>
          <Slider
            aria-label="메인 플레이어 재생 위치"
            value={[m.time]}
            min={0}
            max={m.active.duration}
            step={1}
            onValueChange={(value) =>
              m.seek(Array.isArray(value) ? value[0] : value)
            }
          />
          <div className="mobile-playback-times">
            <span>{formatTime(m.time)}</span>
            <span>{formatTime(m.active.duration)}</span>
          </div>
        </Field.Root>
        <div id="home-listening-controls" className="mobile-listening-controls">
          <PlayerControls model={m} />
        </div>
        <div className="mobile-listening-actions">
          <button onClick={() => m.openMoment()}>
            <AudioWaveform aria-hidden="true" />
            좋아한 구간
          </button>
          <button onClick={m.openChat}>
            <Sparkles aria-hidden="true" />
            AI로 이어듣기
          </button>
          <button
            className="icon-button"
            aria-label="재생 목록 열기"
            onClick={() => m.setQueueDialog(true)}
          >
            <ListMusic />
          </button>
        </div>
      </div>
    </section>
  );
}
