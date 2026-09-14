'use client';
import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { ChevronLeft, ChevronRight, Clock3, X } from 'lucide-react';
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
import { formatTime, getSongSectionAt } from '@/lib/mupid-data';
import { Waveform, PlayerControls } from './primitives';
import { trackComments, validateComment, commentUserId, ownsComment, type TrackComment } from '@/lib/track-comments';

// 로그인 화면을 다녀오는 동안만 새 댓글 초안을 보관한다. 새로고침하면 폐기된다.
const pendingDrafts = new Map<number, { at: number; body: string; owner: number | null }>();
// 댓글이 쌓여도 목록이 끝없이 길어지지 않게 한 페이지씩 보여 준다.
const COMMENTS_PER_PAGE = 5;

export function TrackComments({ model: m }: { model: MupidModel }) {
  const userId = commentUserId(m.demoSession);
  const [items, setItems] = useState<TrackComment[]>([]);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  const [page, setPage] = useState(0);
  const [busy, setBusy] = useState(false);
  const [draft, setDraft] = useState<{ at: number; id?: string } | null>(null);
  const [body, setBody] = useState('');
  const [order, setOrder] = useState('latest');
  const [remove, setRemove] = useState<string | null>(null);
  const [selectedBucket, setSelectedBucket] = useState<number | null>(null);
  const [point, setPoint] = useState<number | null>(null);
  const part = getSongSectionAt(m.active, m.time);
  const input = useRef<HTMLTextAreaElement>(null);
  const bubble = useRef<HTMLFormElement>(null);
  const waveInput = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (!draft || busy) return;
    const dismiss = (event: PointerEvent) => {
      if (!bubble.current?.contains(event.target as Node)) setDraft(null);
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
    const saved = pendingDrafts.get(m.active.id);
    if (saved && (saved.owner === null || saved.owner === userId)) {
      setDraft({ at: saved.at }); setBody(saved.body);
    } else { pendingDrafts.delete(m.active.id); setDraft(null); setBody(''); }
    setRemove(null);
  }, [userId, m.active.id]);
  useEffect(() => {
    let live = true;
    void trackComments.list().then(value => { if (live) { setItems(value); setReady(true); } })
      .catch(() => { if (live) setError('댓글을 불러오지 못했어요. 브라우저 저장 설정을 확인해 주세요.'); });
    return () => { live = false; };
  }, [reload]);
  const persist = async (next: TrackComment[]) => {
    setBusy(true); setError('');
    try { await trackComments.save(next); setItems(next); return true; }
    catch { setError('저장하지 못했어요. 작성 내용은 그대로 있으니 다시 시도해 주세요.'); return false; }
    finally { setBusy(false); }
  };
  const start = (comment?: TrackComment, selectedAt?: number) => {
    if (busy) return;
    if (comment && !ownsComment(comment, userId)) return;
    // 작성 버튼을 누른 순간을 고정한다. 입력 중 음악이 진행돼도 댓글 시점은 바뀌지 않는다.
    const saved = pendingDrafts.get(m.active.id);
    const at = comment?.at ?? selectedAt ?? saved?.at ?? point ?? Math.min(m.active.duration, Math.max(0, Math.floor(m.time)));
    const text = comment?.body ?? saved?.body ?? '';
    setDraft(comment ? {at, id: comment.id} : {at});
    if (!comment) pendingDrafts.set(m.active.id, {at, body: text, owner: userId});
    setBody(text);
    requestAnimationFrame(() => {
      // 목록에서 '수정'을 누르면 작성창이 파형 옆까지 올라간다. 확실히 보여 준다.
      bubble.current?.scrollIntoView({ block: 'center' });
      input.current?.focus({ preventScroll: true });
    });
  };
  const visible = items.filter(c => c.trackId === m.active.id).sort((a,b) => order === 'time' ? a.at - b.at || b.createdAt - a.createdAt : b.createdAt - a.createdAt);
  // 댓글을 지워 마지막 장이 비면 자동으로 앞 장으로 당긴다.
  const pageCount = Math.max(1, Math.ceil(visible.length / COMMENTS_PER_PAGE));
  const currentPage = Math.min(page, pageCount - 1);
  const paged = visible.slice(currentPage * COMMENTS_PER_PAGE, (currentPage + 1) * COMMENTS_PER_PAGE);
  // 좁은 화면에서도 겹치지 않게 가까운 댓글을 8개 시간 구간으로 묶는다.
  const bucketFor = (at: number) => Math.min(7, Math.floor(Math.max(0, at) / Math.max(1, m.active.duration) * 8));
  const buckets = Array.from({length: 8}, (_, index) => visible.filter(c => bucketFor(c.at) === index).sort((a,b) => a.at - b.at));
  // 지금은 이 브라우저의 댓글만 존재한다. 서버 연동 후 작성자별 수정·삭제 권한을 별도로 검증해야 한다.
  return <section className="track-comments" style={{'--comment-anchor': `${Math.min(100, Math.max(0, (draft?.at ?? m.time) / Math.max(1, m.active.duration) * 100))}%`} as CSSProperties} aria-labelledby="track-comments-heading">
    <h2 id="track-comments-heading" className="sr-only">이 순간의 이야기</h2>
    <div className="comment-wave-surface">
      <div className="player-waveform">
        <Waveform duration={m.active.duration} start={part.start} end={part.end} progress={m.time} />
        <input ref={waveInput} className="playback-range" type="range" aria-label="재생 위치 선택, Enter로 이 시점에 댓글 작성" aria-valuetext={formatTime(m.time)} min={0} max={m.active.duration} step={1} value={m.time} onChange={e => { const at = Number(e.target.value); m.seek(at); if (!draft) setPoint(at); }} onPointerUp={e => start(undefined, Number(e.currentTarget.value))} onKeyDown={e => {if (e.key === 'Enter') {e.preventDefault(); start(undefined, Number(e.currentTarget.value));}}} />
      </div>
      <div className="wave-comment-markers" aria-label="시간별 댓글">
        {buckets.map((comments, index) => <div key={index}>{!!comments.length && <button className="wave-comment-avatar" aria-expanded={selectedBucket === index} aria-label={`${formatTime(comments[0].at)} ${comments[0].author}님의 댓글${comments.length > 1 ? ` 외 ${comments.length - 1}개` : ''}`} onClick={() => {setSelectedBucket(index); m.seek(Math.min(comments[0].at, m.active.duration));}}><span aria-hidden="true">{Array.from(comments[0].author)[0] || '♪'}</span>{comments.length > 1 && <small>{comments.length}</small>}</button>}</div>)}
      </div>
      {/* 팝오버가 파형을 덮으면 숫자만 남아 무슨 시간인지 알 수 없다. 이름표를 붙인다. */}
      <div className="time-labels"><span><em>지금</em> {formatTime(m.time)}</span><span><em>전체</em> {formatTime(m.active.duration)}</span></div>
      {ready && !visible.length && <p className="wave-comment-hint">파형에서 순간을 골라 첫 이야기를 남겨보세요.</p>}
      {selectedBucket !== null && !!buckets[selectedBucket].length && <div className="wave-comment-popover">
        <header className="wave-comment-popover-head">
          <span>이 순간의 댓글 · {buckets[selectedBucket].length}개</span>
          <button className="icon-button" aria-label="댓글 닫기" onClick={() => setSelectedBucket(null)}><X size={18} /></button>
        </header>
        {buckets[selectedBucket].map(c => <article key={c.id}>
          <div className="wave-comment-line">
            <button className="comment-time" onClick={() => m.seek(Math.min(c.at, m.active.duration))}>{formatTime(c.at)}</button>
            <strong>{c.author}</strong>
          </div>
          <p>{c.body}</p>
          {/* 목록까지 내려가지 않아도 여기서 바로 고치고 지울 수 있게 둔다. */}
          {ownsComment(c, userId) && <div className="comment-tools">
            <button className="text-link" disabled={busy} onClick={() => { setSelectedBucket(null); start(c); }}>수정</button>
            <button className="text-link" disabled={busy} onClick={() => setRemove(c.id)}>삭제</button>
          </div>}
        </article>)}
      </div>}
    </div>
    <PlayerControls model={m} />
    {!ready && !error && <output>댓글을 불러오고 있어요…</output>}
    {error && <p role="alert">{error} {!ready && <button className="text-link" onClick={() => {setError(''); setReload(v => v + 1);}}>다시 불러오기</button>}</p>}
    {draft && <form ref={bubble} className="wave-comment-composer" role="dialog" aria-label={`${formatTime(draft.at)} 댓글 ${draft.id ? '수정' : '작성'}`} onSubmit={async e => {
      e.preventDefault(); if (busy || !ready || !validateComment(body, draft.at, m.active.duration)) return;
      if (userId === null) { m.openLogin(); return; }
      if (draft.id && !items.some(c => c.id === draft.id && ownsComment(c, userId))) return;
      const next = draft.id ? items.map(c => c.id === draft.id ? {...c, body: body.trim()} : c) : [...items, {id: crypto.randomUUID(), trackId: m.active.id, at: draft.at, body: body.trim(), author: m.nickname || '나', authorId: userId, createdAt: Date.now()}];
      if (await persist(next)) { pendingDrafts.delete(m.active.id); setSelectedBucket(bucketFor(draft.at)); setPoint(null); setDraft(null); setBody(''); }
    }}>
      <label htmlFor="moment-comment"><Clock3 size={16} /> {formatTime(draft.at)} · {draft.id ? '댓글 수정' : '이 시점에 남기기'}</label>
      <textarea ref={input} id="moment-comment" value={body} onChange={e => {setBody(e.target.value); if (!draft.id) pendingDrafts.set(m.active.id, {at: draft.at, body: e.target.value, owner: userId});}} maxLength={500} rows={2} disabled={busy} placeholder="이 순간, 어떤 느낌이 들었나요?" required />
      <footer><span>{body.length}/500</span><button type="button" className="text-link" disabled={busy} onClick={() => {pendingDrafts.delete(m.active.id); setDraft(null); setBody('');}}>취소</button><button className="primary-button" disabled={!ready || busy || !body.trim()}>{busy ? '저장 중…' : userId === null ? '로그인하고 등록' : draft.id ? '수정 저장' : '댓글 등록'}</button></footer>
    </form>}
    <details className="moment-comment-list"><summary>이 순간의 이야기 · {visible.length}개</summary>
    <p className="comment-local-note">체험용 댓글 · 이 브라우저에만 저장되며 다른 사용자에게 공개되지 않아요.</p>
    {ready && !visible.length && <p className="comments-empty">아직 이야기가 없어요. 좋아하는 순간에 첫 댓글을 남겨보세요.</p>}
    {!!visible.length && <label className="comment-sort">정렬 <select value={order} onChange={e => {setOrder(e.target.value); setPage(0);}}><option value="latest">최신순</option><option value="time">시간순</option></select></label>}
    <ul>{paged.map(c => <li key={c.id}>
      <div className="comment-meta"><strong>{c.author}</strong><button className="comment-time" onClick={() => m.seek(Math.min(c.at, m.active.duration))} aria-label={`${formatTime(c.at)} 지점 재생`}><Clock3 size={14} /> {formatTime(c.at)}</button></div>
      <p className="comment-body">{c.body}</p>
      <div className="comment-tools"><time dateTime={new Date(c.createdAt).toISOString()}>{new Date(c.createdAt).toLocaleDateString('ko-KR')}</time>{ownsComment(c, userId) && <><button className="text-link" disabled={busy} onClick={() => start(c)}>수정</button><button className="text-link" disabled={busy} onClick={() => setRemove(c.id)}>삭제</button></>}</div>
    </li>)}</ul>
    {pageCount > 1 && <nav className="comment-pagination" aria-label="댓글 목록 페이지">
      <button className="icon-button" disabled={currentPage === 0} aria-label="이전 댓글 보기" onClick={() => setPage(currentPage - 1)}><ChevronLeft /></button>
      <span aria-live="polite">{currentPage + 1} / {pageCount}</span>
      <button className="icon-button" disabled={currentPage >= pageCount - 1} aria-label="다음 댓글 보기" onClick={() => setPage(currentPage + 1)}><ChevronRight /></button>
    </nav>}
    </details>
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
            if (!target || !ownsComment(target, commentUserId(m.demoSession))) { setRemove(null); return; }
            if (await persist(items.filter(v => v.id !== target.id))) {
              setRemove(null);
              if (draft?.id === target.id) setDraft(null);
            }
          }}>삭제</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  </section>;
}
