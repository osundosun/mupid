'use client';
import { VinylRecord } from './vinyl-record';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { Lyrics } from './lyrics';
import { TrackMenu } from './track-menu';
import { TrackInfoDialog } from './track-info-dialog';
import { TrackComments } from './track-comments';
import { MomentWaveEditor } from './moment-wave-editor';
import { MupidMascot } from './mascot';
import { useEffect, useState } from 'react';
import {
  Check,
  ListMusic,
  Bookmark,
  BookmarkMinus,
  ChevronLeft,
  ChevronRight,
  ThumbsDown,
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
import type { Track } from '@/lib/mupid-data';
import {
  EmptyState,
  LikeButton,
  PageHeader,
  PlayerControls,
  TrackRow,
  Waveform,
} from './primitives';

export function PlayerView({ model: m }: { model: MupidModel }) {
  const [confirmUnsave, setConfirmUnsave] = useState(false);
  const [trackInfoOpen, setTrackInfoOpen] = useState(false);
  const [reasonOpen, setReasonOpen] = useState(false);
  const [lyricsOpen, setLyricsOpen] = useState(false);
  // 들을 곡이 없으면 이 화면은 보여 줄 것이 없다. 앱의 다른 화면은 그대로 쓴다.
  const active = m.active;
  if (!active) return <EmptyState title="아직 들을 곡이 없어요" description="음악이 등록되면 여기에서 바로 들을 수 있어요." />;
  const recommendationReason = active.recommended?.reason?.trim() || active.recommendationReason?.trim();
  const part = getSongSectionAt(active, m.time || active.moment);
  // 지금 재생 중인 순간이 이미 저장돼 있는지 본다. 저장했다면 여기서 바로 해제할 수 있다.
  const savedMoment = m.moments.find(
    (v) => v.track.id === active.id && v.start === part.start && v.end === part.end,
  );
  const momentSaved = !!savedMoment;
  return (
    <div className="player-page">
      <div className="desktop-player-page-heading">
        <PageHeader title="플레이어" onBack={m.goBack} aside={<TrackMenu model={m} />} />
      </div>
      <header className="player-heading">
        <button className="icon-button" aria-label="뒤로 가기" onClick={m.goBack}>
          <ChevronLeft />
        </button>
        <div>
          <h1><button type="button" className="player-title-trigger" aria-label={`${active.title} 곡 정보 보기`} aria-haspopup="dialog" onClick={() => setTrackInfoOpen(true)}>{active.title}</button></h1>
          <button className="track-artist-link" onClick={() => m.setSelectedArtist(active.artist)}>{active.artist}</button>
        </div>
        <TrackMenu model={m} />
      </header>
      <div className="player-body">
        <div className="player-art-area">
          <button type="button" className="record-info-trigger" aria-label={`${active.title} 가사 보기`} aria-haspopup="dialog" onClick={() => setLyricsOpen(true)}>
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
          </button>
        </div>
        <div className="player-detail">
          {/* 줄은 `TrackComments` 가 그린다 — 같은 줄에 설 댓글 토글이 그쪽 상태를 쓴다. */}
          <TrackComments key={active.id} model={m}>
            <button className="secondary-button quick-like" aria-pressed={m.liked.includes(active.id)} aria-label={m.liked.includes(active.id) ? '좋아요 취소' : '좋아요'} onClick={() => m.toggleLike(active.id)}>
              <Heart className="pink" fill={m.liked.includes(active.id) ? 'currentColor' : 'none'} />
              좋아요
            </button>
            <button
              className="quick-save"
              aria-label="현재 곡의 저장할 순간 선택하기"
              onClick={() => m.openMoment(active)}
            >
              <Bookmark />
              이 순간 저장
            </button>
            <button className="secondary-button" aria-expanded={m.queueOpen} aria-controls="queue-dock" onClick={m.toggleQueue}><ListMusic />재생목록</button>
          </TrackComments>
          {/*
            추천 이유는 본문에 펼치지 않고 가사·곡 정보처럼 대화상자로 띄운다.
            본문에 펼치면 그 높이만큼 플레이어가 한 화면을 넘겨 스크롤이 생겼다.
          */}
          {recommendationReason && <button
            type="button"
            className="player-recommendation-toggle"
            aria-haspopup="dialog"
            aria-expanded={reasonOpen}
            onClick={() => setReasonOpen(true)}
          >
            <Sparkles size={15} /><span>추천 이유</span><ChevronRight size={16} />
          </button>}
        </div>
      </div>
      <Dialog open={lyricsOpen} onOpenChange={setLyricsOpen}>
        <DialogContent className="sky-dialog player-lyrics-dialog">
          <DialogHeader>
            <DialogTitle>가사</DialogTitle>
            <DialogDescription>{active.title} · {active.artist}</DialogDescription>
          </DialogHeader>
          <Lyrics key={active.id} track={active} time={m.time} expanded onSeek={(seconds) => m.seek(seconds)} />
        </DialogContent>
      </Dialog>
      <TrackInfoDialog track={active} open={trackInfoOpen} onOpenChange={setTrackInfoOpen} onArtist={m.setSelectedArtist} />
      <Dialog open={reasonOpen && !!recommendationReason} onOpenChange={setReasonOpen}>
        <DialogContent className="sky-dialog player-reason-dialog">
          <DialogHeader>
            <DialogTitle><Sparkles size={16} aria-hidden="true" />추천 이유</DialogTitle>
            <DialogDescription>{active.title} · {active.artist}</DialogDescription>
          </DialogHeader>
          <p className="player-reason-text">{recommendationReason}</p>
          {active.recommended && (
            <p className="player-reason-section">
              <span>추천한 구간</span>
              <strong>{active.recommended.sectionLabel} · {formatTime(active.recommended.start)} – {formatTime(active.recommended.end)}</strong>
            </p>
          )}
        </DialogContent>
      </Dialog>
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
                if (savedMoment) m.removeMoment(savedMoment.id);
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
  // Keep preview sections local to the editor; never send them as AI analysis.
  const previewSections = active.sections.length === 0;
  const labels = ['인트로', '벌스 1', '프리코러스', '코러스 1', '벌스 2', '브리지', '파이널 코러스', '아웃트로'];
  const sections = previewSections
    ? labels.map((label, i) => ({ label, start: Math.round(active.duration * i / labels.length), end: Math.round(active.duration * (i + 1) / labels.length) })).filter(part => part.end > part.start)
    : active.sections;
  // 수정으로 들어왔으면 그 순간을 미리 골라 둔다.
  const [selected, setSelected] = useState<number[]>(() => {
    const index = sections.findIndex(
      (s) => s.start === m.momentRange[0] && s.end === m.momentRange[1],
    );
    if (index >= 0) return [index];
    if (editing) {
      const containing = sections.findIndex(s => m.momentRange[0] >= s.start && m.momentRange[0] < s.end);
      return containing >= 0 ? [containing] : [];
    }
    return [];
  });
  const [saved, setSaved] = useState(false);
  const [trimmed, setTrimmed] = useState<Record<number, { start: number; end: number }>>(() => editing && selected.length ? { [selected[0]]: { start: m.momentRange[0], end: m.momentRange[1] } } : {});
  const parts = selected.map(i => ({ ...sections[i], ...trimmed[i] }));
  const focusedPart = parts.at(-1);
  return <div className="content-page moment-page">
    <PageHeader
      title={editing ? '순간 수정' : '이 순간 저장'}
      onBack={() => { m.setEditingMomentId(null); m.go(editing ? 'library' : 'player'); }}
      aside={<LikeButton model={m} />}
    />
    <div className="moment-layout">
      <div>
        <div className="intro-copy">
          <h2>{editing ? '어느 순간으로 바꿀까요?' : '마음에 드는 순간을 저장하세요.'}</h2>
          <p>파형에서 구간을 누르고, 양 끝을 끌어 원하는 부분만 저장하세요.</p>
        </div>
        <TrackRow track={active} model={m} detail={parts.length ? parts.map(p => p.label).join(' · ') : '저장할 순간을 선택해 주세요'} />
        <div className="moment-editor">
          {previewSections && <p className="moment-wave-help">AI 연결 전 미리보기 · 곡 길이를 임시로 나눈 구간이에요.</p>}
          <div className="song-parts" aria-label="저장할 순간 다중 선택">
            {sections.map((part, i) => <button key={i} aria-pressed={selected.includes(i)} className={selected.includes(i) ? 'selected' : ''} onClick={() => {
              setSelected(ids => ids.includes(i) ? ids.filter(id => id !== i) : editing ? [i] : [...ids, i]);
              setSaved(false);
            }}>{part.label}</button>)}
          </div>
          <MomentWaveEditor track={active} range={focusedPart} onSelect={time => {
              const index = sections.findIndex(part => time >= part.start && time < part.end);
              if (index >= 0) setSelected(ids => editing ? [index] : [...ids.filter(id => id !== index), index]);
            setSaved(false);
          }} onTrim={(start, end) => {
            if (selected.length) setTrimmed(value => ({ ...value, [selected[selected.length - 1]]: { start, end } }));
            setSaved(false);
          }} />
          <div className="time-labels"><span>00:00</span><span>{formatTime(active.duration)}</span></div>
          {/* 고른 순간은 여기서 바로 빼는 게 빠르다. 위 칩을 다시 찾아 누를 필요가 없다. */}
          <div className="track-menu-options moment-part-list">
            {parts.map((part, partIndex) => {
              const index = selected[partIndex];
              return <div className="moment-part-row" key={index}>
                <button onClick={() => m.playSavedMoment({ id: part.start, track: active, start: part.start, end: part.end, section: part.label })} aria-label={part.label + ' 미리듣기'}>
                  <Play /><span>{part.label}</span><small>{formatTime(part.start)} – {formatTime(part.end)}</small>
                </button>
                {<button
                  className="icon-button moment-part-remove"
                  aria-label={part.label + ' 선택 해제'}
                  onClick={() => { setSelected(ids => ids.filter(id => id !== index)); setSaved(false); }}
                >
                  <X />
                </button>}
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
            if (m.editingMomentId !== null) {
              m.replaceMoment(m.editingMomentId, { start: part.start, end: part.end, section: part.label });
            }
            m.setToast('저장한 순간을 바꿨어요.'); setSaved(true);
            return;
          }
          m.addMoments(parts.map((p, i) => ({ id: Date.now() + i, track: active, start: p.start, end: p.end, section: p.label })));
          m.setToast('선택한 순간을 저장했어요.'); setSaved(true);
        }}>{saved ? <Check /> : <Bookmark />}{saved ? (editing ? '수정 완료' : '저장 완료') : (editing ? '이 순간으로 바꾸기' : '선택한 순간 저장하기')}</button>
        <button className="text-link" onClick={() => { m.setLibraryTab('moments'); m.go('library'); }}>저장한 순간 모두 보기 <ChevronRight /></button>
      </div>
    </div>
  </div>;
}
export { SearchView } from './explore';
export function SimilarView({ model: m }: { model: MupidModel }) {
  const active = m.active;
  // 지금 듣고 있는 구간이 질문이다. 「이 순간과 닮은 것」이므로 곡이 아니라 구간이다.
  const part = active ? getSongSectionAt(active, m.time || active.moment) : null;
  const musicId = active?.musicId;
  const sectionId = part?.sectionId;
  /**
   * 무엇을 물었는지 답과 함께 들고 있는다.
   *
   * 「물어보는 중」을 따로 저장하지 않는다 — 물은 것과 답이 붙어 있으면 둘을
   * 비교해 알 수 있고, 구간을 옮긴 순간 옛 답이 잠깐 새 구간의 답인 척하는 일도
   * 생기지 않는다.
   */
  const askKey =
    typeof musicId === 'number' && typeof sectionId === 'number'
      ? `${musicId}:${sectionId}`
      : '';
  const [answer, setAnswer] = useState<{ key: string; tracks: Track[] }>({
    key: '',
    tracks: [],
  });
  // 모델 전체가 아니라 이 함수만 의존성에 넣는다. `m` 은 렌더마다 새 객체다.
  const loadSimilarSections = m.loadSimilarSections;

  /**
   * 서버에 닮은 구간을 묻는다.
   *
   * **분석된 구간에만 번호가 있다.** 아직 분석 전인 곡은 임시 구간이라 물어볼
   * 대상이 없고, 그때는 아래에서 카탈로그로 물러난다.
   */
  useEffect(() => {
    if (typeof musicId !== 'number' || typeof sectionId !== 'number') return;
    let alive = true;
    void loadSimilarSections(musicId, sectionId).then((rows) => {
      if (alive) setAnswer({ key: `${musicId}:${sectionId}`, tracks: rows });
    });
    return () => {
      alive = false;
    };
  }, [musicId, sectionId, loadSimilarSections]);

  // 들을 곡이 없으면 이 화면은 보여 줄 것이 없다. 앱의 다른 화면은 그대로 쓴다.
  if (!active) return <EmptyState title="아직 들을 곡이 없어요" description="음악이 등록되면 여기에서 바로 들을 수 있어요." />;
  const answered = answer.key === askKey;
  const asking = Boolean(askKey) && !answered;
  const tracks = m.tracks;
  const served = (answered ? answer.tracks : []).filter(
    (t) => t.id !== active.id && !m.ignored.includes(t.id),
  );
  // 서버가 답을 주면 그것만 쓴다. 카탈로그를 섞으면 어느 줄이 추천인지 알 수 없다.
  const fromServer = served.length > 0;
  const pool = fromServer
    ? served
    : tracks.filter((t) => t.id !== active.id && !m.ignored.includes(t.id));
  const results = m.showAllSimilar ? pool : pool.slice(0, 4);
  return (
    <div className="content-page similar-page">
      <PageHeader title="비슷한 순간 추천" onBack={() => m.go('moment')} />
      <p className="eyebrow source-label">
        {part ? `${part.label} 구간에서 시작했어요` : '이 순간에서 시작했어요'}
      </p>
      <TrackRow model={m} track={active} />
      <div className="intro-copy">
        <h2>이런 순간은 어때요?</h2>
        <p>
          {asking
            ? '닮은 순간을 찾고 있어요.'
            : fromServer
              ? '고른 구간과 소리가 가까운 순간이에요.'
              : '아직 닮은 순간을 찾지 못해, 둘러볼 만한 곡을 모았어요.'}
        </p>
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
    </div>
  );
}
