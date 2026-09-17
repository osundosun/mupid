'use client';
import { useState } from 'react';
import { ChevronDown, LockKeyhole } from 'lucide-react';
import { api } from '@/lib/api/session';
import { localDemoEnabled } from '@/lib/api/local-demo';

export function PasswordForm() {
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const local = localDemoEnabled();
  return <details className="profile-password" open={local || undefined}>
    <summary><span><LockKeyhole size={18} />비밀번호 변경</span><ChevronDown size={18} /></summary>
    <form onSubmit={async event => {
      event.preventDefault();
      if (busy || local) return;
      if (password !== confirm) { setMessage('새 비밀번호가 일치하지 않아요.'); return; }
      setBusy(true); setMessage('');
      try {
        await api.user.update({ password });
        setPassword(''); setConfirm(''); setMessage('비밀번호를 변경했어요.');
      } catch (error) { setMessage(error instanceof Error ? error.message : '변경하지 못했어요. 다시 시도해 주세요.'); }
      finally { setBusy(false); }
    }}>
      <label htmlFor="profile-new-password">새 비밀번호</label>
      <input id="profile-new-password" type="password" autoComplete="new-password" required value={password} onChange={e => setPassword(e.target.value)} disabled={busy} />
      <label htmlFor="profile-confirm-password">새 비밀번호 확인</label>
      <input id="profile-confirm-password" type="password" autoComplete="new-password" required value={confirm} onChange={e => setConfirm(e.target.value)} disabled={busy} />
      {local && <p className="demo-note">데모 계정에서는 비밀번호 변경을 지원하지 않아요.</p>}
      {message && <p role="status">{message}</p>}
      <button className="primary-button" disabled={busy || local || !password || !confirm}>{busy ? '변경 중…' : '비밀번호 변경'}</button>
    </form>
  </details>;
}
