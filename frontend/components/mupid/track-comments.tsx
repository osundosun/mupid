'use client';
import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { Clock3, MessageCircle, X } from 'lucide-react';
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
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { useIsMobile } from '@/hooks/use-mobile';
import type { MupidModel } from '@/hooks/use-mupid';
import { formatTime } from '@/lib/mupid-data';
import { EmptyState, PlayerControls } from './primitives';
import { commentable, validateComment } from '@/lib/comment-rules';
import { trackComments, commentUserId, ownsComment, type TrackComment } from '@/lib/track-comments';
import { localDemoEnabled } from '@/lib/api/local-demo';
import { profilePhotoStore } from '@/lib/profile-photo';
import { DEFAULT_PROFILE_PHOTO } from './profile-image';
import { useStore } from '@/lib/store';

// 로그인 화면을 다녀오는 동안만 새 댓글 초안을 보관한다. 새로고침하면 폐기된다.
const pendingDrafts = new Map<number, { at: number; body: string; owner: number | null }>();
function CommentAvatar({ comment }: { comment: TrackComment }) {
  const [failed, setFailed] = useState<string | null>(null);
  const myPhoto = useStore(profilePhotoStore, state => state.url);
  const photo = comment.mine ? myPhoto : undefined;
  const src = photo && photo !== failed ? photo : DEFAULT_PROFILE_PHOTO;
  return <img className="comment-avatar-image" src={src} alt="" onError={() => { if (src !== DEFAULT_PROFILE_PHOTO) setFailed(src); }} />;
}

