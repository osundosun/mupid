'use client';
import { useRef, useState, type CSSProperties } from 'react';
import {
  ArrowDown,
  ArrowUp,
  ArrowUpRight,
  Disc3,
  List,
  Pause,
  Play,
  Search,
  X,
} from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
import { moodOptions } from '@/lib/mupid-data';
import { filterExploreTracks, getExploreWindow } from '@/lib/explore';
import { PlaybackStatus } from './playback-status';
import { FadeContent } from './fade-content';
import { MobileAlbumDiscovery } from './mobile-album-discovery';
import {
  AlbumArt,
  EmptyState,
  LikeButton,
  PageHeader,
  TrackRow,
} from './primitives';

export function SearchView({ model: m }: { model: MupidModel }) {
  const tracks = m.tracks;
  const [focusedId, setFocusedId] = useState<number | null>(m.active.id);
  const [listView, setListView] = useState(false);
  const touchStart = useRef<{ x: number; y: number } | null>(null);
  const results = filterExploreTracks(tracks, m.query, m.moodFilter, m.ignored);
  const selectedIndex = Math.max(
    0,
    results.findIndex((t) => t.id === focusedId),
  );
  const selected = results[selectedIndex];
  const playing = selected && m.active.id === selected.id && m.playing;
  const visibleAlbums = getExploreWindow(results, selectedIndex);
  const mobilePage = Math.floor(selectedIndex / 6);
  const mobileTracks = results.slice(mobilePage * 6, mobilePage * 6 + 6);
  const move = (direction: number) => {
    if (results.length > 1)
      setFocusedId(
        results[(selectedIndex + direction + results.length) % results.length]
          .id,
      );
  };

  return (
    <div className="content-page explore-page">
      <PageHeader title="탐색" onBack={() => m.go('home')} />
      <div className="explore-toolbar">
        <form className="search-box" onSubmit={(e) => e.preventDefault()}>
          <Search />
          <input
            aria-label="곡 또는 아티스트 검색"
            placeholder="곡, 아티스트, 분위기 검색"
            value={m.query}
            onChange={(e) => {
              m.setQuery(e.target.value);
              setFocusedId(null);
            }}
          />
          {m.query && (
            <button
              type="button"
              aria-label="검색어 지우기"
              className="icon-button"
              onClick={() => {
                m.setQuery('');
                setFocusedId(null);
              }}
            >
              <X />
            </button>
          )}
        </form>
        <button
          className="explore-view-toggle"
          aria-pressed={listView}
          aria-label={listView ? '앨범으로 둘러보기' : '전체 곡 목록 보기'}
          onClick={() => setListView(!listView)}
        >
          {listView ? <Disc3 /> : <List />}
          <span>{listView ? '앨범 보기' : '목록 보기'}</span>
        </button>
      </div>
      <div className="filter-chips explore-filters" aria-label="분위기 필터">
        {['전체', ...moodOptions].map((mood) => (
          <button
            className={m.moodFilter === mood ? 'selected' : ''}
            aria-pressed={m.moodFilter === mood}
            key={mood}
            onClick={() => {
              m.setMoodFilter(mood);
              setFocusedId(null);
            }}
          >
            {mood}
          </button>
        ))}
      </div>
      <FadeContent contentKey={`${mobilePage}-${m.moodFilter}-${m.query}-${listView}`}>
      {selected ? (
        listView ? (
          <section className="explore-list">
            <div className="explore-list-heading">
              <h2>{m.query.trim() ? '검색 결과' : '모든 음악'}</h2>
              <span>{results.length}곡</span>
            </div>
            <div className="track-list">
              {results.map((track) => (
                <TrackRow
                  key={track.id}
                  track={track}
                  model={m}
                  action={<LikeButton model={m} track={track} />}
                />
              ))}
            </div>
          </section>
        ) : (
          <div className="explore-stage">
            <MobileAlbumDiscovery tracks={results} model={m} />
            <section className="explore-selection" aria-label="선택한 곡">
              <span className="eyebrow">지금 고른 음악</span>
              <div className="explore-feature-art">
                <AlbumArt track={selected} />
              </div>
              <div className="explore-selection-copy">
                <p className="explore-genre">
                  {selected.genre} <span>·</span> {selected.mood}
                </p>
                <h2>{selected.title}</h2>
                <p className="explore-artist">
                  {selected.artist} <span>— {selected.album}</span>
                </p>
                <p className="explore-reason">{selected.reason}</p>
                <div className="explore-actions">
                  <button
                    className="primary-button"
                    onClick={() => m.play(selected)}
                    aria-label={`${selected.title} ${playing ? '일시정지' : '재생'}`}
                  >
                    {playing ? (
                      <Pause fill="currentColor" />
                    ) : (
                      <Play fill="currentColor" />
                    )}
                    {playing ? '일시정지' : '재생'}
                  </button>
                  <LikeButton model={m} track={selected} />
                  <button
                    className="text-link"
                    onClick={() => m.openTrack(selected)}
                  >
                    곡 자세히 <ArrowUpRight />
                  </button>
                </div>
              </div>
            </section>
            <section className="explore-mobile-list" aria-label="추천 음악 둘러보기">
              <div className="explore-list-heading">
                <h2>다음으로 듣기</h2>
                <span>{results.length}곡</span>
              </div>
              <div className="track-list">
                {mobileTracks.map((track) => (
                  <TrackRow key={track.id} track={track} model={m}
                    onPlay={() => { setFocusedId(track.id); m.play(track); }}
                    action={<span className="explore-status-slot">{m.active.id === track.id && m.playing ? <PlaybackStatus playing /> : null}</span>} />
                ))}
              </div>
              {results.length > 6 && (
                <div className="explore-mobile-pagination">
                  <button className="icon-button" aria-label="이전 6곡" disabled={mobilePage === 0}
                    onClick={() => setFocusedId(results[(mobilePage - 1) * 6].id)}><ArrowUp /></button>
                  <span>{mobilePage + 1} / {Math.ceil(results.length / 6)}</span>
                  <button className="icon-button" aria-label="다음 6곡" disabled={(mobilePage + 1) * 6 >= results.length}
                    onClick={() => setFocusedId(results[(mobilePage + 1) * 6].id)}><ArrowDown /></button>
                </div>
              )}
            </section>
            <section className="explore-orbit" aria-label="앨범 둘러보기">
              <div className="explore-orbit-heading">
                <span>{m.query.trim() ? '검색 결과' : '다음으로 듣기'}</span>
                <span aria-live="polite" aria-atomic="true">
                  {selectedIndex + 1} / {results.length}
                  <span className="sr-only">
                    {' '}
                    · {selected.title}, {selected.artist}
                  </span>
                </span>
              </div>
              <div
                className="explore-orbit-window"
                style={
                  {
                    '--orbit-count': visibleAlbums.length,
                    '--orbit-before': Math.max(
                      0,
                      -(visibleAlbums[0]?.offset ?? 0),
                    ),
                  } as CSSProperties
                }
                onTouchStart={(e) => {
                  const touch = e.touches[0];
                  touchStart.current = { x: touch.clientX, y: touch.clientY };
                }}
                onTouchCancel={() => {
                  touchStart.current = null;
                }}
                onTouchEnd={(e) => {
                  const start = touchStart.current;
                  touchStart.current = null;
                  if (!start) return;
                  const touch = e.changedTouches[0];
                  const dx = touch.clientX - start.x;
                  const dy = touch.clientY - start.y;
                  if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy) * 1.5)
                    move(dx < 0 ? 1 : -1);
                }}
              >
                <fieldset className="explore-orbit-items">
                  <legend className="sr-only">앨범 선택</legend>
                  {visibleAlbums.map(({ item: track, offset }) => (
                    <div
                      key={track.id}
                      className={`explore-orbit-item ${offset === 0 ? 'is-selected' : ''}`}
                      data-distance={Math.abs(offset)}
                      style={
                        {
                          '--offset': offset,
                          '--direction': -Math.sign(offset),
                        } as CSSProperties
                      }
                    >
                      <button
                        className="explore-track-pick"
                        aria-pressed={offset === 0}
                        aria-label={`${track.title}, ${track.artist} 선택`}
                        title={`${track.title} · ${track.artist}`}
                        onClick={() => setFocusedId(track.id)}
                      >
                        <AlbumArt track={track} />
                        <span className="explore-track-copy">
                          <strong>{track.title}</strong>
                          <small>{track.artist}</small>
                        </span>
                      </button>
                      {offset === 0 && (
                        <button
                          className="explore-orbit-play"
                          aria-label={`${track.title} ${playing ? '일시정지' : '재생'}`}
                          onClick={() => m.play(track)}
                        >
                          {playing ? '일시정지' : '재생'}
                        </button>
                      )}
                    </div>
                  ))}
                </fieldset>
              </div>
              <div className="explore-orbit-footer">
                <span>앨범을 골라 들어보세요</span>
                <div>
                  <button
                    className="icon-button"
                    aria-label="이전 앨범"
                    disabled={results.length < 2}
                    onClick={() => move(-1)}
                  >
                    <ArrowUp />
                  </button>
                  <button
                    className="icon-button"
                    aria-label="다음 앨범"
                    disabled={results.length < 2}
                    onClick={() => move(1)}
                  >
                    <ArrowDown />
                  </button>
                </div>
              </div>
            </section>
          </div>
        )
      ) : (
        <EmptyState
          title="검색 결과가 없어요"
          description="검색어나 분위기를 바꿔보세요."
        >
          <button
            className="secondary-button"
            onClick={() => {
              m.setQuery('');
              m.setMoodFilter('전체');
              setFocusedId(null);
            }}
          >
            전체 음악 보기
          </button>
        </EmptyState>
      )}
      </FadeContent>
    </div>
  );
}
