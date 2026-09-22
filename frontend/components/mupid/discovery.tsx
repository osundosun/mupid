'use client';
import { VinylRecord } from './vinyl-record';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { Lyrics } from './lyrics';
import { VolumeSlider } from './volume-slider';
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
  Volume2,
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
import { useDismissableOverlay } from '@/hooks/use-dismissable-overlay';
import { useIsMobile } from '@/hooks/use-mobile';
import type { Track } from '@/lib/mupid-data';
import {
  EmptyState,
  LikeButton,
  PageHeader,
  PlayerControls,
  TrackRow,
  Waveform,
} from './primitives';

/**
 * 구간 이름의 짧은 설명. 칩에 마우스를 올리면(툴팁) 보인다. '벌스 2'·'코러스 1' 처럼
 * 번호가 붙은 서버 라벨도 앞 글자로 찾는다.
 */
const SECTION_HINTS: Record<string, string> = {
  '인트로': '곡의 시작. 분위기를 여는 도입부예요.',
  '벌스': '이야기를 풀어가는 절. 코러스 앞에 와요.',
  '프리코러스': '코러스로 이어지는 다리. 긴장이 차오르는 부분이에요.',
  '코러스': '가장 기억에 남는 후렴. 곡의 핵심이에요.',
  '브리지': '분위기를 한 번 바꾸는 전환부. 보통 후반에 와요.',
  '파이널 코러스': '마지막 후렴. 가장 크게 터지는 자리예요.',
  '아웃트로': '곡의 마무리. 여운을 남기며 끝나요.',
};
const sectionHint = (label: string): string | undefined => {
  const key = Object.keys(SECTION_HINTS).sort((a, b) => b.length - a.length).find(k => label.startsWith(k));
  return key ? SECTION_HINTS[key] : undefined;
};