export function TrackComments({ model: m, children }: { model: MupidModel; children?: ReactNode }) {
  // 들을 곡이 없으면 이 화면은 보여 줄 것이 없다. 앱의 다른 화면은 그대로 쓴다.
  const active = m.active;
  if (!active) return <EmptyState title="아직 들을 곡이 없어요" description="음악이 등록되면 여기에서 바로 들을 수 있어요." />;
  const userId = commentUserId(m.demoSession);
  const service = trackComments;
  // 로컬 데모 곡은 서버에 없지만(`musicId` 없음) 브라우저 저장소가 댓글을 받는다.
  const localComments = localDemoEnabled() && Boolean(active.localAudio);
  const available = (commentable(active) || localComments) && m.demoSession?.kind === 'authenticated';
  const [items, setItems] = useState<TrackComment[]>([]);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  const [busy, setBusy] = useState(false);
  const [draft, setDraft] = useState<{ at: number; id?: string } | null>(null);
  const [body, setBody] = useState('');
  const [remove, setRemove] = useState<string | null>(null);
  const [selectedTime, setSelectedTime] = useState<number | null>(null);
  const [point, setPoint] = useState<number | null>(null);
  const markerRail = useRef<HTMLDivElement>(null);
  // 폰에서는 선택한 시점의 댓글을 본문에 펼치지 않고 대화상자로 띄운다(아래 `panel`).
  const isMobile = useIsMobile();
  const [railWidth, setRailWidth] = useState(300);
  useEffect(() => {
    const rail = markerRail.current;
    if (!rail) return;
    const observer = new ResizeObserver(([entry]) => setRailWidth(entry.contentRect.width));
    observer.observe(rail);
    return () => observer.disconnect();
  }, []);
  const input = useRef<HTMLTextAreaElement>(null);
  const bubble = useRef<HTMLFormElement>(null);
  const waveInput = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (!draft || busy) return;
    const dismiss = (event: PointerEvent) => {
      const node = event.target as Node | null;
      if (bubble.current?.contains(node as Node)) return;
      // 토글 자신을 누른 것은 '바깥 클릭' 이 아니다. 여기서 닫아 버리면
      // 토글의 onClick 이 닫힌 상태를 보고 곧바로 다시 연다.
      const element = node instanceof Element ? node : node?.parentElement ?? null;
      if (element?.closest('[data-comment-toggle]')) return;
      setDraft(null);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { setDraft(null); waveInput.current?.focus(); }
    };
    document.addEventListener('pointerdown', dismiss);
    document.addEventListener('keydown', escape);
    return () => { document.removeEventListener('pointerdown', dismiss); document.removeEventListener('keydown', escape); };
  }, [draft, busy]);
  // 로그아웃/계정 전환 시 이전 계정의 작성·삭제 상태를 폐기한다.
  useEffect(() => {
    const saved = pendingDrafts.get(active.id);
    if (saved && (saved.owner === null || saved.owner === userId)) {
      setDraft({ at: saved.at }); setBody(saved.body);
    } else { pendingDrafts.delete(active.id); setDraft(null); setBody(''); }
    setRemove(null);
  }, [userId, active.id]);
  // 댓글은 곡마다 서버에서 받는다. 로그인해야 읽을 수 있고 다른 사람 것도 보인다.
  useEffect(() => {
    if (!available) { setItems([]); setReady(true); return; }
    const abort = new AbortController();
    setReady(false); setError('');
    void service.list(active, abort.signal)
      .then(value => { if (!abort.signal.aborted) { setItems(value); setReady(true); } })
      .catch(() => { if (!abort.signal.aborted) setError('댓글을 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.'); });
    return () => abort.abort();
  }, [available, active.id, active.musicId, reload]);
  /** 서버 호출 한 번을 감싼다. 실패하면 작성 내용을 그대로 두고 알린다. */
  const run = async (action: () => Promise<void>) => {
    setBusy(true); setError('');
    try { await action(); return true; }
    catch (e) { setError(e instanceof Error && e.message ? e.message : '저장하지 못했어요. 다시 시도해 주세요.'); return false; }
    finally { setBusy(false); }
  };
  const start = (comment?: TrackComment, selectedAt?: number) => {
    if (busy) return;
    if (comment && !ownsComment(comment, userId)) return;
    // 작성 버튼을 누른 순간을 고정한다. 입력 중 음악이 진행돼도 댓글 시점은 바뀌지 않는다.
    const saved = pendingDrafts.get(active.id);
    const at = comment?.at ?? selectedAt ?? saved?.at ?? point ?? Math.min(active.duration, Math.max(0, Math.round(m.time * 1000) / 1000));
    const text = comment?.body ?? saved?.body ?? '';
    setDraft(comment ? {at, id: comment.id} : {at});
    if (!comment) pendingDrafts.set(active.id, {at, body: text, owner: userId});
    setBody(text);
    requestAnimationFrame(() => {
      // 목록에서 '수정'을 누르면 작성창이 파형 옆까지 올라간다. 확실히 보여 준다.
      bubble.current?.scrollIntoView({ block: 'center' });
      input.current?.focus({ preventScroll: true });
    });
  };
  const closePanel = () => { setSelectedTime(null); setDraft(null); setBody(''); };
  const visible = items.filter(c => c.trackId === active.id && Number.isFinite(c.at) && c.at >= 0 && c.at <= active.duration);
  // 같은 시점만 묶는다. 가까운 다른 시점은 가로 위치를 유지하고 다음 줄에 둔다.
  const moments = new Map<number, TrackComment[]>();
  for (const comment of visible) {
    const comments = moments.get(comment.at) ?? [];
    comments.push(comment);
    moments.set(comment.at, comments);
  }
  /*
   * 하트는 **한 줄에만 둔다.**
   *
   * 전에는 겹치는 것을 아래 줄로 내렸는데, 가까운 시점 몇 개 때문에 레일이
   * 세로로 흩어져 어느 하트가 어느 시점인지 읽기 어려웠다. 대신 서로 겹칠
   * 만큼 가까운 시점끼리 하나로 묶고 개수를 배지로 보여 준다.
   *
   * 묶는 기준은 **처음 놓인 하트**에서의 거리다. 직전 하트에서 재면 조금씩
   * 가까운 것들이 사슬처럼 이어져 묶음 하나가 레일 끝까지 자란다.
   */
  const MARKER_WIDTH = 44;
  const clusters: { at: number; comments: TrackComment[]; left: number }[] = [];
  let anchorX = -Infinity;
  for (const [at, comments] of [...moments.entries()].sort(([a], [b]) => a - b)) {
    const x = at / Math.max(1, active.duration) * railWidth;
    const last = clusters[clusters.length - 1];
    if (last && x - anchorX < MARKER_WIDTH) { last.comments.push(...comments); continue; }
    clusters.push({ at, comments: [...comments], left: at / Math.max(1, active.duration) * 100 });
    anchorX = x;
  }
  const selectedComments = selectedTime === null
    ? []
    : clusters.find(c => c.at === selectedTime)?.comments ?? moments.get(selectedTime) ?? [];
  const composer = draft && <form ref={bubble} className="wave-comment-composer" role="dialog" aria-label={`${formatTime(draft.at)} 댓글 ${draft.id ? '수정' : '작성'}`} onSubmit={async e => {
    e.preventDefault(); if (busy || !ready || !validateComment(body, draft.at, active.duration)) return;
    if (userId === null) { m.openLogin(); return; }
    if (draft.id && !items.some(c => c.id === draft.id && ownsComment(c, userId))) return;
    const done = await run(async () => {
      const saved = draft.id
        ? await service.update(active, draft.id, body)
        : await service.add(active, draft.at, body);
      setItems(current => draft.id ? current.map(c => c.id === saved.id ? saved : c) : [...current, saved]);
    });
    if (done) { pendingDrafts.delete(active.id); setSelectedTime(draft.at); setPoint(draft.at); setDraft(null); setBody(''); }
  }}>
    <label htmlFor="moment-comment" className={selectedTime !== null && !draft.id ? 'sr-only' : undefined}><Clock3 size={16} /> {draft.id ? `${formatTime(draft.at)} · 댓글 수정` : `${formatTime(draft.at)}에 댓글 남기기`}</label>
    <textarea ref={input} id="moment-comment" value={body} onChange={e => {setBody(e.target.value); if (!draft.id) pendingDrafts.set(active.id, {at: draft.at, body: e.target.value, owner: userId});}} maxLength={100} rows={2} disabled={busy} placeholder="이 순간, 어떤 느낌이 들었나요?" required />
    <footer><span>{body.length}/100</span><button type="button" className="text-link" disabled={busy} onClick={() => {pendingDrafts.delete(active.id); setDraft(null); setBody('');}}>취소</button><button className="primary-button" disabled={!ready || busy || !body.trim() || (userId !== null && !available)}>{busy ? '저장 중…' : userId === null ? '로그인하고 등록' : draft.id ? '수정 저장' : '댓글 등록'}</button></footer>
  </form>;
  /*
   * 선택한 시점의 댓글 묶음. 데스크톱은 파형 아래에 카드로 펼치고, 폰은 같은 내용을
   * 대화상자로 띄운다(`.moment-comment-dialog`). 폰에서 본문에 펼치면 그 높이만큼
   * 플레이어가 한 화면을 넘겨 스크롤이 생기고, 댓글을 읽는 동안 LP 와 컨트롤이
   * 위로 밀려 나갔다. 대화상자 머리말이 시각과 개수를 대신 말하므로 안의 머리말은
   * CSS 로 감춘다.
   */
  const panel = selectedTime !== null && <div className="wave-comment-popover selected-moment-popover" key={selectedTime} role="region" aria-label="선택한 시점의 댓글">
      <header className="wave-comment-popover-head"><span>{formatTime(selectedTime)} · 댓글 {selectedComments.length}개</span><button className="icon-button" aria-label="댓글 닫기" onClick={closePanel}><X size={18} /></button></header>
      <div className="selected-moment-messages">
        {selectedComments.length ? selectedComments.map(c => <article key={c.id}>
          <div className="wave-comment-line"><span className="moment-author-avatar" aria-hidden="true"><CommentAvatar comment={c} /></span><strong>{c.author}</strong><span className="moment-comment-timestamp">{formatTime(c.at)}</span>{ownsComment(c, userId) && <button className="text-link moment-edit" onClick={() => start(c)}>수정</button>}{ownsComment(c, userId) && <button className="text-link moment-delete" disabled={busy} onClick={() => setRemove(c.id)}>삭제</button>}</div>
          <p>{c.body}</p>
        </article>) : <p className="moment-popup-empty">이 순간에 첫 글을 남겨보세요.</p>}
      </div>
      <button type="button" data-comment-toggle className="moment-popup-write" aria-pressed={draft !== null && !draft.id} onClick={() => {
        if (draft && !draft.id) { pendingDrafts.delete(active.id); setDraft(null); setBody(''); return; }
        start(undefined, selectedTime);
      }}><Clock3 size={16} />{formatTime(selectedTime)}에 댓글 남기기</button>
      {composer}
    </div>;
  // 수정·삭제 권한은 서버가 판정한 `mine` 을 따른다. 화면이 user_id 를 비교하지 않는다.
  return <section className="track-comments" style={{'--comment-anchor': `${Math.min(100, Math.max(0, (draft?.at ?? m.time) / Math.max(1, active.duration) * 100))}%`} as CSSProperties} aria-labelledby="track-comments-heading">
    <h2 id="track-comments-heading" className="sr-only">순간 댓글</h2>
    <div className="comment-wave-surface">
      <div className="player-waveform player-line-timeline">
        <span className="player-progress-line" aria-hidden="true"><span style={{ width: `${Math.min(100, Math.max(0, m.time / Math.max(1, active.duration) * 100))}%` }} /></span>
        <span className="wave-playhead" aria-hidden="true" style={{ left: `${Math.min(100, Math.max(0, m.time / Math.max(1, active.duration) * 100))}%` }} />
        <input ref={waveInput} className="playback-range" type="range" aria-label="재생 위치 선택, Enter로 이 시점에 댓글 작성" aria-valuetext={formatTime(m.time)} min={0} max={active.duration} step={1} value={m.time} onChange={e => { const at = Number(e.target.value); m.seek(at); if (!draft) setPoint(at); }} onPointerUp={e => { const at = Number(e.currentTarget.value); setPoint(at); if (selectedTime !== null) setSelectedTime(at); }} onKeyDown={e => {if (e.key === 'Enter') {e.preventDefault(); const at = Number(e.currentTarget.value); setPoint(at); setSelectedTime(at); start(undefined, at);}}} />
      </div>
      <div className="moment-heart-scroll" aria-label="시간별 댓글">
        <div ref={markerRail} className="moment-heart-rail" style={{ height: clusters.length ? MARKER_WIDTH : 0 }}>
          {clusters.map(({ at, comments, left }) => <button key={at} className="moment-heart-marker" style={{ left: left + '%', top: 0 }} aria-expanded={selectedTime === at} aria-label={formatTime(at) + ' 댓글 ' + comments.length + '개'} onClick={() => { setSelectedTime(at); setPoint(at); m.seek(at); }}>
            <span className="moment-author-avatar" aria-hidden="true"><CommentAvatar comment={comments[0]} /></span>
            {comments.length > 1 && <small>{comments.length}</small>}
          </button>)}
        </div>
      </div>
      {/* 팝오버가 파형을 덮으면 숫자만 남아 무슨 시간인지 알 수 없다. 이름표를 붙인다. */}
      <div className="time-labels"><span aria-label={`현재 재생 시간 ${formatTime(m.time)}`}>{formatTime(m.time)}</span><span aria-label={`전체 재생 시간 ${formatTime(active.duration)}`}>{formatTime(active.duration)}</span></div>
      <PlayerControls model={m} />
      <div className="player-quick-actions" aria-label="좋아요와 곡 저장, 댓글">
        {children}
        <button type="button" className="moment-panel-toggle" aria-expanded={selectedTime !== null} onClick={() => {
          if (selectedTime !== null) { closePanel(); return; }
          // 마지막으로 짚은 지점이 없으면 지금 듣고 있는 자리를 연다.
          setSelectedTime(point ?? Math.min(active.duration, Math.max(0, Math.round(m.time))));
        }}><MessageCircle />{selectedTime !== null ? '댓글 닫기' : `댓글 ${visible.length}개`}</button>
      </div>
      {!isMobile && panel}

    </div>
    {!ready && !error && <output>댓글을 불러오고 있어요…</output>}
    {error && <p role="alert">{error} {!ready && <button className="text-link" onClick={() => {setError(''); setReload(v => v + 1);}}>다시 불러오기</button>}</p>}
    {selectedTime === null && composer}
    <Dialog open={isMobile && selectedTime !== null} onOpenChange={open => { if (!open) closePanel(); }}>
      <DialogContent className="sky-dialog moment-comment-dialog">
        <DialogHeader>
          <DialogTitle>{selectedTime !== null ? formatTime(selectedTime) : ''} 순간의 댓글</DialogTitle>
          <DialogDescription>{active.title} · 댓글 {selectedComments.length}개</DialogDescription>
        </DialogHeader>
        {panel}
      </DialogContent>
    </Dialog>
    <AlertDialog open={remove !== null} onOpenChange={open => { if (!open) setRemove(null); }}>
      <AlertDialogContent className="sky-dialog">
        <AlertDialogHeader>
          <AlertDialogTitle>댓글을 삭제할까요?</AlertDialogTitle>
          <AlertDialogDescription>삭제한 댓글은 되돌릴 수 없어요.</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={busy}>취소</AlertDialogCancel>
          <AlertDialogAction disabled={busy} onClick={async event => {
            // 저장이 끝난 뒤 닫아야 실패했을 때 되돌릴 수 있다.
            event.preventDefault();
            const target = items.find(v => v.id === remove);
            if (!target || !ownsComment(target, userId)) { setRemove(null); return; }
            const done = await run(async () => {
              await service.remove(target.id);
              setItems(current => current.filter(v => v.id !== target.id));
            });
            if (done) {
              setRemove(null);
              if (draft?.id === target.id) setDraft(null);
            }
          }}>삭제</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  </section>;
}
