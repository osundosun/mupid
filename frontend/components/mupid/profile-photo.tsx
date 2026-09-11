'use client';
import { useEffect, useRef, useState } from 'react';
import { Camera, Trash2 } from 'lucide-react';
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
  return <div className={`profile-photo-editor ${photo ? 'has-photo' : ''}`}>
    {/* 눌러서 바꾸는 자리라는 걸 알 수 있게 사진 위에 카메라 배지를 얹는다. */}
    <button type="button" className="profile-photo-pick" aria-label={photo ? '프로필 사진 바꾸기' : '프로필 사진 등록하기'} onClick={() => input.current?.click()} disabled={busy}>
      <span className="profile-photo-frame">
        {photo ? <img src={photo} alt="내 프로필 사진" width={116} height={116} /> : <MupidMascot size={104} alt="기본 프로필" />}
      </span>
      <span className="profile-photo-badge" aria-hidden="true"><Camera /></span>
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
    <div className="profile-photo-actions">
      <button className="secondary-button profile-photo-main" disabled={busy} onClick={() => input.current?.click()}>
        <Camera />{busy ? '저장 중…' : photo ? '사진 변경' : '사진 등록'}
      </button>
      {photo && <button className="profile-photo-remove" disabled={busy} onClick={async () => { setBusy(true); try { await mupidService.removePhoto(); setPhoto(''); setMessage('기본 프로필로 변경했어요.'); } catch { setMessage('삭제하지 못했어요. 다시 시도해 주세요.'); } finally { setBusy(false); } }}>
        <Trash2 />삭제
      </button>}
    </div>
    <small>{mupidService.mode === 'demo' ? '이 브라우저에만 저장됩니다' : '내 계정에 저장됩니다'}</small>
    <output>{message}</output>
  </div>;
}
