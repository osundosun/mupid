'use client';
import type { CSSProperties } from 'react';
import { VinylRecord } from './vinyl-record';
import {
  AudioWaveform,
  ChevronRight,
  Bookmark,
  ListMusic,
  Pause,
  Play,
  Volume2,
} from 'lucide-react';
import { pickTodayMoments, todayMomentNote } from './today-moment';
import { forYouHeading, pickForYou } from '@/lib/recommendations';
import type { MupidModel } from '@/hooks/use-mupid';
import { VolumeSlider } from './volume-slider';
import { formatTime, getSongSectionAt } from '@/lib/mupid-data';
import { RowPlaybackArea, AlbumArt, LikeButton, PlayerControls, TrackRow } from './primitives';
import { AlbumDeck } from './album-deck';
import { GenreName } from './genre-name';

export function DesktopHome({ model: m }: { model: MupidModel }) {
  const tracks = m.tracks;
  const recommendations = pickForYou(m.forYou, tracks, m.ignored);
  const forYou = forYouHeading(m.recommendationsReady);
  const recent = m.history.slice(0, 4).flatMap((id) => {
    const track = m.knownTracks.find((item) => item.id === id);
    return track ? [track] : [];
  });
  const todayMoments = pickTodayMoments(m);
  return (
    <div className="desktop-home">
      <div className="desktop-listening-room">
        <div className="desktop-record-stage">
          <div className="desktop-record-crop">
            <VinylRecord
              playing={m.playing}
              track={m.active}
              unoptimized
              src="/sky-vinyl.webp"
              width={1254}
              height={1254}
              className="desktop-record-art"
              alt="하늘빛 라벨이 담긴 LP 레코드"
            />
          </div>
        </div>
        <section className={`desktop-song${!m.active ? ' is-empty' : ''}`} aria-label="현재 곡">
          <p className="desktop-song-kicker">지금, 당신의 음악</p>
          {/* 곡을 고르기 전에는 탐색을 안내한다. */}
          {!m.active ? <div className="desktop-song-empty">
            <h1>듣고 싶은 음악을 골라보세요</h1>
            <p className="desktop-song-empty-description">추천 음악이나 탐색에서<br />마음에 드는 곡을 골라 재생해 보세요.</p>
            <button className="primary-button" onClick={() => m.go('search')}>
              음악 둘러보기 <ChevronRight />
            </button>
          </div> : <>
          <h1>{m.active.title}</h1>
          <button className="desktop-song-artist track-artist-link" onClick={() => m.setSelectedArtist(m.active!.artist)}>{m.active.artist}</button>
          <p className="desktop-song-meta">
            {m.active.album} <span>·</span> <GenreName genre={m.active.genre} />
          </p>
          <button
            className="text-link desktop-song-detail"
            onClick={() => m.go('player')}
          >
            이 곡 자세히 <ChevronRight />
          </button>
          <div className="desktop-song-playback">
            <PlayerControls model={m} />
            <input
              className="desktop-seek"
              type="range"
              min={0}
              max={m.active.duration}
              step={1}
              value={m.time}
              style={{ '--seek-progress': `${m.active.duration > 0 ? Math.min(100, Math.max(0, m.time / m.active.duration * 100)) : 0}%` } as CSSProperties}
              aria-label="현재 곡 재생 위치"
              aria-valuetext={`${formatTime(m.time)} / ${formatTime(m.active.duration)}`}
              onChange={(e) => m.seek(Number(e.target.value))}
            />
            <div className="time-labels">
              <span>{formatTime(m.time)}</span>
              <span>{formatTime(m.active.duration)}</span>
            </div>
          </div>
          <div className="desktop-song-actions">
            <button
              className="desktop-moment-button"
              onClick={() => m.openMoment()}
            >
              <AudioWaveform />
              <span>저장한 순간 남기기</span>
            </button>
            <LikeButton model={m} />
          </div>
          </>}
          <div className="desktop-audio-options">
            {/* 감싸개는 그대로 둔다 — `.desktop-audio-options label` 에 배치가 걸려 있다. */}
            <label>
              <Volume2 />
              <span className="sr-only">볼륨</span>
              <VolumeSlider />
            </label>
            <button
              className="icon-button"
              aria-label={m.queueOpen ? '재생 목록 접기' : '재생 목록 펼치기'}
              aria-expanded={m.queueOpen}
              aria-controls="queue-dock"
              onClick={m.toggleQueue}
            >
              <ListMusic />
            </button>
          </div>
        </section>
        <aside className="desktop-playlists today-moments" aria-label="오늘의 Moment 추천">
          <header>
            <div><span className="eyebrow">오늘, 마음에 남을 음악의 순간</span><h2>오늘의 Moment</h2></div>
          </header>
          <p className="today-moment-note">{todayMomentNote(m)}</p>
          <div className="desktop-playlist-list">
            {todayMoments.map(track => {
              const section = getSongSectionAt(track, track.moment);
              const playing = m.active?.id === track.id && m.playing;
              return <article data-playable-row className={`desktop-playlist-row ${playing ? 'is-current' : ''}`} key={track.id}><RowPlaybackArea onPlay={() => playing ? m.play() : m.play(track, true)} />
                <button className="desktop-playlist-open" aria-label={`${track.title} ${formatTime(section.start)} 추천 순간 듣기`} onClick={() => m.play(track, true)}>
                  <AlbumArt track={track} />
                  <span><strong>{track.title}</strong><small>{track.artist}</small></span>
                </button>
                <button className="icon-button" aria-label={`${track.title} 추천 순간 ${playing ? '일시정지' : '재생'}`} onClick={() => playing ? m.play() : m.play(track, true)}>{playing ? <Pause fill="currentColor" /> : <Play fill="currentColor" />}</button>
              </article>;
            })}
            {!todayMoments.length && <p className="desktop-section-empty">지금 추천할 수 있는 곡이 없어요. 탐색에서 다른 음악을 만나보세요.</p>}
          </div>
          <button className="desktop-saved-link" onClick={() => {m.setLibraryTab('moments'); m.go('library');}}>
            <Bookmark /><span>저장한 순간 <strong>{m.moments.length}</strong></span><ChevronRight />
          </button>
        </aside>
      </div>
      <div className="desktop-home-shelves">
        <section className="desktop-recommendations">
          <header>
            <div>
              <span className="eyebrow">{forYou.eyebrow}</span>
              <h2>{forYou.title}</h2>
            </div>
            <button className="text-link" onClick={() => m.go('search')}>
              더 둘러보기 <ChevronRight />
            </button>
          </header>
          <div className="desktop-recommendation-list">
            <AlbumDeck
              model={m}
              items={recommendations}
              label="취향에 이어지는 음악"
            />
          </div>
        </section>
        <section className="desktop-recent">
          <header>
            <div>
              <span className="eyebrow">다시 듣기</span>
              <h2>최근 들은 음악</h2>
            </div>
            <button className="text-link" onClick={() => m.go('profile')}>
              감상 기록 <ChevronRight />
            </button>
          </header>
          <div className="desktop-recent-list">
            {/* 다시 듣기는 추천이 아니다 — 홈에 있어도 `RECENT` 로 남긴다. */}
            {recent.map((track) => (
              <TrackRow
                key={track.id}
                track={track}
                model={m}
                queueable
                showDetail={false}
                onPlay={() => m.play(track, false, undefined, 'RECENT')}
              />
            ))}
          </div>
          {!recent.length && (
            <p className="desktop-section-empty">
              음악을 들으면 여기에 모아둘게요.
            </p>
          )}
        </section>
      </div>
    </div>
  );
}
