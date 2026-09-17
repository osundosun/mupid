'use client';
import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { Clock3, Heart, X } from 'lucide-react';
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
import { formatTime } from '@/lib/mupid-data';
import { EmptyState, PlayerControls } from './primitives';
import { commentable, validateComment } from '@/lib/comment-rules';
import { trackComments, commentUserId, ownsComment, type TrackComment } from '@/lib/track-comments';
import { localDemoEnabled } from '@/lib/api/local-demo';
import { DEFAULT_PROFILE_PHOTO, profilePhotoStore } from '@/lib/profile-photo';
import { useStore } from '@/lib/store';

// 로그인 화면을 다녀오는 동안만 새 댓글 초안을 보관한다. 새로고침하면 폐기된다.
const pendingDrafts = new Map<number, { at: number; body: string; owner: number | null }>();
function CommentAvatar({ comment }: { comment: TrackComment }) {
  const [failed, setFailed] = useState<string | null>(null);
  const myPhoto = useStore(profilePhotoStore, state => state.url);
  const photo = comment.mine ? myPhoto || comment.avatarUrl : comment.avatarUrl;
  const src = photo && photo !== failed ? photo : DEFAULT_PROFILE_PHOTO;
  return <img className="comment-avatar-image" src={src} alt="" onError={() => { if (src !== DEFAULT_PROFILE_PHOTO) setFailed(src); }} />;
}