export function PlayerView({ model: m }: { model: MupidModel }) {
  const [confirmUnsave, setConfirmUnsave] = useState(false);
  const [trackInfoOpen, setTrackInfoOpen] = useState(false);
  const [reasonOpen, setReasonOpen] = useState(false);
  // 폰은 가사를 LP 를 눌러 대화상자로, PC 는 오른쪽 빈 자리에 바로 펼친다.
  const isMobile = useIsMobile();
  const [lyricsOpen, setLyricsOpen] = useState(false);
  /*
   * 뒤로가기로 닫히게 한다 (S15P21A204-410). 세 창이 겹쳐 열릴 수 있으므로
   * 등록 순서(호출 순서)가 곧 "가장 위" 순서다 — 나중에 연 것이 먼저 닫힌다.
   * 가사 창은 폰에서만 뜨므로 열림 조건에 `isMobile` 을 함께 본다.
   */
  useDismissableOverlay(trackInfoOpen, () => setTrackInfoOpen(false));
  useDismissableOverlay(reasonOpen, () => setReasonOpen(false));
  useDismissableOverlay(lyricsOpen && isMobile, () => setLyricsOpen(false));
  useDismissableOverlay(confirmUnsave, () => setConfirmUnsave(false));
  // 들을 곡이 없으면 이 화면은 보여 줄 것이 없다. 앱의 다른 화면은 그대로 쓴다.
  const active = m.active;
  if (!active) return <EmptyState title="듣고 싶은 음악을 골라보세요" description="추천 음악이나 탐색에서 곡을 선택하면 재생이 시작돼요."><button className="primary-button" onClick={() => m.go('search')}>음악 둘러보기</button></EmptyState>;
  const recommendationReason = active.recommended?.reason?.trim() || active.recommendationReason?.trim();
  const part = getSongSectionAt(active, m.time || active.moment);
  // 지금 재생 중인 순간이 이미 저장돼 있는지 본다. 저장했다면 여기서 바로 해제할 수 있다.
  const savedMoment = m.moments.find(
    (v) => v.track.id === active.id && v.start === part.start && v.end === part.end,
  );
  const momentSaved = !!savedMoment;
  const vinyl = (
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
  );
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
          {/* PC 에서는 가사가 옆에 펼쳐져 있으므로 LP 는 누를 것이 아니다. */}
          {isMobile
            ? <button type="button" className="record-info-trigger" aria-label={`${active.title} 가사 보기`} aria-haspopup="dialog" onClick={() => setLyricsOpen(true)}>
              {vinyl}
            </button>
            : <div className="record-info-trigger">{vinyl}</div>}
        </div>
        <div className="player-detail">
          {/*
            PC 는 오른쪽 위가 비어 있었다. 가사를 대화상자로 가릴 이유가 없으니 재생바
            위에 펼친다. 지금 부르는 줄이 진하게 따라오고, 줄을 누르면 그 시점으로 간다.
          */}
          {!isMobile && <div className="player-lyrics-inline">
            <Lyrics key={active.id} track={active} time={m.time} expanded onSeek={(seconds) => m.seek(seconds)} />
          </div>}
          {/* 줄은 `TrackComments` 가 그린다 — 같은 줄에 설 댓글 토글이 그쪽 상태를 쓴다. */}
          <TrackComments key={active.id} model={m} controlsAside={!isMobile && <label className="player-desktop-volume">
            <Volume2 size={20} aria-hidden="true" />
            <span className="sr-only">볼륨</span>
            <VolumeSlider />
          </label>}>
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
      <Dialog open={lyricsOpen && isMobile} onOpenChange={setLyricsOpen}>
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
  return <MomentSelection key={`${m.active?.id ?? 0}:${m.editingMomentId ?? 'new'}:${m.momentOpenRevision}`} model={m} />;
}
function MomentSelection({ model: m }: { model: MupidModel }) {
  // 들을 곡이 없으면 이 화면은 보여 줄 것이 없다. 앱의 다른 화면은 그대로 쓴다.
  const active = m.active;
  if (!active) return <EmptyState title="듣고 싶은 음악을 골라보세요" description="추천 음악이나 탐색에서 곡을 선택하면 재생이 시작돼요."><button className="primary-button" onClick={() => m.go('search')}>음악 둘러보기</button></EmptyState>;
  const editing = m.editingMomentId !== null;
  // Keep preview sections local to the editor; never send them as AI analysis.
  const previewSections = active.sections.length === 0;
  const labels = ['인트로', '벌스', '프리코러스', '코러스', '브리지', '파이널 코러스', '아웃트로'];
  const sections = previewSections
    ? labels.map((label, i) => ({ label, start: Math.round(active.duration * i / labels.length), end: Math.round(active.duration * (i + 1) / labels.length) })).filter(part => part.end > part.start)
    : active.sections;
  // 수정으로 들어왔으면 이 곡에 저장한 모든 순간을 한 번에 올린다.
  const editingMoments = editing
    ? m.moments.filter((moment) => moment.track.id === active.id)
    : [];
  const [selected, setSelected] = useState<number[]>(() => {
    if (editing) {
      return [...new Set(editingMoments.map((moment) => sections.findIndex(
        (section) => moment.start >= section.start && moment.start < section.end,
      )).filter((index) => index >= 0))];
    }
    const index = sections.findIndex((section, i) =>
      m.momentAnchorTime >= section.start &&
      (m.momentAnchorTime < section.end || (i === sections.length - 1 && m.momentAnchorTime === section.end)),
    );
    return index >= 0 ? [index] : [];
  });
  const [saved, setSaved] = useState(false);
  // 마지막에 만진 구간. 폰은 마우스를 올릴 수 없어 툴팁이 뜨지 않으니, 그 구간의
  // 설명을 칩 아래 한 줄로 보여 준다. 처음에는 고른 것이 있으면 그것.
  const [hinted, setHinted] = useState<number | null>(() => selected.at(-1) ?? null);
  const [trimmed, setTrimmed] = useState<Record<number, { start: number; end: number }>>(() => {
    if (!editing) return {};
    return Object.fromEntries(editingMoments.flatMap((moment) => {
      const index = sections.findIndex((section) => moment.start >= section.start && moment.start < section.end);
      return index >= 0 ? [[index, { start: moment.start, end: moment.end }]] : [];
    }));
  });
  /*
   * 분홍 양 끝을 끌어 범위를 다른 구간까지 늘리거나 옮기면, 위 칩과 이름도 따라간다.
   * 범위가 걸치는 구간이 하나면 그 구간 이름을, 여럿이면 '벌스 2 – 브리지' 처럼
   * 처음과 끝을 잇는다. 범위에 걸친 칩은 모두 같은 분홍으로 켜진다 — 켜진 칩이 곧 저장될 범위다.
   */
  const covering = (start: number, end: number) => sections.map((s, i) => ({ s, i })).filter(({ s }) => s.start < end && s.end > start);
  const parts = selected.map(i => {
    const range = { ...sections[i], ...trimmed[i] };
    const cover = covering(range.start, range.end);
    const label = cover.length === 0 ? range.label : cover.length === 1 ? cover[0].s.label : `${cover[0].s.label} – ${cover[cover.length - 1].s.label}`;
    return { ...range, label };
  });
  const covered = new Set(parts.flatMap(p => covering(p.start, p.end).map(c => c.i)));
  const focusedPart = parts.at(-1);
  /** 이 시점이 든 순간(선택 목록의 자리). 없으면 -1. */
  const partAt = (time: number) => parts.findIndex(p => time >= p.start && time < p.end);
  /** 이미 고른 순간을 맨 뒤로 보내 손보는 대상으로 삼는다. 새로 더하지 않는다. */
  const focus = (partIndex: number) => setSelected(ids => [...ids.filter((_, k) => k !== partIndex), ids[partIndex]]);
  /**
   * 순간들은 서로 겹치지 않는다. 범위를 끌다가 옆 순간의 경계에 닿으면 거기서 멈춘다.
   * 겹치게 두면 '인트로 – 벌스 2 · 인트로 · 벌스 1' 처럼 같은 자리가 목록에 두세 번
   * 쌓였고, 삼킨 것을 조용히 지우면 왜 사라졌는지 알 수 없다. 막는 쪽이 읽기 쉽다.
   */
  const trimFocused = (start: number, end: number) => {
    const focused = selected[selected.length - 1];
    if (focused === undefined) return;
    const mine = { ...sections[focused], ...trimmed[focused] };
    let lo = 0, hi = active.duration;
    for (const id of selected) {
      if (id === focused) continue;
      const r = { ...sections[id], ...trimmed[id] };
      if (r.end <= mine.start) lo = Math.max(lo, r.end);
      if (r.start >= mine.end) hi = Math.min(hi, r.start);
    }
    const nextStart = Math.max(lo, Math.min(start, hi - 1));
    const nextEnd = Math.min(hi, Math.max(end, nextStart + 1));
    setTrimmed(value => ({ ...value, [focused]: { start: nextStart, end: nextEnd } }));
  };
  return <div className="content-page moment-page">
    <PageHeader
      title={editing ? '순간 수정' : '이 순간 저장'}
      onBack={() => { m.setEditingMomentId(null); m.go(editing ? 'library' : 'player'); }}
      aside={<LikeButton model={m} />}
    />
    <div className="moment-layout">
      <div>
        <div className="intro-copy">
          <h2>{editing ? '저장한 순간을 함께 다듬어 보세요.' : '마음에 드는 순간을 저장하세요.'}</h2>
          <p>파형에서 순간을 누르고, 양 끝을 끌어 원하는 부분만 저장하세요.</p>
        </div>
        <TrackRow track={active} model={m} detail={parts.length ? parts.map(p => p.label).join(' · ') : '저장할 순간을 선택해 주세요'} />
        <div className="moment-editor">
          {previewSections && <p className="moment-wave-help">AI 연결 전 미리보기 · 곡 길이를 임시로 나눈 순간이에요.</p>}
          <div className="song-parts" aria-label="저장할 순간 다중 선택">
            {sections.map((part, i) => <button key={i} title={sectionHint(part.label)} aria-pressed={selected.includes(i)} className={selected.includes(i) || covered.has(i) ? 'selected' : ''} onClick={() => {
              // 다른 순간의 범위에 이미 걸린 구간을 누르면 새로 더하지 않고 그 순간을 손보는 대상으로 삼는다.
              setHinted(i);
              const within = !selected.includes(i) ? partAt(part.start + (part.end - part.start) / 2) : -1;
              const preview = within >= 0 ? parts[within] : { ...part, ...trimmed[i] };
              m.playSavedMoment({ id: preview.start, track: active, start: preview.start, end: preview.end, section: preview.label });
              if (within >= 0) { focus(within); return; }
              setSelected(ids => ids.includes(i) ? ids.filter(id => id !== i) : [...ids, i]);
              setSaved(false);
            }}>{part.label}</button>)}
          </div>
          {hinted !== null && sections[hinted] && sectionHint(sections[hinted].label) && (
            <p className="song-parts-hint" role="status"><strong>{sections[hinted].label}</strong> {sectionHint(sections[hinted].label)}</p>
          )}
          <MomentWaveEditor track={active} time={m.time} range={focusedPart} others={parts.slice(0, -1)} onSelect={time => {
            // 이미 고른 순간 안을 누르면 그 순간을 손보는 대상으로. 바깥이면 그 구간을 새로 고른다.
            const within = partAt(time);
            const index = sections.findIndex(part => time >= part.start && time < part.end);
            if (index >= 0) setHinted(index);
            const preview = within >= 0 ? parts[within] : sections[index];
            // 파형 클릭은 구간 선택이다. 표시된 구간의 시작부터 미리 듣는다.
            if (preview) m.playSavedMoment({
              id: preview.start, track: active, start: preview.start, end: preview.end, section: preview.label,
            });
            if (within >= 0) { focus(within); return; }
            if (index >= 0) { setHinted(index); setSelected(ids => [...ids.filter(id => id !== index), index]); }
            setSaved(false);
          }} onTrim={(start, end) => {
            trimFocused(start, end);
            setSaved(false);
          }} />
          <div className="time-labels"><span aria-label="현재 재생 시간">{formatTime(m.time)}</span><span>{formatTime(active.duration)}</span></div>
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
        <p>{editing ? <>이 곡에 저장한 순간을 모두 보여드려요.<br />수정 후에도 각 순간은 따로 저장돼요.</> : <>선택한 부분은 각각 따로 저장돼요.<br />선택하지 않은 부분은 포함하지 않아요.</>}</p>
        <button className="primary-button wide" disabled={!parts.length || saved} onClick={() => {
          if (editing) {
            m.replaceMoments(
              editingMoments.map((moment) => moment.id),
              parts.map((part, index) => ({
                id: editingMoments[index]?.id ?? Date.now() + index,
                track: active,
                start: part.start,
                end: part.end,
                section: part.label,
              })),
            );
            m.setToast('저장한 순간을 바꿨어요.'); setSaved(true);
            return;
          }
          m.addMoments(parts.map((p, i) => ({ id: Date.now() + i, track: active, start: p.start, end: p.end, section: p.label })));
          m.setToast('선택한 순간을 저장했어요.'); setSaved(true);
        }}>{saved ? <Check /> : <Bookmark />}{saved ? (editing ? '수정 완료' : '저장 완료') : (editing ? '선택한 순간 모두 수정하기' : '선택한 순간 저장하기')}</button>
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
  if (!active) return <EmptyState title="듣고 싶은 음악을 골라보세요" description="추천 음악이나 탐색에서 곡을 선택하면 재생이 시작돼요."><button className="primary-button" onClick={() => m.go('search')}>음악 둘러보기</button></EmptyState>;
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
