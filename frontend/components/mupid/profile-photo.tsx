'use client';
import { useEffect, useRef, useState } from 'react';
import { MupidMascot } from './mascot';
import { mupidService } from '@/lib/services';

export function ProfilePhoto() {
  const [photo, setPhoto] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(true);
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => {
    let live = true;
    void mupidService.readPhoto().then(value => { if (live) setPhoto(value); })
      .catch(() => { if (live) setMessage('사진을 불러오지 못했어요.'); })
      .finally(() => { if (live) setBusy(false); });
    return () => { live = false; };
  }, []);
  return <div className="profile-photo-editor">
    <button type="button" aria-label="프로필 사진 선택" onClick={() => input.current?.click()} disabled={busy}>
      {photo ? <img src={photo} alt="내 프로필 사진" width={116} height={116} style={{ width: 116, height: 116, objectFit: 'cover', borderRadius: '50%' }} /> : <MupidMascot size={116} alt="기본 프로필" />}
    </button>
    <input ref={input} type="file" accept="image/jpeg,image/png,image/webp" hidden onChange={async event => {
      const file = event.target.files?.[0]; event.target.value = '';
      if (!file) return;
      if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type) || file.size > 5 * 1024 * 1024) { setMessage('JPG, PNG, WebP 사진을 5MB 이하로 선택해 주세요.'); return; }
      setBusy(true); setMessage('');
      const url = URL.createObjectURL(file);
      try {
        const image = new Image(); image.src = url; await image.decode();
        if (image.naturalWidth * image.naturalHeight > 40000000) throw new Error('large');
        const canvas = document.createElement('canvas'); canvas.width = canvas.height = 256;
        const context = canvas.getContext('2d'); if (!context) throw new Error('canvas');
        context.fillStyle = '#f3f7ff'; context.fillRect(0, 0, 256, 256);
        const edge = Math.min(image.naturalWidth, image.naturalHeight);
        context.drawImage(image, (image.naturalWidth - edge) / 2, (image.naturalHeight - edge) / 2, edge, edge, 0, 0, 256, 256);
        const data = canvas.toDataURL('image/jpeg', .85);
        await mupidService.savePhoto(data); setPhoto(data); setMessage('프로필 사진을 저장했어요.');
      } catch { setMessage('사진을 저장하지 못했어요. 다른 사진으로 다시 시도해 주세요.'); }
      finally { URL.revokeObjectURL(url); setBusy(false); }
    }} />
    <div><button className="text-link" disabled={busy} onClick={() => input.current?.click()}>{busy ? '저장 중…' : photo ? '사진 변경' : '사진 등록'}</button>
      {photo && <button className="text-link" disabled={busy} onClick={async () => { setBusy(true); try { await mupidService.removePhoto(); setPhoto(''); setMessage('기본 프로필로 변경했어요.'); } catch { setMessage('삭제하지 못했어요. 다시 시도해 주세요.'); } finally { setBusy(false); } }}>삭제</button>}
    </div>
    <small>{mupidService.mode === 'demo' ? '이 브라우저에만 저장됩니다' : '내 계정에 저장됩니다'}</small>
    <p role="status">{message}</p>
  </div>;
}