export function TrackComments({ model: m }: { model: MupidModel }) {
  // 들을 곡이 없으면 이 화면은 보여 줄 것이 없다. 앱의 다른 화면은 그대로 쓴다.
  const active = m.active;
  if (!active) return <EmptyState title="아직 들을 곡이 없어요" description="음악이 등록되면 여기에서 바로 들을 수 있어요." />;
  const local = localDemoEnabled() && Boolean(active.localAudio);
  const userId = local && m.demoSession ? 1 : commentUserId(m.demoSession);
  const localKey = `mupid-local-comments:${active.id}`;
  const service = local ? {
    async list(_track: unknown, _signal?: AbortSignal): Promise<TrackComment[]> {
      const stored = JSON.parse(localStorage.getItem(localKey) ?? '[]');
      return Array.isArray(stored) ? stored : [];
    },
    async add(_track: unknown, at: number, text: string): Promise<TrackComment> {
      const saved: TrackComment = { id: crypto.randomUUID(), trackId: active.id, at, body: text.trim(), author: m.displayName, mine: true, createdAt: Date.now() };
      const stored = JSON.parse(localStorage.getItem(localKey) ?? '[]');
      localStorage.setItem(localKey, JSON.stringify([...stored, saved]));
      return saved;
    },
    async update(_track: unknown, id: string, text: string): Promise<TrackComment> {
      const stored: TrackComment[] = JSON.parse(localStorage.getItem(localKey) ?? '[]');
      const saved = stored.find(c => c.id === id && c.mine);
      if (!saved) throw new Error('수정할 글을 찾지 못했어요.');
      saved.body = text.trim();
      localStorage.setItem(localKey, JSON.stringify(stored));
      return saved;
    },
    async remove(id: string) {
      const stored: TrackComment[] = JSON.parse(localStorage.getItem(localKey) ?? '[]');
      localStorage.setItem(localKey, JSON.stringify(stored.filter(c => c.id !== id || !c.mine)));
    },
  } : trackComments;
  // 데모 곡은 서버에 없어 댓글을 붙일 데가 없다.
  const available = local ? userId !== null : commentable(active) && m.demoSession?.kind === 'authenticated';
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
  const visible = items.filter(c => c.trackId === active.id && Number.isFinite(c.at) && c.at >= 0 && c.at <= active.duration);
  // 같은 시점만 묶는다. 가까운 다른 시점은 가로 위치를 유지하고 다음 줄에 둔다.
  const moments = new Map<number, TrackComment[]>();
  for (const comment of visible) {
    const comments = moments.get(comment.at) ?? [];
    comments.push(comment);
    moments.set(comment.at, comments);
  }
  const laneEnds: number[] = [];
  const markers = [...moments.entries()].sort(([a], [b]) => a - b).map(([at, comments]) => {
    const left = at / Math.max(1, active.duration) * 100;
    const x = left / 100 * railWidth;
    let lane = laneEnds.findIndex(end => x - end >= 44);
    if (lane === -1) lane = laneEnds.length;
    laneEnds[lane] = x;
    return { at, comments, left, lane };
  });
  const selectedComments = selectedTime === null ? [] : moments.get(selectedTime) ?? [];
  // 수정·삭제 권한은 서버가 판정한 `mine` 을 따른다. 화면이 user_id 를 비교하지 않는다.
  return <section className="track-comments" style={{'--comment-anchor': `${Math.min(100, Math.max(0, (draft?.at ?? m.time) / Math.max(1, active.duration) * 100))}%`} as CSSProperties} aria-labelledby="track-comments-heading">
    <h2 id="track-comments-heading" className="sr-only">순간 댓글</h2>
    <div className="comment-wave-surface">
      <div className="player-waveform player-line-timeline">
        <span className="player-progress-line" aria-hidden="true"><span style={{ width: `${Math.min(100, Math.max(0, m.time / Math.max(1, active.duration) * 100))}%` }} /></span>
        <span className="wave-playhead" aria-hidden="true" style={{ left: `${Math.min(100, Math.max(0, m.time / Math.max(1, active.duration) * 100))}%` }} />
        <input ref={waveInput} className="playback-range" type="range" aria-label="재생 위치 선택, Enter로 이 시점에 댓글 작성" aria-valuetext={formatTime(m.time)} min={0} max={active.duration} step={1} value={m.time} onChange={e => { const at = Number(e.target.value); m.seek(at); if (!draft) setPoint(at); }} onPointerUp={e => { const at = Number(e.currentTarget.value); setSelectedTime(at); setPoint(at); }} onKeyDown={e => {if (e.key === 'Enter') {e.preventDefault(); start(undefined, Number(e.currentTarget.value));}}} />
      </div>
      <div className="moment-heart-scroll" aria-label="시간별 댓글">
        <div ref={markerRail} className="moment-heart-rail" style={{ height: markers.length ? laneEnds.length * 44 : 0 }}>
          {markers.map(({ at, comments, left, lane }) => <button key={at} className="moment-heart-marker" style={{ left: left + '%', top: lane * 44 }} aria-expanded={selectedTime === at} aria-label={formatTime(at) + ' 댓글 ' + comments.length + '개'} onClick={() => { setSelectedTime(at); setPoint(at); m.seek(at); }}>
            <Heart size={19} fill="currentColor" />
            {comments.length > 1 && <small>{comments.length}</small>}
          </button>)}
        </div>
      </div>
      {/* 팝오버가 파형을 덮으면 숫자만 남아 무슨 시간인지 알 수 없다. 이름표를 붙인다. */}
      <div className="time-labels"><span aria-label={`현재 재생 시간 ${formatTime(m.time)}`}>{formatTime(m.time)}</span><span aria-label={`전체 재생 시간 ${formatTime(active.duration)}`}>{formatTime(active.duration)}</span></div>
      {selectedTime !== null && <div className="wave-comment-popover selected-moment-popover" key={selectedTime} role="region" aria-label="선택한 시점의 댓글">
        <header className="wave-comment-popover-head"><span>{formatTime(selectedTime)} · 댓글 {selectedComments.length}개</span><button className="icon-button" aria-label="댓글 닫기" onClick={() => setSelectedTime(null)}><X size={18} /></button></header>
        <div className="selected-moment-messages">
          {selectedComments.length ? selectedComments.map(c => <article key={c.id}>
            <div className="wave-comment-line"><span className="moment-author-avatar" aria-hidden="true"><CommentAvatar comment={c} /></span><strong>{c.author}</strong><span className="moment-comment-timestamp">{formatTime(c.at)}</span>{ownsComment(c, userId) && <button className="text-link moment-edit" onClick={() => start(c)}>수정</button>}</div>
            <p>{c.body}</p>
          </article>) : <p className="moment-popup-empty">이 순간에 첫 글을 남겨보세요.</p>}
        </div>
        <button className="moment-popup-write" onClick={() => start(undefined, selectedTime)}><Clock3 size={16} />이 순간에 글 남기기</button>
      </div>}

    </div>
    <PlayerControls model={m} />
    {!ready && !error && <output>댓글을 불러오고 있어요…</output>}
    {error && <p role="alert">{error} {!ready && <button className="text-link" onClick={() => {setError(''); setReload(v => v + 1);}}>다시 불러오기</button>}</p>}
    {draft && <form ref={bubble} className="wave-comment-composer" role="dialog" aria-label={`${formatTime(draft.at)} 댓글 ${draft.id ? '수정' : '작성'}`} onSubmit={async e => {
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
      <label htmlFor="moment-comment"><Clock3 size={16} /> {formatTime(draft.at)} · {draft.id ? '댓글 수정' : '이 시점에 남기기'}</label>
      <textarea ref={input} id="moment-comment" value={body} onChange={e => {setBody(e.target.value); if (!draft.id) pendingDrafts.set(active.id, {at: draft.at, body: e.target.value, owner: userId});}} maxLength={100} rows={2} disabled={busy} placeholder="이 순간, 어떤 느낌이 들었나요?" required />
      <footer><span>{body.length}/100</span><button type="button" className="text-link" disabled={busy} onClick={() => {pendingDrafts.delete(active.id); setDraft(null); setBody('');}}>취소</button><button className="primary-button" disabled={!ready || busy || !body.trim() || (userId !== null && !available)}>{busy ? '저장 중…' : userId === null ? '로그인하고 등록' : draft.id ? '수정 저장' : '댓글 등록'}</button></footer>
    </form>}
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
