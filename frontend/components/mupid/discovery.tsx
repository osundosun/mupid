'use client';
import { VinylRecord } from './vinyl-record';
import { Lyrics } from './lyrics';
import { TrackComments } from './track-comments';
import { MupidMascot } from './mascot';
import { useState } from 'react';
import {
  Check,
  ChevronDown,
  ChevronRight,
  EllipsisVertical,
  Heart,
  Play,
  Search,
  Sparkles,
  X,
} from 'lucide-react';
import { Slider } from '@/components/ui/slider';
import { formatTime, getSongSectionAt } from '@/lib/mupid-data';
import type { MupidModel } from '@/hooks/use-mupid';
import {
  EmptyState,
  LikeButton,
  PageHeader,
  PlayerControls,
  TrackRow,
  Waveform,
} from './primitives';

export function PlayerView({ model: m }: { model: MupidModel }) {
  const part = getSongSectionAt(m.active, m.time || m.active.moment);
  return (
    <div className="player-page">
      <header className="player-heading">
        <button
          className="icon-button"
          aria-label="플레이어 닫고 홈으로"
          onClick={() => m.go('home')}
        >
          <ChevronDown />
        </button>
        <div>
          <h1>{m.active.title}</h1>
          <p>{m.active.artist}</p>
        </div>
        <button
          className="icon-button"
          aria-label="재생 목록"
          onClick={() => m.setQueueDialog(true)}
        >
          <EllipsisVertical />
        </button>
      </header>
      <div className="player-body">
        <div className="player-art-area">
          <VinylRecord
            playing={m.playing}
            track={m.active}
            unoptimized
            className="vinyl-record"
            src="/sky-vinyl.webp"
            alt="하늘을 담은 검은 LP 레코드"
            width="440"
            height="440"
          />
        </div>
        <div className="player-detail">
          <div className="section-pill">
            {part.label} · {formatTime(part.start)} – {formatTime(part.end)}
          </div>
          <TrackComments key={m.active.id} model={m} />
          <Lyrics track={m.active} />
          <div className="player-actions">
            <button className="secondary-button" onClick={() => m.openMoment()}>
              <Heart className="pink" />
              좋아한 구간
            </button>
            <button
              className="secondary-button"
              onClick={() => m.go('similar')}
            >
              <Search />
              파생곡 찾기
            </button>
          </div>
          <details className="player-insights">
            <summary>
              이 곡이 추천된 이유 <ChevronDown />
            </summary>
            <div className="player-reason">
              <p className="album-name">{m.active.album}</p>
              <span className="eyebrow">WHY THIS MOMENT</span>
              <h3>{m.active.reason}</h3>
              <p>좋아한 순간의 분위기를 따라 새로운 음악을 만나보세요.</p>
              <div className="tags">
                {m.active.tags.map((t) => (
                  <span key={t}>{t}</span>
                ))}
              </div>
            </div>
            <button className="text-link" onClick={m.openChat}>
              <Sparkles />이 곡을 조금 더 잔잔하게 <ChevronRight />
            </button>
          </details>
        </div>
      </div>
    </div>
  );
}
export function MomentView({ model: m }: { model: MupidModel }) {
  const [saved, setSaved] = useState(false);
  return (
    <div className="content-page moment-page">
      <PageHeader
        title="좋아한 구간"
        onBack={() => m.go('player')}
        aside={<LikeButton model={m} />}
      />
      <div className="moment-layout">
        <div>
          <div className="intro-copy">
            <h2>좋아한 순간을 남겨요.</h2>
            <p>구간을 고르고, 나만의 취향으로 저장하세요.</p>
          </div>
          <TrackRow
            track={m.active}
            model={m}
            detail={`${m.momentSection} · ${formatTime(m.momentRange[0])} – ${formatTime(m.momentRange[1])}`}
          />
          <div className="moment-editor">
            <div className="song-parts" aria-label="음악 구간 선택">
              {m.active.sections.map((part, i) => (
                <button
                  className={m.momentSection === part.label ? 'selected' : ''}
                  key={i}
                  onClick={() => {
                    m.setMomentSection(part.label);
                    m.setMomentRange([part.start, part.end]);
                    setSaved(false);
                  }}
                >
                  {part.label}
                </button>
              ))}
            </div>
            <Waveform
              duration={m.active.duration}
              start={m.momentRange[0]}
              end={m.momentRange[1]}
            />
            <Slider
              aria-label="좋아한 구간 시작과 끝 조절"
              className="moment-slider"
              value={m.momentRange}
              min={0}
              max={m.active.duration}
              minStepsBetweenValues={1}
              step={1}
              thumbCollisionBehavior="none"
              onValueChange={(value) => {
                m.setMomentRange(value as number[]);
                m.setMomentSection('직접 선택');
                setSaved(false);
              }}
            />
            <div className="time-labels">
              <span>00:00</span>
              <span>{formatTime(m.active.duration)}</span>
            </div>
            <details className="moment-adjustments">
              <summary>시작·종료 시간 직접 조정</summary>
              <div className="range-fields">
                <label>
                  시작{' '}
                  <span>
                    <input
                      aria-label="구간 시작 시간(초)"
                      type="number"
                      min={0}
                      max={m.momentRange[1] - 1}
                      value={m.momentRange[0]}
                      onChange={(e) => {
                        const n = Number(e.target.value);
                        m.setMomentRange([
                          Math.max(0, Math.min(n, m.momentRange[1] - 1)),
                          m.momentRange[1],
                        ]);
                        m.setMomentSection('직접 선택');
                        setSaved(false);
                      }}
                    />
                    초
                  </span>
                </label>
                <span className="range-separator">—</span>
                <label>
                  종료{' '}
                  <span>
                    <input
                      aria-label="구간 종료 시간(초)"
                      type="number"
                      min={m.momentRange[0] + 1}
                      max={m.active.duration}
                      value={m.momentRange[1]}
                      onChange={(e) => {
                        const n = Number(e.target.value);
                        m.setMomentRange([
                          m.momentRange[0],
                          Math.min(
                            m.active.duration,
                            Math.max(m.momentRange[0] + 1, n),
                          ),
                        ]);
                        m.setMomentSection('직접 선택');
                        setSaved(false);
                      }}
                    />
                    초
                  </span>
                </label>
                <button
                  className="icon-button"
                  aria-label="선택 구간 미리듣기"
                  onClick={m.previewMoment}
                >
                  <Play />
                </button>
              </div>
            </details>
          </div>
        </div>
        <div className="moment-story">
          <span className="heart-badge">
            <Heart fill="currentColor" />
          </span>
          <h2>이 순간, 마음에 들어요.</h2>
          <p>
            {formatTime(m.momentRange[0])}부터 {formatTime(m.momentRange[1])}
            까지
            <br />
            당신의 취향을 한 겹 더 기록해요.
          </p>
          <div className="tags">
            {m.active.tags.map((tag) => (
              <span key={tag}>{tag}</span>
            ))}
          </div>
          <button
            className="primary-button wide"
            onClick={() => {
              m.saveMoment();
              setSaved(true);
            }}
          >
            {saved ? <Check /> : <Heart />}
            {saved ? '좋아한 구간에 저장했어요' : '이 구간 저장하기'}
          </button>
          <button
            className="secondary-button wide"
            onClick={() => m.go('similar')}
          >
            <Sparkles />이 구간으로 파생곡 찾기
          </button>
          <button
            className="text-link"
            onClick={() => {
              m.setLibraryTab('moments');
              m.go('library');
            }}
          >
            저장한 구간 모두 보기 <ChevronRight />
          </button>
        </div>
      </div>
    </div>
  );
}
export { SearchView } from './explore';
export function SimilarView({ model: m }: { model: MupidModel }) {
  const tracks = m.tracks;
  const pool = tracks.filter(
    (t) => t.id !== m.active.id && !m.ignored.includes(t.id),
  );
  const results = m.showAllSimilar ? pool : pool.slice(0, 4);
  return (
    <div className="content-page similar-page">
      <PageHeader title="비슷한 구간 추천" onBack={() => m.go('moment')} />
      <p className="eyebrow source-label">이 순간에서 시작했어요</p>
      <TrackRow model={m} track={m.active} />
      <div className="intro-copy">
        <h2>이런 구간은 어때요?</h2>
        <p>좋아한 순간의 분위기를 이어가 보세요.</p>
      </div>
      <div className="track-list">
        {results.map((t) => (
          <div key={t.id}>
            <TrackRow
              track={t}
              model={m}
              action={
                <button
                  className="icon-button dismiss-track"
                  aria-label={`${t.title} 관심 없음`}
                  onClick={() => {
                    m.setIgnored((list) => [...list, t.id]);
                    m.setToast('앞으로 이 곡은 추천에서 숨길게요.');
                  }}
                >
                  <X />
                </button>
              }
            />
            <p className="recommendation-reason">{t.reason}</p>
          </div>
        ))}
      </div>
      {!results.length && (
        <EmptyState
          title="잠시 모든 음악을 둘러봤어요"
          description="숨긴 곡을 다시 보여드릴까요?"
        >
          <button className="secondary-button" onClick={() => m.setIgnored([])}>
            추천 목록 초기화
          </button>
        </EmptyState>
      )}
      {!m.showAllSimilar && pool.length > 4 && (
        <button
          className="secondary-button wide load-more"
          onClick={() => m.setShowAllSimilar(true)}
        >
          더 많은 구간 추천 받기 <ChevronRight />
        </button>
      )}
      <button className="ai-prompt-card" onClick={m.openChat}>
        <MupidMascot size={70} />
        <span>
          <strong>조금 다른 느낌을 찾고 있나요?</strong>
          <small>Mupid AI에게 원하는 분위기를 말해보세요.</small>
        </span>
        <ChevronRight />
      </button>
    </div>
  );
}
