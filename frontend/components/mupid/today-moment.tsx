'use client';
import { Bookmark, ChevronRight, Pause, Play } from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
import { formatTime, getSongSectionAt, type Track } from '@/lib/mupid-data';
import { RowPlaybackArea, AlbumArt } from './primitives';

/**
 * 오늘의 Moment 에 보여 줄 곡.
 *
 * **서버가 만들어 둔 것이 있으면 그것을 쓴다.** 배치가 매일 정오에 한 번 만들고,
 * 하루 동안 같은 곡이 나온다 — 열 때마다 바뀌면 "오늘의" 가 성립하지 않는다.
 *
 * 없으면 카탈로그를 취향으로 정렬해 채운다. 배치가 아직 안 돌았거나 추천 서버가
 * 준비되지 않았을 수 있고, 그때 홈이 비면 곤란하다. **물러났다는 사실은
 * `todayMomentNote` 가 말한다** — 조용히 대신 채우면 추천이 도는 줄 알고 넘어간다.
 */
export function pickTodayMoments(m: MupidModel): Track[] {
  if (m.todayMoments.length) {
    return m.todayMoments.filter((track) => !m.ignored.includes(track.id)).slice(0, 4);
  }
  const moods = m.onboardingSubmitted ? [...new Set(m.moods)] : [];
  const genres = m.onboardingSubmitted ? m.genres : [];
  return m.tracks
    .filter((track) => !m.ignored.includes(track.id))
    .map((track) => ({
      track,
      score: Number(moods.includes(track.mood)) + Number(genres.includes(track.genre)),
    }))
    .sort((a, b) => b.score - a.score)
    .map(({ track }) => {
      const matched = [moods.includes(track.mood) ? track.mood : '', genres.includes(track.genre) ? track.genre : ''].filter(Boolean);
      return {
        ...track,
        recommendationReason: matched.length
          ? `선택한 취향 ${matched.map(value => `‘${value}’`).join(' · ')}와 곡의 정보가 일치해 골랐어요.`
          : '오늘의 추천을 준비 중이에요.\n먼저 음악 목록의 곡을 소개해요.',
      };
    })
    .slice(0, 4);
}

export function todayMomentNote(m: MupidModel) {
  // 서버가 만든 묶음에는 이유가 함께 온다. 우리가 지어내지 않는다.
  if (m.todayMoments.length && m.todayMomentNote) return m.todayMomentNote;
  if (m.todayMoments.length) return '오늘 들어볼 음악을 골랐어요.';
  const tuned = m.onboardingSubmitted && (m.moods.length || m.genres.length);
  return tuned
    ? '아직 오늘의 추천이 준비되지 않아, 고른 취향으로 먼저 골랐어요.'
    : '아직 오늘의 추천이 준비되지 않아, 둘러볼 만한 곡을 골랐어요.';
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
          더 둘러보기 <ChevronRight />
        </button>
      </div>
      <p className="today-moment-note">{todayMomentNote(m)}</p>
      <div className="today-moment-list">
        {moments.map((track) => {
          const section = getSongSectionAt(track, track.moment);
          const playing =
            m.active?.id === track.id && m.playing;
          return (
            <article data-playable-row className={`today-moment-row ${playing ? 'is-current' : ''}`} key={track.id}>
              <RowPlaybackArea onPlay={() => playing ? m.play() : m.play(track, true)} />
              <button
                className="today-moment-open"
                aria-label={`${track.title} ${formatTime(section.start)} 추천 순간 듣기`}
                onClick={() => m.play(track, true)}
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
                onClick={() => (playing ? m.play() : m.play(track, true))}
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
        <Bookmark /><span>저장한 순간 <strong>{m.moments.length}</strong></span><ChevronRight />
      </button>
    </section>
  );
}
