'use client';
import { useState } from 'react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
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
export function AppDialogs({ model: m }: { model: MupidModel }) {
  const [name, setName] = useState('');
  const renaming = m.playlists.find((p) => p.id === m.renamePlaylist);
  return (
    <>
      <Dialog
        open={m.playlistDialog}
        onOpenChange={(open) => {
          m.setPlaylistDialog(open);
          if (!open) setName('');
        }}
      >
        <DialogContent className="sky-dialog">
          <DialogHeader>
            <DialogTitle>새 플레이리스트</DialogTitle>
            <DialogDescription>
              어떤 이름으로 이 음악들을 기억할까요?
            </DialogDescription>
          </DialogHeader>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void m.createPlaylist(name);
              setName('');
            }}
          >
            <label className="field-label" htmlFor="playlist-name">
              플레이리스트 이름
            </label>
            <input
              id="playlist-name"
              maxLength={40}
              placeholder="예: 구름 위를 걷는 기분"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
            />
            <button
              className="primary-button wide"
              type="submit"
              disabled={!name.trim()}
            >
              만들기
            </button>
          </form>
        </DialogContent>
      </Dialog>
      <Dialog
        open={m.renamePlaylist !== null}
        onOpenChange={(open) => { if (!open) m.setRenamePlaylist(null); }}
      >
        <DialogContent className="sky-dialog">
          <DialogHeader>
            <DialogTitle>플레이리스트 이름 바꾸기</DialogTitle>
            <DialogDescription>담긴 음악은 그대로예요.</DialogDescription>
          </DialogHeader>
          {/* key로 다시 마운트해 현재 이름을 기본값으로 채운다. */}
          <form
            key={m.renamePlaylist}
            onSubmit={(e) => {
              e.preventDefault();
              const input = e.currentTarget.elements.namedItem('playlist-rename');
              const trimmed = input instanceof HTMLInputElement ? input.value.trim() : '';
              if (!trimmed || m.renamePlaylist === null) return;
              void m.applyPlaylistRename(m.renamePlaylist, trimmed);
              m.setRenamePlaylist(null);
            }}
          >
            <label className="field-label" htmlFor="playlist-rename">
              플레이리스트 이름
            </label>
            <input
              id="playlist-rename"
              name="playlist-rename"
              maxLength={40}
              defaultValue={renaming?.name ?? ''}
              required
            />
            <button className="primary-button wide" type="submit">
              저장
            </button>
          </form>
        </DialogContent>
      </Dialog>
      <AlertDialog
        open={m.deletePlaylist !== null}
        onOpenChange={(open) => {
          if (!open) m.setDeletePlaylist(null);
        }}
      >
        <AlertDialogContent className="sky-dialog">
          <AlertDialogHeader>
            <AlertDialogTitle>플레이리스트를 삭제할까요?</AlertDialogTitle>
            <AlertDialogDescription>
              이 목록만 삭제되며, 좋아요한 음악과 저장한 순간은 남아 있어요.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (m.deletePlaylist !== null) void m.removePlaylist(m.deletePlaylist);
                m.setDeletePlaylist(null);
              }}
            >
              삭제
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
