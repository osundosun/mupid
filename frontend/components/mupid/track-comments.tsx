'use client';
import { useEffect, useRef, useState } from 'react';
import { MessageCircle, Clock3 } from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
import { formatTime } from '@/lib/mupid-data';
import { trackComments, validateComment, commentUserId, ownsComment, type TrackComment } from '@/lib/track-comments';

export function TrackComments({ model: m }: { model: MupidModel }) {
  const userId = commentUserId(m.demoSession);
  const [items, setItems] = useState<TrackComment[]>([]);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  const [busy, setBusy] = useState(false);
  const [draft, setDraft] = useState<{ at: number; id?: string } | null>(null);
  const [body, setBody] = useState('');
  const [order, setOrder] = useState('latest');
  const [remove, setRemove] = useState<string | null>(null);
  const input = useRef<HTMLTextAreaElement>(null);
  // 로그아웃/계정 전환 시 이전 계정의 작성·삭제 상태를 폐기한다.
  useEffect(() => { setDraft(null); setBody(''); setRemove(null); }, [userId]);
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
  const start = (comment?: TrackComment) => {
    if (userId === null) { m.openLogin(); return; }
    if (comment && !ownsComment(comment, userId)) return;
    // 작성 버튼을 누른 순간을 고정한다. 입력 중 음악이 진행돼도 댓글 시점은 바뀌지 않는다.
    setDraft(comment ? {at: comment.at, id: comment.id} : {at: Math.min(m.active.duration, Math.max(0, Math.floor(m.time)))});
    setBody(comment?.body ?? '');
    requestAnimationFrame(() => input.current?.focus());
  };
  const visible = items.filter(c => c.trackId === m.active.id).sort((a,b) => order === 'time' ? a.at - b.at || b.createdAt - a.createdAt : b.createdAt - a.createdAt);
  // 지금은 이 브라우저의 댓글만 존재한다. 서버 연동 후 작성자별 수정·삭제 권한을 별도로 검증해야 한다.
  return <section className="track-comments" aria-labelledby="track-comments-heading">
    <header><h2 id="track-comments-heading"><MessageCircle size={20} /> 이 순간의 이야기 <span>{visible.length}</span></h2>
      <button className="secondary-button" disabled={!ready || busy} onClick={() => start()}>{userId === null ? '로그인하고 댓글 남기기' : '이 순간에 댓글 남기기'}</button></header>
    <p className="comment-local-note">체험용 댓글 · 이 브라우저에만 저장되며 다른 사용자에게 공개되지 않아요.</p>
    {!ready && !error && <p role="status">댓글을 불러오고 있어요…</p>}
    {error && <p role="alert">{error} {!ready && <button className="text-link" onClick={() => {setError(''); setReload(v => v + 1);}}>다시 불러오기</button>}</p>}
    {draft && userId !== null && <form onSubmit={async e => {
      e.preventDefault(); if (busy || !validateComment(body, draft.at, m.active.duration)) return;
      if (draft.id && !items.some(c => c.id === draft.id && ownsComment(c, userId))) return;
      const next = draft.id ? items.map(c => c.id === draft.id ? {...c, body: body.trim()} : c) : [...items, {id: crypto.randomUUID(), trackId: m.active.id, at: draft.at, body: body.trim(), author: m.nickname || '나', authorId: userId, createdAt: Date.now()}];
      if (await persist(next)) { setDraft(null); setBody(''); }
    }}>
      <label htmlFor="moment-comment"><Clock3 size={16} /> {formatTime(draft.at)} · {draft.id ? '댓글 수정' : '이 시점에 남기기'}</label>
      <textarea ref={input} id="moment-comment" value={body} onChange={e => setBody(e.target.value)} maxLength={500} rows={3} disabled={busy} placeholder="이 순간, 어떤 느낌이 들었나요?" required />
      <footer><span>{body.length}/500</span><button type="button" className="text-link" disabled={busy} onClick={() => {setDraft(null); setBody('');}}>취소</button><button className="primary-button" disabled={busy || !body.trim()}>{busy ? '저장 중…' : draft.id ? '수정 저장' : '댓글 등록'}</button></footer>
    </form>}
    {ready && !visible.length && <p className="comments-empty">아직 이야기가 없어요. 좋아하는 순간에 첫 댓글을 남겨보세요.</p>}
    {!!visible.length && <label className="comment-sort">정렬 <select value={order} onChange={e => setOrder(e.target.value)}><option value="latest">최신순</option><option value="time">시간순</option></select></label>}
    <ul>{visible.map(c => <li key={c.id}>
      <div className="comment-meta"><strong>{c.author} <small>{ownsComment(c, userId) ? '내 댓글 · 로컬' : '로컬'}</small></strong><button className="comment-time" onClick={() => m.seek(Math.min(c.at, m.active.duration))} aria-label={`${formatTime(c.at)} 지점 재생`}><Clock3 size={14} /> {formatTime(c.at)}</button></div>
      <p className="comment-body">{c.body}</p>
      <div className="comment-tools"><time dateTime={new Date(c.createdAt).toISOString()}>{new Date(c.createdAt).toLocaleDateString('ko-KR')}</time>{ownsComment(c, userId) && <><button className="text-link" disabled={busy} onClick={() => start(c)}>수정</button><button className="text-link" disabled={busy} onClick={() => setRemove(c.id)}>삭제</button></>}</div>
      {remove === c.id && ownsComment(c, userId) && <div className="comment-delete">이 댓글을 삭제할까요? <button className="text-link" disabled={busy} onClick={async () => {if (!ownsComment(c, commentUserId(m.demoSession))) return; if (await persist(items.filter(v => v.id !== c.id))) {setRemove(null); if (draft?.id === c.id) setDraft(null);}}}>삭제하기</button><button className="text-link" disabled={busy} onClick={() => setRemove(null)}>취소</button></div>}
    </li>)}</ul>
  </section>;
}
