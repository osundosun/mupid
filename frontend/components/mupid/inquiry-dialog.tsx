'use client';
import { useState } from 'react';
import { Mail, MessageSquareText } from 'lucide-react';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { api } from '@/lib/api/session';
import type { MupidModel } from '@/hooks/use-mupid';

const BODY_MAX = 1000;

/**
 * 문의하기. 홈 인사말 오른쪽의 버튼과, 누르면 뜨는 대화상자.
 *
 * 내용만 받아 `POST /api/v1/inquiries` 로 보낸다. 답변은 가입한 메일로 간다. 보내는 동안은 버튼을 잠그고,
 * 성공하면 닫고 토스트로 알린다. 실패하면 쓴 글을 지우지 않고 대화상자 안에서 알린다.
 */
export function InquiryButton({ model: m }: { model: MupidModel }) {
  const [open, setOpen] = useState(false);
  const [body, setBody] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const ready = body.trim().length > 0;

  const close = () => { setOpen(false); setBody(''); setError(''); };
  const submit = async () => {
    if (!ready || busy) return;
    setBusy(true); setError('');
    try {
      await api.inquiry.send(body.trim());
      close();
      m.setToast('문의를 보냈어요. 확인하고 답변드릴게요.');
    } catch (e) {
      setError(e instanceof Error && e.message ? e.message : '문의를 보내지 못했어요. 잠시 뒤 다시 시도해 주세요.');
    } finally {
      setBusy(false);
    }
  };

  return <>
    <button type="button" className="inquiry-button" onClick={() => setOpen(true)}>
      <MessageSquareText aria-hidden="true" />
      문의하기
    </button>
    <Dialog open={open} onOpenChange={value => { if (!value && !busy) close(); }}>
      <DialogContent className="sky-dialog inquiry-dialog">
        <DialogHeader>
          <DialogTitle>문의하기</DialogTitle>
          <DialogDescription>불편한 점이나 바라는 점을 알려주세요.</DialogDescription>
        </DialogHeader>
        <form onSubmit={e => { e.preventDefault(); void submit(); }}>
          <label className="field-label" htmlFor="inquiry-body">내용</label>
          <textarea
            id="inquiry-body"
            rows={5}
            maxLength={BODY_MAX}
            placeholder="언제, 어떤 화면에서 무슨 일이 있었는지 적어주시면 빨리 살펴볼 수 있어요."
            value={body}
            onChange={e => setBody(e.target.value)}
            disabled={busy}
            required
          />
          <span className="inquiry-count">{body.length}/{BODY_MAX}</span>
          <p className="inquiry-note"><Mail aria-hidden="true" />답변은 가입한 메일로 발송돼요.</p>
          {error && <p className="inquiry-error" role="alert">{error}</p>}
          <DialogFooter className="inquiry-actions">
            <button type="button" className="secondary-button" onClick={close} disabled={busy}>취소</button>
            <button type="submit" className="primary-button" disabled={!ready || busy}>{busy ? '보내는 중…' : '전송'}</button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  </>;
}
