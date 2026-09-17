'use client';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { formatTime, type Track } from '@/lib/mupid-data';

export function TrackInfoDialog({ track, open, onOpenChange, onArtist }: {
  track: Track | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onArtist: (artist: string) => void;
}) {
  return <Dialog open={open} onOpenChange={onOpenChange}>
    <DialogContent className="sky-dialog track-info-dialog">
      <DialogHeader>
        <DialogTitle>곡 정보</DialogTitle>
        <DialogDescription>{track?.title} · {track?.artist}</DialogDescription>
      </DialogHeader>
      {track && <>
        <dl className="track-info-fields">
          <div><dt>곡</dt><dd>{track.title}</dd></div>
          <div><dt>아티스트</dt><dd><button className="track-artist-link" onClick={() => { onOpenChange(false); onArtist(track.artist); }}>{track.artist}</button></dd></div>
          <div><dt>앨범</dt><dd>{track.album || '앨범 정보 없음'}</dd></div>
          <div><dt>재생 시간</dt><dd>{formatTime(track.duration)}</dd></div>
        </dl>
        <div className="tags">{[...new Set(track.tags.filter(Boolean))].map(t => <span key={t}>{t === 'contemporary_pop' ? '컨템포러리 팝' : t === 'bright' ? '밝은' : t.replaceAll('_', ' ')}</span>)}</div>
      </>}
    </DialogContent>
  </Dialog>;
}
