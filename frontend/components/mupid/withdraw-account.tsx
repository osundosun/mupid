'use client';
import { useRef, useState } from 'react';
import type { MupidModel } from '@/hooks/use-mupid';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '@/components/ui/alert-dialog';

export function WithdrawAccount({ model }: { model: MupidModel }) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const pending = useRef(false);
  return <div className="profile-withdraw" style={{ marginTop: 24 }}>
    <button type="button" className="text-link" onClick={() => { setError(''); setOpen(true); }}>회원탈퇴</button>
    <AlertDialog open={open} onOpenChange={value => { if (!pending.current) setOpen(value); }}>
      <AlertDialogContent className="sky-dialog">
        <AlertDialogHeader>
          <AlertDialogTitle>회원탈퇴를 진행할까요?</AlertDialogTitle>
          <AlertDialogDescription>탈퇴하면 이 계정으로 로그인할 수 없으며, 계정에 저장한 음악과 활동 기록을 이용할 수 없어요.</AlertDialogDescription>
        </AlertDialogHeader>
        {error && <p role="alert">{error}</p>}
        <AlertDialogFooter>
          <AlertDialogCancel disabled={busy}>취소</AlertDialogCancel>
          <AlertDialogAction disabled={busy} onClick={async event => {
            event.preventDefault();
            if (pending.current) return;
            pending.current = true;
            setBusy(true); setError('');
            try {
              await model.withdrawMembership();
              setOpen(false);
            } catch (cause) {
              setError(cause instanceof Error ? cause.message : '회원탈퇴를 처리하지 못했어요. 다시 시도해 주세요.');
            } finally {
              pending.current = false;
              setBusy(false);
            }
          }}>{busy ? '탈퇴 처리 중…' : '회원탈퇴'}</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  </div>;
}
