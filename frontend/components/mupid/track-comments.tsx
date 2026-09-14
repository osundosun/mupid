'use client';
import { useEffect, useRef, useState } from 'react';
import { MessageCircle, Clock3 } from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
import { formatTime } from '@/lib/mupid-data';
import { COMMENT_MAX_LENGTH, commentable, validateComment } from '@/lib/comment-rules';
import { trackComments, type TrackComment } from '@/lib/track-comments';

export function TrackComments({ model: m }: { model: MupidModel }) {
  const [items, setItems] = useState<TrackComment[]>([]);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  const [busy, setBusy] = useState(false);
  const [draft, setDraft] = useState<{ at: number; id?: number } | null>(null);
  const [body, setBody] = useState('');
  const [order, setOrder] = useState('latest');
  const [remove, setRemove] = useState<number | null>(null);
  const input = useRef<HTMLTextAreaElement>(null);
  const track = m.active;
  const signedIn = m.demoSession?.kind === 'authenticated';
  // 데모 곡은 서버에 없어 댓글을 붙일 데가 없다.
  const available = commentable(track) && signedIn;
  // 곡이 바뀌면 그 곡의 댓글만 다시 받는다. 화면에서 거르지 않는다.
  useEffect(() => {
    if (!available) {
      setItems([]);
      setReady(true);
      setDraft(null);
      return;
    }
    const abort = new AbortController();
    setReady(false);
    setError('');
    void trackComments
      .list(track, abort.signal)
      .then((value) => {
        if (abort.signal.aborted) return;
        setItems(value);
        setReady(true);
      })
      .catch(() => {
        if (abort.signal.aborted) return;
        setError('댓글을 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.');
      });
    return () => abort.abort();
  }, [track.id, available, reload]);
  const describe = (e: unknown) =>
    e instanceof Error && e.message ? e.message : '요청을 처리하지 못했어요.';
  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    setError('');
    try {
      await action();
      return true;
    } catch (e) {
      setError(describe(e));
      return false;
    } finally {
      setBusy(false);
    }
  };
  const start = (comment?: TrackComment) => {
    setDraft(
      comment
        ? { at: comment.at, id: comment.id }
        : { at: Math.min(track.duration, Math.max(0, Math.floor(m.time))) },
    );
    setBody(comment?.body ?? '');
    requestAnimationFrame(() => input.current?.focus());
  };
  const visible = [...items].sort((a, b) =>
    order === 'time' ? a.at - b.at || b.createdAt - a.createdAt : b.createdAt - a.createdAt,
  );
  return <section className="track-comments" aria-labelledby="track-comments-heading">
    <header><h2 id="track-comments-heading"><MessageCircle size={20} /> 이 순간의 이야기 <span>{visible.length}</span></h2>
      <button className="secondary-button" disabled={!ready || busy || !available} onClick={() => start()}>이 순간에 댓글 남기기</button></header>
    {!signedIn && <p className="comment-local-note">로그인하면 이 순간에 이야기를 남길 수 있어요.</p>}
    {signedIn && !commentable(track) && <p className="comment-local-note">체험용 곡이라 댓글을 남길 수 없어요.</p>}
    {!ready && !error && <p role="status">댓글을 불러오고 있어요…</p>}
    {error && <p role="alert">{error} {!ready && <button className="text-link" onClick={() => {setError(''); setReload(v => v + 1);}}>다시 불러오기</button>}</p>}
    {draft && <form onSubmit={async e => {
      e.preventDefault(); if (busy || !validateComment(body, draft.at, track.duration)) return;
      const done = await run(async () => {
        const saved = draft.id
          ? await trackComments.update(track, draft.id, body)
          : await trackComments.add(track, draft.at, body);
        setItems(current => draft.id ? current.map(c => c.id === saved.id ? saved : c) : [...current, saved]);
      });
      if (done) { setDraft(null); setBody(''); }
    }}>
      <label htmlFor="moment-comment"><Clock3 size={16} /> {formatTime(draft.at)} · {draft.id ? '댓글 수정' : '이 시점에 남기기'}</label>
      <textarea ref={input} id="moment-comment" value={body} onChange={e => setBody(e.target.value)} maxLength={COMMENT_MAX_LENGTH} rows={3} disabled={busy} placeholder="이 순간, 어떤 느낌이 들었나요?" required />
      <footer><span>{body.length}/{COMMENT_MAX_LENGTH}</span><button type="button" className="text-link" disabled={busy} onClick={() => {setDraft(null); setBody('');}}>취소</button><button className="primary-button" disabled={busy || !body.trim()}>{busy ? '저장 중…' : draft.id ? '수정 저장' : '댓글 등록'}</button></footer>
    </form>}
    {ready && !visible.length && available && <p className="comments-empty">아직 이야기가 없어요. 좋아하는 순간에 첫 댓글을 남겨보세요.</p>}
    {!!visible.length && <label className="comment-sort">정렬 <select value={order} onChange={e => setOrder(e.target.value)}><option value="latest">최신순</option><option value="time">시간순</option></select></label>}
    <ul>{visible.map(c => <li key={c.id}>
      <div className="comment-meta"><strong>{c.author} {c.mine && <small>나</small>}</strong><button className="comment-time" onClick={() => m.seek(Math.min(c.at, track.duration))} aria-label={`${formatTime(c.at)} 지점 재생`}><Clock3 size={14} /> {formatTime(c.at)}</button></div>
      <p className="comment-body">{c.body}</p>
      {c.mine && <div className="comment-tools"><time dateTime={new Date(c.createdAt).toISOString()}>{new Date(c.createdAt).toLocaleDateString('ko-KR')}</time><button className="text-link" disabled={busy} onClick={() => start(c)}>수정</button><button className="text-link" disabled={busy} onClick={() => setRemove(c.id)}>삭제</button></div>}
      {!c.mine && <div className="comment-tools"><time dateTime={new Date(c.createdAt).toISOString()}>{new Date(c.createdAt).toLocaleDateString('ko-KR')}</time></div>}
      {remove === c.id && <div className="comment-delete">이 댓글을 삭제할까요? <button className="text-link" disabled={busy} onClick={async () => {
        const done = await run(async () => {
          await trackComments.remove(c.id);
          setItems(current => current.filter(v => v.id !== c.id));
        });
        if (done) { setRemove(null); if (draft?.id === c.id) setDraft(null); }
      }}>삭제하기</button><button className="text-link" disabled={busy} onClick={() => setRemove(null)}>취소</button></div>}
    </li>)}</ul>
  </section>;
}
