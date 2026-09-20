'use client';
import { useEffect, useState } from 'react';
import { api } from '@/lib/api/session';
import { toImageSource } from '@/lib/platform';
import { toTrack } from '@/lib/stream-catalog';
import { ChevronLeft, ChevronRight, Disc3, Music2, UserRound } from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
import type { Track } from '@/lib/mupid-data';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
import { AlbumArt } from './primitives';

export function ArtistInfo({ model: m }: { model: MupidModel }) {
  return <Dialog open={m.selectedArtist !== null} onOpenChange={open => { if (!open) m.setSelectedArtist(null); }}>
    <DialogContent className="sky-dialog artist-info-dialog">
      <ArtistContent key={m.selectedArtist} model={m} />
    </DialogContent>
  </Dialog>;
}

function ArtistContent({ model: m }: { model: MupidModel }) {
  const [tab, setTab] = useState('info');
  const [album, setAlbum] = useState<string | null>(null);
  // 추천·대기열의 곡은 초기 카탈로그 100곡에 없을 수도 있다.
  const knownTracks = [...m.tracks, ...(m.active ? [m.active] : [])]
    .filter(track => track.artist === m.selectedArtist);
  const [remoteTracks, setRemoteTracks] = useState<Track[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [retry, setRetry] = useState(0);
  const tracks = [...new Map([...knownTracks, ...remoteTracks].map(track => [track.id, track])).values()];
  const knownArtistId = knownTracks.find(track => track.artistId !== undefined)?.artistId;
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setLoadFailed(false);
    const load = async () => {
      const found: Track[] = [];
      let page = 0;
      let totalPages = 1;
      while (page < totalPages) {
        const result = await api.music.search({ q: m.selectedArtist || '', page, size: 100 }, controller.signal);
        totalPages = result.total_pages;
        found.push(...result.items.filter(music => knownArtistId !== undefined
          ? music.artist.artist_id === knownArtistId : music.artist.name === m.selectedArtist).map(toTrack));
        page++;
      }
      if (!controller.signal.aborted) setRemoteTracks(found);
    };
    void load().catch(() => { if (!controller.signal.aborted) setLoadFailed(true); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [m.selectedArtist, knownArtistId, retry]);
  const artistId = tracks.find(track => track.artistId !== undefined)?.artistId;
  const catalogImage = tracks.find(track => track.artistImage)?.artistImage || '';
  const [profileImage, setProfileImage] = useState<string | null>(null);
  const [failedImage, setFailedImage] = useState<string | null>(null);
  useEffect(() => {
    setProfileImage(null);
    if (artistId === undefined) return;
    const controller = new AbortController();
    void api.artist.get(artistId, controller.signal).then(artist => {
      if (!controller.signal.aborted) setProfileImage(toImageSource(artist.image_url?.trim()));
    }).catch(() => { /* 상세 조회 실패 시 곡 목록에 포함된 등록 이미지를 유지한다. */ });
    return () => controller.abort();
  }, [artistId]);
  const image = profileImage ?? catalogImage;
  const albums = [...new Set(tracks.map(track => track.album))];
  const genres = [...new Set(tracks.map(track => track.genre).filter(Boolean))];
  const song = (track: Track) => <button className="artist-song" key={track.id} onClick={() => { m.setSelectedArtist(null); m.openTrack(track); }}>
    <AlbumArt track={track} /><span>{track.title}</span><ChevronRight size={18} aria-hidden="true" />
  </button>;
  return <>
    <div className="artist-profile-header">
      <div className="artist-profile-photo">
        {image && image !== failedImage
          ? <img src={image} alt={`${m.selectedArtist} 프로필`} width={72} height={72} onError={() => setFailedImage(image)} />
          : <UserRound size={36} role="img" aria-label="등록된 프로필 이미지 없음" />}
      </div>
      <DialogHeader><DialogTitle>{m.selectedArtist}</DialogTitle><DialogDescription>아티스트의 음악과 앨범을 둘러보세요.</DialogDescription></DialogHeader>
    </div>
    {loading && <p role="status">곡과 앨범 정보를 불러오고 있어요.</p>}
    {loadFailed && <p role="alert">곡과 앨범 정보를 불러오지 못했어요. <button className="text-link" onClick={() => setRetry(value => value + 1)}>다시 시도</button></p>}
    {!loading && !loadFailed && tracks.length === 0 && <p>등록된 곡과 앨범이 없습니다.</p>}
    <Tabs className="sky-tabs artist-tabs" value={tab} onValueChange={value => { setTab(value); setAlbum(null); }}>
      <TabsList aria-label="아티스트 정보 메뉴">
        <TabsTrigger value="info">아티스트 정보</TabsTrigger>
        <TabsTrigger value="songs">곡</TabsTrigger>
        <TabsTrigger value="albums">앨범</TabsTrigger>
      </TabsList>
      <TabsContent value="info">
        <div className="artist-overview">
          <div className="artist-stat-grid">
            <button onClick={() => setTab('songs')}><Music2 size={18} /><span>곡</span><strong>{tracks.length}</strong><ChevronRight size={16} /></button>
            <button onClick={() => setTab('albums')}><Disc3 size={18} /><span>앨범</span><strong>{albums.length}</strong><ChevronRight size={16} /></button>
          </div>
          {genres.length > 0 && <div className="artist-genres"><h3>장르</h3><div className="tags">{genres.map(genre => <span key={genre}>{genre === 'contemporary_pop' ? '컨템포러리 팝' : genre.replaceAll('_', ' ')}</span>)}</div></div>}
          <p className="artist-info-note">현재 등록된 음악 기준</p>
        </div>
      </TabsContent>
      <TabsContent value="songs"><div className="artist-song-list">{tracks.map(song)}</div></TabsContent>
      <TabsContent value="albums">
        {album === null ? <div className="artist-album-grid">{albums.map(name => {
          const songs = tracks.filter(track => track.album === name);
          return <button className="artist-album-card" key={name} onClick={() => setAlbum(name)}>
            <AlbumArt track={songs[0]} /><strong>{name || '앨범 정보 없음'}</strong><span>{songs.length}곡</span>
          </button>;
        })}</div> : <section className="artist-album-detail">
          <button className="text-link" onClick={() => setAlbum(null)}><ChevronLeft size={18} />앨범 목록</button>
          <h3>{album || '앨범 정보 없음'}</h3>
          <div className="artist-song-list">{tracks.filter(track => track.album === album).map(song)}</div>
        </section>}
      </TabsContent>
    </Tabs>
  </>;
}
