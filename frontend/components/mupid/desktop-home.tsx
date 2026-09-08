'use client';
import { VinylRecord } from './vinyl-record';
import {
  ArrowUpRight,
  AudioWaveform,
  ChevronRight,
  Heart,
  ListMusic,
  Music2,
  Pause,
  Play,
  Search,
  Sparkles,
  Volume2,
} from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
import { formatTime, getSongSectionAt } from '@/lib/mupid-data';
import { AlbumArt, LikeButton, PlayerControls, TrackRow } from './primitives';
import { MupidMascot } from './mascot';
import { AlbumDeck } from './album-deck';

export function DesktopHome({ model: m }: { model: MupidModel }) {
  const tracks = m.tracks;
  const recommendations = tracks
    .filter((track) => !m.ignored.includes(track.id))
    .slice(0, 6);
  const recent = m.history.slice(0, 4).flatMap((id) => {
    const track = tracks.find((item) => item.id === id);
    return track ? [track] : [];
  });
  // 취향은 제외 조건이 아니라 정렬 기준이다. 데모 곡이 적어도 목록이 비지 않게 채운다.
  const selectedMoods = m.onboardingSubmittedAt ? [...new Set(m.moods)] : [];
  const selectedGenres = m.onboardingSubmittedAt ? m.genres : [];
  const todayMoments = tracks
    .filter(track => !m.ignored.includes(track.id))
    .map(track => ({
      track,
      score: Number(selectedMoods.includes(track.mood)) + Number(selectedGenres.includes(track.genre)),
    }))
    .sort((a, b) => b.score - a.score)
    .map(({ track }) => track)
    .slice(0, 4);
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
          <button
            className="desktop-record-caption"
            onClick={() => m.go('player')}
          >
            <span
              className={
                m.playing ? 'record-status is-playing' : 'record-status'
              }
              aria-hidden="true"
            />
            {m.playing ? '지금 재생 중' : '재생 준비'} <ArrowUpRight />
          </button>
        </div>
        <section className="desktop-song" aria-label="현재 곡">
          <p className="desktop-song-kicker">지금, 당신의 음악</p>
          <h1>{m.active.title}</h1>
          <p className="desktop-song-artist">{m.active.artist}</p>
          <p className="desktop-song-meta">
            {m.active.album} <span>·</span> {m.active.genre}
          </p>
          <button
            className="text-link desktop-song-detail"
            onClick={() => m.go('player')}
          >
            이 곡 자세히 <ArrowUpRight />
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
              <span>좋아한 구간 남기기</span>
            </button>
            <LikeButton model={m} />
          </div>
          <div className="desktop-audio-options">
            <label>
              <Volume2 />
              <span className="sr-only">볼륨</span>
              <input
                type="range"
                min={0}
                max={1}
                step={0.05}
                value={m.volume}
                onChange={(e) => m.setVolume(Number(e.target.value))}
              />
            </label>
            <button
              className="icon-button"
              aria-label="재생 목록 열기"
              onClick={() => m.setQueueDialog(true)}
            >
              <ListMusic />
            </button>
          </div>
        </section>
        <aside className="desktop-playlists today-moments" aria-label="오늘의 Moment 추천">
          <header>
            <div><p>오늘, 마음에 남을 음악의 순간</p><h2>오늘의 Moment<span>.</span></h2></div>
            <button className="icon-button" aria-label="추천 음악 더 둘러보기" onClick={() => m.go('search')}><ArrowUpRight /></button>
          </header>
          <p className="today-moment-note">{selectedMoods.length || selectedGenres.length ? '선택한 취향의 곡부터, 새롭게 발견할 음악까지 만나보세요.' : '오늘 들어볼 음악을 골랐어요.'}</p>
          <div className="desktop-playlist-list">
            {todayMoments.map(track => {
              const section = getSongSectionAt(track, track.moment);
              const playing = m.active.id === track.id && m.playing && m.time >= section.start && m.time < section.end;
              return <article className={`desktop-playlist-row ${playing ? 'is-current' : ''}`} key={track.id}>
                <button className="desktop-playlist-open" aria-label={`${track.title} ${formatTime(section.start)} 추천 구간 듣기`} onClick={() => m.playSavedMoment({id: track.id, track, start: section.start, end: section.end, section: section.label})}>
                  <AlbumArt track={track} />
                  <span><strong>{track.title}</strong><small>{track.artist}</small></span>
                </button>
                <button className="icon-button" aria-label={`${track.title} 추천 구간 ${playing ? '일시정지' : '재생'}`} onClick={() => playing ? m.play() : m.playSavedMoment({id: track.id, track, start: section.start, end: section.end, section: section.label})}>{playing ? <Pause fill="currentColor" /> : <Play fill="currentColor" />}</button>
              </article>;
            })}
            {!todayMoments.length && <p className="desktop-section-empty">지금 추천할 수 있는 곡이 없어요. 탐색에서 다른 음악을 만나보세요.</p>}
          </div>
          <button className="desktop-saved-link" onClick={() => {m.setLibraryTab('moments'); m.go('library');}}>
            <Heart /><span>좋아한 구간 <strong>{m.moments.length}</strong></span><ChevronRight />
          </button>
        </aside>
      </div>
      <div className="desktop-home-bottom">
        <form
          className="desktop-search"
          onSubmit={(e) => {
            e.preventDefault();
            m.go('search');
          }}
        >
          <Search aria-hidden="true" />
          <input
            aria-label="음악 검색"
            placeholder="다음으로 듣고 싶은 곡, 아티스트"
            value={m.query}
            onChange={(e) => m.setQuery(e.target.value)}
          />
          <button
            type="submit"
            className="icon-button"
            aria-label="음악 탐색하기"
          >
            <ArrowUpRight />
          </button>
        </form>
        <button className="desktop-ai-entry" onClick={m.openChat}>
          <MupidMascot size={64} />
          <span>
            <small>이 곡, 조금 다른 느낌으로</small>
            <strong>
              Mupid AI와 이어듣기 <ArrowUpRight />
            </strong>
          </span>
        </button>
      </div>
      <div className="desktop-home-shelves">
        <section className="desktop-recommendations">
          <header>
            <div>
              <span className="eyebrow">FOR YOU</span>
              <h2>취향에 이어지는 음악</h2>
            </div>
            <button className="text-link" onClick={() => m.go('search')}>
              더 둘러보기 <ArrowUpRight />
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
              <span className="eyebrow">PLAY IT AGAIN</span>
              <h2>최근 들은 음악</h2>
            </div>
            <button className="text-link" onClick={() => m.go('profile')}>
              감상 기록 <ArrowUpRight />
            </button>
          </header>
          <div className="desktop-recent-list">
            {recent.map((track) => (
              <TrackRow key={track.id} track={track} model={m} />
            ))}
          </div>
          {!recent.length && (
            <p className="desktop-section-empty">
              음악을 들으면 여기에 모아둘게요.
            </p>
          )}
        </section>
      </div>
      <footer className="desktop-home-footer">
        <span>Music for your sky.</span>
        <button className="text-link" onClick={m.openChat}>
          <Sparkles /> Mupid AI
        </button>
      </footer>
    </div>
  );
}
