'use client';
import { useState } from 'react';
import { EllipsisVertical, Pencil, Trash2 } from 'lucide-react';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from '@/components/ui/dialog';
import type { MupidModel } from '@/hooks/use-mupid';
import type { Playlist } from '@/lib/mupid-data';

// 목록에서 바로 이름을 바꾸거나 지운다. 상세로 들어가야만 보이던 버튼을 카드에도 둔다.
// 이름 바꾸기·삭제 창은 앱 전역에 하나씩 있으니 여기서는 열어 주기만 한다.
export function PlaylistMenu({ model: m, playlist }: { model: MupidModel; playlist: Playlist }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button
        type="button"
        className="playlist-card-menu"
        aria-label={`${playlist.name} 더보기`}
        onClick={() => setOpen(true)}
      >
        <EllipsisVertical />
      </button>
      <Dialog open={open} onOpenChange={(value) => { if (!value) setOpen(false); }}>
        <DialogContent className="sky-dialog track-menu-sheet">
          <DialogHeader>
            <DialogTitle>{playlist.name}</DialogTitle>
            <DialogDescription>{playlist.tracks.length}곡</DialogDescription>
          </DialogHeader>
          <div className="track-menu-options">
            <button onClick={() => { setOpen(false); m.setRenamePlaylist(playlist.id); }}>
              <Pencil />이름 바꾸기
            </button>
            <button onClick={() => { setOpen(false); m.setDeletePlaylist(playlist.id); }}>
              <Trash2 />삭제
            </button>
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}
