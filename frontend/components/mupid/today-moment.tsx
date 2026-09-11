'use client';
import { ArrowUpRight, ChevronRight, Heart, Pause, Play } from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
import { formatTime, getSongSectionAt, type Track } from '@/lib/mupid-data';
import { AlbumArt } from './primitives';

// 취향은 제외 조건이 아니라 정렬 기준이다. 데모 곡이 적어도 목록이 비지 않게 채운다.
export function pickTodayMoments(m: MupidModel): Track[] {
  const moods = m.onboardingSubmittedAt ? [...new Set(m.moods)] : [];
  const genres = m.onboardingSubmittedAt ? m.genres : [];
  return m.tracks
    .filter((track) => !m.ignored.includes(track.id))
    .map((track) => ({
      track,
      score: Number(moods.includes(track.mood)) + Number(genres.includes(track.genre)),
    }))
    .sort((a, b) => b.score - a.score)
    .map(({ track }) => track)
    .slice(0, 4);
}

export function todayMomentNote(m: MupidModel) {
  const tuned = m.onboardingSubmittedAt && (m.moods.length || m.genres.length);
  return tuned
    ? '선택한 취향의 곡부터, 새롭게 발견할 음악까지 만나보세요.'
    : '오늘 들어볼 음악을 골랐어요.';
}

// 데스크톱 사이드바와 같은 추천을 모바일 홈에서도 보여 준다.
export function TodayMoment({ model: m }: { model: MupidModel }) {
  const moments = pickTodayMoments(m);
  return (
    <section className="today-moment" aria-label="오늘의 Moment 추천">
      <div className="section-heading today-moment-head">
        {/* 데스크톱 사이드바와 같은 윗줄·제목을 그대로 쓴다. */}
        <div className="section-heading-copy">
          <span className="eyebrow">오늘, 마음에 남을 음악의 순간</span>
          <h2>오늘의 Moment</h2>
        </div>
        <button className="text-link" onClick={() => m.go('search')}>
          더 둘러보기 <ArrowUpRight />
        </button>
      </div>
      <p className="today-moment-note">{todayMomentNote(m)}</p>
      <div className="today-moment-list">
        {moments.map((track) => {
          const section = getSongSectionAt(track, track.moment);
          const playing =
            m.active.id === track.id && m.playing && m.time >= section.start && m.time < section.end;
          const moment = { id: track.id, track, start: section.start, end: section.end, section: section.label };
          return (
            <article className={`today-moment-row ${playing ? 'is-current' : ''}`} key={track.id}>
              <button
                className="today-moment-open"
                aria-label={`${track.title} ${formatTime(section.start)} 추천 순간 듣기`}
                onClick={() => m.playSavedMoment(moment)}
              >
                <AlbumArt track={track} />
                <span>
                  <strong>{track.title}</strong>
                  <small>{track.artist}</small>
                </span>
              </button>
              <button
                className="icon-button"
                aria-label={`${track.title} 추천 순간 ${playing ? '일시정지' : '재생'}`}
                onClick={() => (playing ? m.play() : m.playSavedMoment(moment))}
              >
                {playing ? <Pause fill="currentColor" /> : <Play fill="currentColor" />}
              </button>
            </article>
          );
        })}
        {!moments.length && (
          <p className="today-moment-empty">지금 추천할 수 있는 곡이 없어요. 탐색에서 다른 음악을 만나보세요.</p>
        )}
      </div>
      <button
        className="today-moment-saved"
        onClick={() => { m.setLibraryTab('moments'); m.go('library'); }}
      >
        <Heart /><span>저장한 순간 <strong>{m.moments.length}</strong></span><ChevronRight />
      </button>
    </section>
  );
}
