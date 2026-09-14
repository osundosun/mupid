'use client';
import { VinylRecord } from './vinyl-record';
import { Lyrics } from './lyrics';
import { TrackMenu } from './track-menu';
import { TrackComments } from './track-comments';
import { MupidMascot } from './mascot';
import { useState } from 'react';
import {
  Check,
  Bookmark,
  BookmarkMinus,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  ListMusic,
  Heart,
  Play,
  Search,
  Sparkles,
  X,
} from 'lucide-react';
import { formatTime, getSongSectionAt } from '@/lib/mupid-data';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import type { MupidModel } from '@/hooks/use-mupid';
import {
  EmptyState,
  LikeButton,
  PageHeader,
  TrackRow,
  Waveform,
} from './primitives';

export function PlayerView({ model: m }: { model: MupidModel }) {
  // 들을 곡이 없으면 이 화면은 보여 줄 것이 없다. 앱의 다른 화면은 그대로 쓴다.
  const active = m.active;
  if (!active) return <EmptyState title="아직 들을 곡이 없어요" description="음악이 등록되면 여기에서 바로 들을 수 있어요." />;
  const part = getSongSectionAt(active, m.time || active.moment);
  // 지금 재생 중인 순간이 이미 저장돼 있는지 본다. 저장했다면 여기서 바로 해제할 수 있다.
  const savedMoment = m.moments.find(
    (v) => v.track.id === active.id && v.start === part.start && v.end === part.end,
  );
  const momentSaved = !!savedMoment;
  const [confirmUnsave, setConfirmUnsave] = useState(false);
  return (
    <div className="player-page">
      <header className="player-heading">
        <button className="icon-button" aria-label="뒤로 가기" onClick={m.goBack}>
          <ChevronLeft />
        </button>
        <div>
          <h1>{active.title}</h1>
          <p>{active.artist}</p>
        </div>
        <TrackMenu model={m} />
      </header>
      <div className="player-body">
        <div className="player-art-area">
          <VinylRecord
            playing={m.playing}
            track={active}
            unoptimized
            className="vinyl-record"
            src="/sky-vinyl.webp"
            alt="하늘을 담은 검은 LP 레코드"
            width="440"
            height="440"
          />
          <Lyrics track={active} />
        </div>
        <div className="player-detail">
          <div className="section-pill">
            {part.label} · {formatTime(part.start)} – {formatTime(part.end)}
          </div>
          <TrackComments key={active.id} model={m} />
          <div className="player-actions">
            <button
              className="secondary-button"
              aria-pressed={momentSaved}
              onClick={() => {
                if (momentSaved) { setConfirmUnsave(true); return; }
                m.setMoments((list) => [
                  { id: Date.now(), track: active, start: part.start, end: part.end, section: part.label },
                  ...list,
                ]);
                m.setToast(`${part.label} 순간을 저장했어요.`);
              }}
            >
              {/* '저장됨'은 상태로만 읽혀 누를 수 있는지 알 수 없다. 누르면 무슨 일이 일어나는지 적는다. */}
              {momentSaved ? <BookmarkMinus /> : <Bookmark />}
              {momentSaved ? '저장 취소' : '이 순간 저장'}
            </button>
            <button className="secondary-button" onClick={() => { m.setLibraryTab('queue'); m.go('library'); }}><ListMusic />재생목록</button>
            <button className="secondary-button" aria-pressed={m.liked.includes(active.id)} aria-label={m.liked.includes(active.id) ? '좋아요 취소' : '좋아요'} onClick={() => m.toggleLike(active.id)}>
              <Heart className="pink" fill={m.liked.includes(active.id) ? 'currentColor' : 'none'} />
              좋아요
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
              <p className="album-name">{active.album}</p>
              <span className="eyebrow" lang="en">WHY THIS MOMENT</span>
              <p>저장한 순간의 분위기를 따라 새로운 음악을 만나보세요.</p>
              <div className="tags">
                {active.tags.map((t) => (
                  <span key={t}>{t}</span>
                ))}
              </div>
            </div>
            <button className="text-link" onClick={m.openChat}>
              <Sparkles />AI 기능 <ChevronRight />
            </button>
          </details>
        </div>
      </div>
      {/* 저장은 한 번 더 물어보고 푼다. 보관함의 순간 삭제와 같은 규칙. */}
      <AlertDialog open={confirmUnsave} onOpenChange={(open) => { if (!open) setConfirmUnsave(false); }}>
        <AlertDialogContent className="sky-dialog">
          <AlertDialogHeader>
            <AlertDialogTitle>저장한 순간을 뺄까요?</AlertDialogTitle>
            <AlertDialogDescription>
              {part.label} · {formatTime(part.start)} – {formatTime(part.end)} 순간만 지워지고, 곡은 그대로예요.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                m.setMoments((list) => list.filter((v) => v.id !== savedMoment?.id));
                m.setToast('저장한 순간을 뺐어요.');
                setConfirmUnsave(false);
              }}
            >
              빼기
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
export function MomentView({ model: m }: { model: MupidModel }) {
  return <MomentSelection key={m.active?.id ?? 0} model={m} />;
}
function MomentSelection({ model: m }: { model: MupidModel }) {
  // 들을 곡이 없으면 이 화면은 보여 줄 것이 없다. 앱의 다른 화면은 그대로 쓴다.
  const active = m.active;
  if (!active) return <EmptyState title="아직 들을 곡이 없어요" description="음악이 등록되면 여기에서 바로 들을 수 있어요." />;
  const editing = m.editingMomentId !== null;
  // 수정으로 들어왔으면 그 순간을 미리 골라 둔다.
  const [selected, setSelected] = useState<number[]>(() => {
    if (!editing) return [];
    const index = active.sections.findIndex(
      (s) => s.start === m.momentRange[0] && s.end === m.momentRange[1],
    );
    return index < 0 ? [] : [index];
  });
  const [saved, setSaved] = useState(false);
  const parts = active.sections.filter((_, i) => selected.includes(i));
  return <div className="content-page moment-page">
    <PageHeader
      title={editing ? '순간 수정' : '저장한 순간'}
      onBack={() => { m.setEditingMomentId(null); m.go(editing ? 'library' : 'player'); }}
      aside={<LikeButton model={m} />}
    />
    <div className="moment-layout">
      <div>
        <div className="intro-copy">
          <h2>{editing ? '어느 순간으로 바꿀까요?' : '마음에 드는 순간을 저장하세요.'}</h2>
          <p>{editing ? '새로 고른 순간이 기존 순간을 대신해요.' : '인트로·벌스·코러스 등을 여러 개 선택할 수 있어요.'}</p>
        </div>
        <TrackRow track={active} model={m} detail={parts.length ? parts.map(p => p.label).join(' · ') : '저장할 순간을 선택해 주세요'} />
        <div className="moment-editor">
          <div className="song-parts" aria-label="저장할 순간 다중 선택">
            {active.sections.map((part, i) => <button key={i} aria-pressed={selected.includes(i)} className={selected.includes(i) ? 'selected' : ''} onClick={() => {
              setSelected(ids => ids.includes(i) ? ids.filter(id => id !== i) : [...ids, i]);
              setSaved(false);
            }}>{part.label}</button>)}
          </div>
          <Waveform duration={active.duration} ranges={parts} />
          <div className="time-labels"><span>00:00</span><span>{formatTime(active.duration)}</span></div>
          {/* 고른 순간은 여기서 바로 빼는 게 빠르다. 위 칩을 다시 찾아 누를 필요가 없다. */}
          <div className="track-menu-options moment-part-list">
            {parts.map(part => {
              const index = active.sections.indexOf(part);
              return <div className="moment-part-row" key={part.start}>
                <button onClick={() => m.playSavedMoment({ id: part.start, track: active, start: part.start, end: part.end, section: part.label })} aria-label={part.label + ' 미리듣기'}>
                  <Play /><span>{part.label}</span><small>{formatTime(part.start)} – {formatTime(part.end)}</small>
                </button>
                <button
                  className="icon-button moment-part-remove"
                  aria-label={part.label + ' 선택 해제'}
                  onClick={() => { setSelected(ids => ids.filter(id => id !== index)); setSaved(false); }}
                >
                  <X />
                </button>
              </div>;
            })}
          </div>
        </div>
      </div>
      <div className="moment-story">
        <h2>{parts.length}개의 순간 선택</h2>
        <p>{editing ? '한 순간만 골라 주세요. 기존에 저장한 순간을 이걸로 바꿔요.' : <>선택한 부분은 각각 따로 저장돼요.<br />선택하지 않은 부분은 포함하지 않아요.</>}</p>
        <button className="primary-button wide" disabled={!parts.length || saved} onClick={() => {
          if (editing) {
            const part = parts[0];
            m.setMoments(list => list.map(v => v.id === m.editingMomentId
              ? { ...v, start: part.start, end: part.end, section: part.label }
              : v));
            m.setToast('저장한 순간을 바꿨어요.'); setSaved(true);
            return;
          }
          const entries = parts.map((p, i) => ({ id: Date.now() + i, track: active, start: p.start, end: p.end, section: p.label }));
          m.setMoments(list => [...entries.filter(e => !list.some(v => v.track.id === e.track.id && v.start === e.start && v.end === e.end)), ...list]);
          m.setToast('선택한 순간을 저장했어요.'); setSaved(true);
        }}>{saved ? <Check /> : <Bookmark />}{saved ? (editing ? '수정 완료' : '저장 완료') : (editing ? '이 순간으로 바꾸기' : '선택한 순간 저장하기')}</button>
        <button className="text-link" onClick={() => { m.setLibraryTab('moments'); m.go('library'); }}>저장한 순간 모두 보기 <ChevronRight /></button>
      </div>
    </div>
  </div>;
}
export { SearchView } from './explore';
export function SimilarView({ model: m }: { model: MupidModel }) {
  // 들을 곡이 없으면 이 화면은 보여 줄 것이 없다. 앱의 다른 화면은 그대로 쓴다.
  const active = m.active;
  if (!active) return <EmptyState title="아직 들을 곡이 없어요" description="음악이 등록되면 여기에서 바로 들을 수 있어요." />;
  const tracks = m.tracks;
  const pool = tracks.filter(
    (t) => t.id !== active.id && !m.ignored.includes(t.id),
  );
  const results = m.showAllSimilar ? pool : pool.slice(0, 4);
  return (
    <div className="content-page similar-page">
      <PageHeader title="비슷한 순간 추천" onBack={() => m.go('moment')} />
      <p className="eyebrow source-label">이 순간에서 시작했어요</p>
      <TrackRow model={m} track={active} />
      <div className="intro-copy">
        <h2>이런 순간은 어때요?</h2>
        <p>저장한 순간의 분위기를 이어가 보세요.</p>
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
                    m.ignoreTrack(t.id);
                    m.setToast('앞으로 이 곡은 추천에서 숨길게요.');
                  }}
                >
                  <X />
                </button>
              }
            />
          </div>
        ))}
      </div>
      {!results.length && (
        <EmptyState
          title="잠시 모든 음악을 둘러봤어요"
          description="숨긴 곡을 다시 보여드릴까요?"
        >
          <button className="secondary-button" onClick={() => m.clearIgnored()}>
            추천 목록 초기화
          </button>
        </EmptyState>
      )}
      {!m.showAllSimilar && pool.length > 4 && (
        <button
          className="secondary-button wide load-more"
          onClick={() => m.setShowAllSimilar(true)}
        >
          더 많은 순간 추천 받기 <ChevronRight />
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
