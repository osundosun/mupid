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
import { TrackRow } from './primitives';
import { Pagination, PaginationContent, PaginationItem } from '@/components/ui/pagination';
export function AppDialogs({ model: m }: { model: MupidModel }) {
  const [name, setName] = useState('');
  const [queuePage, setQueuePage] = useState(0);
  const pageCount = Math.max(1, Math.ceil(m.queueTracks.length / 4));
  const currentPage = Math.min(queuePage, pageCount - 1);
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
              m.createPlaylist(name);
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
              이 목록만 삭제되며, 좋아요한 음악과 저장한 구간은 남아 있어요.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                m.setPlaylists((list) =>
                  list.filter((p) => p.id !== m.deletePlaylist),
                );
                m.setSelectedPlaylist(null);
                m.setDeletePlaylist(null);
                m.setToast('플레이리스트를 삭제했어요.');
              }}
            >
              삭제
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
      <Dialog open={m.queueDialog} onOpenChange={(open) => { m.setQueueDialog(open); if (!open) setQueuePage(0); }}>
        <DialogContent className="sky-dialog queue-dialog">
          <DialogHeader>
            <DialogTitle>이어 들을 음악</DialogTitle>
            <DialogDescription>
              마음에 드는 곡을 눌러 바로 들어보세요.
            </DialogDescription>
          </DialogHeader>
          <div className="track-list">
            {m.queueTracks.slice(currentPage * 4, currentPage * 4 + 4).map((t) => (
              <TrackRow track={t} model={m} key={t.id} />
            ))}
          </div>
          <Pagination aria-label="이어 들을 음악 페이지" className="queue-pagination">
            <PaginationContent>
              <PaginationItem><button disabled={currentPage === 0} onClick={() => setQueuePage(currentPage - 1)}>이전</button></PaginationItem>
              {Array.from({ length: pageCount }, (_, page) => <PaginationItem key={page}>
                <button aria-label={`${page + 1}페이지`} aria-current={currentPage === page ? 'page' : undefined}
                  onClick={() => setQueuePage(page)}>{page + 1}</button>
              </PaginationItem>)}
              <PaginationItem><button disabled={currentPage === pageCount - 1} onClick={() => setQueuePage(currentPage + 1)}>다음</button></PaginationItem>
            </PaginationContent>
          </Pagination>
        </DialogContent>
      </Dialog>
    </>
  );
}
