'use client';
import { ThemeToggle } from './theme';
import { WeeklyMubtiButton } from './listening-calendar';
import { DailyAlbumTreemap } from './daily-album-treemap';

import { PlaylistMenu } from './playlist-menu';
import { SelectionToolbar, useTrackSelection } from './track-selection';
import { MupidMascot } from './mascot';
import { toImageSource } from '@/lib/platform/image';
import { ProfilePhoto } from './profile-photo';
import { DEFAULT_PROFILE_PHOTO } from './profile-image';
import { PasswordForm } from './password-form';
import { WithdrawAccount } from './withdraw-account';
import { useEffect, useMemo, useState, type ComponentProps } from 'react';
import { useTrackWaveform } from '@/lib/track-waveform';
import { useDismissableOverlay } from '@/hooks/use-dismissable-overlay';
import type { Track } from '@/lib/mupid-data';
import { DemoAccount } from './demo-login';
import {
  Check,
  ChevronLeft,
  ChevronRight,
  Heart,
  Pause,
  Plus,
  Pencil,
  Play,
  Trash2,
  X,
} from 'lucide-react';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
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
import { type MupidModel } from '@/hooks/use-mupid';
import { NICKNAME_MAX_LENGTH, NICKNAME_MIN_LENGTH, normalizeNickname } from '@/lib/profile';
import { useDailyReport } from '@/lib/report';
import { formatTime, genreOptions, moodOptions } from '@/lib/mupid-data';
import { groupSavedMoments } from '@/lib/saved-moments';
import {
  loadOnboardingOptions,
  namesOf,
  OnboardingOptionsStale,
  refreshOnboardingOptions,
} from '@/lib/onboarding';
import {
  PlaylistArt,
  Brand,
  EmptyState,
  Heading,
  PageHeader,
  PlayerControls,
  TrackRow,
  Waveform,
} from './primitives';

function SavedMomentWaveform({ track, ...props }: { track: Track } & ComponentProps<typeof Waveform>) {
  const waveform = useTrackWaveform(track);
  return <>
    <Waveform {...props} samples={waveform.values ?? undefined} />
    {waveform.failed
      ? <span role="status">파형을 분석하지 못했어요.</span>
      : waveform.progress < 1 && <span role="status">파형 분석 중 {Math.round(waveform.progress * 100)}%</span>}
  </>;
}

// 탭 이름을 그대로 화면 제목으로 쓴다. 어느 탭에 있는지 제목만 봐도 알 수 있게.
const LIBRARY_TAB_TITLES: Record<string, string> = {

  playlists: '플레이리스트',
  moments: '저장한 순간',
  liked: '좋아한 곡',
};
export function LibraryView({ model: m }: { model: MupidModel }) {
  const tracks = m.tracks;
  const selected = m.playlists.find((p) => p.id === m.selectedPlaylist);
  /*
   * 지금 울리는 곡이 이 목록의 곡인가.
   *
   * 눈금과 재생 아이콘은 이 값이 있을 때만 살린다. 전에는 전역 플레이어
   * (`m.time`·`m.active`)를 그대로 비춰서, 목록에 없는 곡이 돌 때도 눈금이
   * 같이 움직이고 이 목록이 재생 중인 것처럼 일시정지 모양이 됐다.
   */
  const playingTrack = m.active && selected?.tracks.some((t) => t.id === m.active!.id) ? m.active : null;
  const momentGroups = useMemo(() => groupSavedMoments(m.moments), [m.moments]);
  // 화면은 곡별 한 줄이므로 삭제도 그 곡에 저장한 모든 순간을 대상으로 확인한다.
  const [removeMomentTrack, setRemoveMomentTrack] = useState<number | null>(null);
  const momentGroupToRemove = momentGroups.find((group) => group.track.id === removeMomentTrack);
  const [openMomentPlayer, setOpenMomentPlayer] = useState<number | null>(null);
  const [focusedMoment, setFocusedMoment] = useState<number | null>(null);
  // 플레이리스트에서 여러 곡을 골라 한 번에 뺄 수 있게 한다.
  // 재생 목록 쪽 같은 흐름은 독이 들고 있다 (`components/mupid/queue-dock.tsx`).
  const playlistPick = useTrackSelection((selected?.tracks ?? []).map((t) => t.id));
  // 곡을 목록에서 빼는 건 한 곡이든 여러 곡이든 똑같이 한 번 확인받는다.
  const [confirmRemove, setConfirmRemove] = useState<number[] | null>(null);
  /*
   * 뒤로가기로 닫히게 한다 (S15P21A204-410). 확인 대화상자 둘은 목록 위에
   * 겹쳐 열리므로 위에 등록해 두었다 — 먼저 닫힌다. 확인 창이 떠 있는 동안
   * 곡을 고르는 상태는 그대로 남고, 확인 창만 사라진다(취소와 같은 결과).
   */
  useDismissableOverlay(removeMomentTrack !== null, () => setRemoveMomentTrack(null));
  useDismissableOverlay(confirmRemove !== null, () => setConfirmRemove(null));
  const removeTargets = (selected?.tracks ?? []).filter((t) => confirmRemove?.includes(t.id));
  return (
    <div className="content-page collection-page" data-playlist-detail={Boolean(selected && m.libraryTab === 'playlists')}>
      {!(selected && m.libraryTab === 'playlists') && <div className="desktop-library-page-heading">
        <PageHeader title="보관함" onBack={() => m.go('home')} />
      </div>}
      <PageHeader
        title={
          selected && m.libraryTab === 'playlists'
            ? selected.name
            : (LIBRARY_TAB_TITLES[m.libraryTab] ?? '보관함')
        }
        onBack={() => {
          if (selected) m.setSelectedPlaylist(null);
          else m.go('home');
        }}
        aside={
          selected && m.libraryTab === 'playlists' ? (
            <>
              <button
                aria-label="플레이리스트 이름 바꾸기"
                className="icon-button"
                onClick={() => m.setRenamePlaylist(selected.id)}
              >
                <Pencil />
              </button>
              <button
                aria-label="플레이리스트 삭제"
                className="icon-button"
                onClick={() => m.setDeletePlaylist(selected.id)}
              >
                <Trash2 />
              </button>
            </>
          ) : undefined
        }
      />
      <Tabs
        value={m.libraryTab}
        onValueChange={(v) => {
          m.setLibraryTab(String(v));
          m.setSelectedPlaylist(null);
        }}
        className="sky-tabs"
      >
        {/*
          '재생목록' 탭은 없앴다. 목록을 보려고 듣던 화면을 떠나야 했던 것이 문제였고,
          지금은 미니 플레이어의 목록 버튼이 독을 연다 (`components/mupid/queue-dock.tsx`).
        */}
        <TabsList>
          <TabsTrigger value="playlists">플레이리스트</TabsTrigger>
          <TabsTrigger value="moments">저장한 순간</TabsTrigger>
          <TabsTrigger value="liked">좋아한 곡</TabsTrigger>
        </TabsList>
        <TabsContent value="playlists">
          {selected ? (
            <div className="playlist-detail">
              <div className="playlist-summary">
                <PlaylistArt track={selected.tracks[0]} name={selected.name} />
                <div>
                  <span className="eyebrow">내 플레이리스트</span>
                  <h2>{selected.name}</h2>
                  <p>
                    {selected.tracks.length}곡 ·{' '}
                    {Math.ceil(
                      selected.tracks.reduce((sum, t) => sum + t.duration, 0) /
                        60,
                    )}
                    분
                  </p>
                  {selected.tracks.length > 0 && <div className="playlist-transport" aria-label="플레이리스트 재생">
                    {playingTrack ? <>
                      <PlayerControls model={m} />
                      <div className="playlist-seek">
                        <span>{formatTime(m.time)}</span>
                        <input type="range" min={0} max={playingTrack.duration} step={1} value={Math.min(m.time, playingTrack.duration)} aria-label="재생 위치" onChange={event => m.seek(Number(event.target.value))} />
                        <span>{formatTime(playingTrack.duration)}</span>
                      </div>
                    </> : <button className="primary-button playlist-play-all" onClick={() => m.playPlaylist(selected)}>
                      <Play fill="currentColor" />전체 재생
                    </button>}
                  </div>}
                </div>
              </div>
              <div className="playlist-tracks">
              <div className="playlist-tracks-heading"><h3>수록곡</h3></div>
              {!!selected.tracks.length && (
                <SelectionToolbar
                  selection={playlistPick}
                  total={selected.tracks.length}
                  onDelete={() => setConfirmRemove(playlistPick.picked)}
                />
              )}
              <div className="track-list">
                {selected.tracks.map((t) => (
                  <TrackRow
                    key={t.id}
                    track={t}
                    model={m}
                    selected={playlistPick.has(t.id)}
                    showDetail={false}
                    onSelect={playlistPick.active ? () => playlistPick.toggle(t.id) : undefined}
                    onPlay={() => m.playPlaylist(selected, t)}
                    action={
                      <button
                        className="icon-button"
                        aria-label={`${t.title} 플레이리스트에서 빼기`}
                        onClick={() => setConfirmRemove([t.id])}
                      >
                        <X />
                      </button>
                    }
                  />
                ))}
              </div>
              {/* 빈 안내는 '수록곡' 자리 안에 둔다. 밖에 두면 PC 에서 왼쪽 카드 밑으로 내려가 오른쪽이 텅 빈다. */}
              {!selected.tracks.length && (
                <EmptyState
                  title="첫 번째 음악을 담아볼까요?"
                  description="현재 듣는 곡부터 플레이리스트를 채워보세요."
                />
              )}
              </div>
              {/* 듣고 있는 곡이 없으면 담을 것도 없다. */}
              {m.active && <div className="add-current">
                <span>현재 재생 중인 곡</span>
                <strong>{m.active.title}</strong>
                <button
                  className="secondary-button"
                  disabled={selected.tracks.some((t) => t.id === m.active?.id)}
                  onClick={() => {
                    void m.addToPlaylist(selected.id, m.active!);
                    m.setToast('플레이리스트에 음악을 담았어요.');
                  }}
                >
                  {selected.tracks.some((t) => t.id === m.active?.id) ? (
                    <Check />
                  ) : (
                    <Plus />
                  )}
                  추가
                </button>
              </div>}
            </div>
          ) : !m.playlists.length ? (
            /*
             * 플레이리스트가 하나도 없을 때. 격자에 '새 플레이리스트' 칸 하나만 떠 있고
             * 안내는 그 아래 한가운데라 둘이 따로 놀았다. 안내 하나로 모으고 만들기
             * 버튼을 그 안에 둔다. 폰도 PC 도 같다.
             */
            <EmptyState
              title="나만의 첫 플레이리스트"
              description="좋아하는 음악을 한곳에 모아두세요."
            >
              <button className="primary-button empty-state-action" onClick={() => m.setPlaylistDialog(true)}>
                <Plus />새 플레이리스트 만들기
              </button>
            </EmptyState>
          ) : (
            <>
              <div className="playlist-grid">
                {/* 만들기 자리도 목록의 한 칸으로 둔다. 오른쪽에 혼자 뜨면 축이 어긋난다. */}
                <button
                  className="new-playlist"
                  onClick={() => m.setPlaylistDialog(true)}
                >
                  <span className="new-playlist-tile" aria-hidden="true"><Plus /></span>
                  <strong>새 플레이리스트</strong>
                </button>
                {m.playlists.map((p) => (
                  // 카드 버튼 안에 버튼을 넣을 수 없어 더보기는 옆에 두고 겹쳐 놓는다.
                  <div className="playlist-card-wrap" key={p.id}>
                    <button
                      className="playlist-card"
                      onClick={() => m.setSelectedPlaylist(p.id)}
                    >
                      <PlaylistArt track={p.tracks[0]} name={p.name} />
                      <span>
                        <strong>{p.name}</strong>
                        <small>
                          {p.tracks.length}곡 ·{' '}
                          {Math.ceil(
                            p.tracks.reduce((sum, t) => sum + t.duration, 0) / 60,
                          )}
                          분
                        </small>
                      </span>
                      <ChevronRight />
                    </button>
                    <PlaylistMenu model={m} playlist={p} />
                  </div>
                ))}
              </div>
              {!m.playlists.length && (
                <EmptyState
                  title="나만의 첫 플레이리스트"
                  description="좋아하는 음악을 한곳에 모아두세요."
                />
              )}
            </>
          )}
        </TabsContent>
        <TabsContent value="moments">
          <div className="intro-copy">
            <h2>다시 듣고 싶은 그 순간</h2>
            <p>짧지만, 오래 남는 당신의 음악 취향.</p>
          </div>
          <div className="track-list">
            {momentGroups.map(({ track, moments }) => {
              const expanded = openMomentPlayer === track.id;
              const isActive = m.active?.id === track.id;
              const playingMoment = isActive
                ? moments.find((moment) => m.time >= moment.start && m.time < moment.end)
                : undefined;
              const focused = playingMoment ?? moments.find((moment) => moment.id === focusedMoment) ?? moments[0];
              return (
              <article className={`saved-moment ${expanded ? 'is-expanded' : ''}`} key={track.id}>
                <TrackRow
                  track={track}
                  model={m}
                  showDetail={false}
                  onPlay={() => m.playSavedMoments(moments)}
                  playingOverride={Boolean(m.playing && m.savedMomentPlayback?.trackId === track.id && m.savedMomentPlayback.mode === 'all')}
                  action={
                    <>
                      <button aria-label={`${track.title} 저장 순간 모두 삭제`} className="icon-button" onClick={() => setRemoveMomentTrack(track.id)}>
                        <X />
                      </button>
                      <button
                        aria-label={`${track.title} 구간별 재생 ${expanded ? '접기' : '펼치기'}`}
                        aria-expanded={expanded}
                        className="icon-button saved-moment-expand"
                        onClick={() => {
                          if (expanded) m.stopSavedMomentPlayback(track.id, 'single');
                          setOpenMomentPlayer((id) => id === track.id ? null : track.id);
                          setFocusedMoment(moments[0]?.id ?? null);
                        }}
                      >
                        <span className="saved-moment-composite-icon" aria-hidden="true">
                          <span className="saved-moment-bracket">[</span>
                          <span className="saved-moment-mini-wave">
                            <i /><i /><i /><i /><i />
                          </span>
                          <span className="saved-moment-bracket">]</span>
                          <Play fill="currentColor" />
                        </span>
                      </button>
                    </>
                  }
                />
                <div className="saved-moment-detail-section">
                  <span className="moment-meta" title={moments.map((moment) => moment.section).join(' · ')}>
                    {moments.map((moment) => moment.section).join(' · ')}
                  </span>
                  <button className="text-link" onClick={() => m.openMoment(track, moments[0])}>
                    {moments.length}개 순간 수정 <ChevronRight />
                  </button>
                </div>
                {expanded && <section className="saved-moment-player" aria-label={`${track.title} 저장한 순간 재생`}>
                  <div className="saved-moment-parts">
                    {moments.map((moment) => {
                      const playing = isActive && m.playing && m.savedMomentPlayback?.mode === 'single' && focused.id === moment.id && m.time >= moment.start && m.time < moment.end;
                      return <div className="saved-moment-part" key={moment.id}>
                        <button
                          className={focused.id === moment.id ? 'is-focused' : ''}
                          onClick={() => { setFocusedMoment(moment.id); m.playSavedMoments(moments, moment); }}
                          aria-label={`${moment.section} ${formatTime(moment.start)}부터 재생`}
                        >
                          {playing ? <Pause fill="currentColor" /> : <Play fill="currentColor" />}
                          <span>{moment.section}</span>
                          <small>{formatTime(moment.start)} – {formatTime(moment.end)}</small>
                        </button>
                      </div>;
                    })}
                  </div>
                  <div className="saved-moment-wave">
                    <SavedMomentWaveform track={track} duration={track.duration} ranges={moments} progress={isActive ? m.time : 0} />
                    {moments.map((moment) => <span key={moment.id} className={`saved-wave-range ${focused.id === moment.id ? 'is-focused' : ''}`} style={{ left: `${moment.start / track.duration * 100}%`, width: `${(moment.end - moment.start) / track.duration * 100}%` }} />)}
                    {isActive && <span className="saved-wave-playhead" style={{ left: `${Math.min(100, m.time / track.duration * 100)}%` }} />}
                    <button
                      className="saved-wave-hit"
                      onClick={(event) => {
                        const box = event.currentTarget.getBoundingClientRect();
                        const at = Math.max(0, Math.min(track.duration, ((event.clientX - box.left) / box.width) * track.duration));
                        const target = moments.find((moment) => at >= moment.start && at < moment.end);
                        if (target) { setFocusedMoment(target.id); m.playSavedMoments(moments, target); }
                      }}
                      aria-label="파형에서 저장한 순간 선택"
                    />
                  </div>
                  <div className="saved-moment-time"><span>{isActive ? formatTime(m.time) : '00:00'}</span><span>{formatTime(track.duration)}</span></div>
                </section>}
              </article>
            );})}
          </div>
          {!m.moments.length && (
            <EmptyState
              title="아직 남긴 순간이 없어요"
              description="플레이어에서 ‘이 순간 저장’을 눌러 남겨보세요."
            >
              <button
                className="secondary-button"
                onClick={() => m.go('player')}
              >
                음악 들으러 가기
              </button>
            </EmptyState>
          )}
        </TabsContent>
        <TabsContent value="liked">
          <Heading title={`좋아하는 음악 ${m.liked.length}곡`} />
          <div className="search-results liked-track-list">
            {m.liked
              .map((id) => tracks.find((t) => t.id === id)!)
              .filter(Boolean)
              .map((t) => (
                <TrackRow
                  key={t.id}
                  track={t}
                  model={m}
                  showDetail={false}
                  action={
                    <button
                      aria-label={`${t.title} 좋아요 취소`}
                      className="icon-button like-button"
                      onClick={() => m.toggleLike(t.id)}
                    >
                      <Heart fill="currentColor" />
                    </button>
                  }
                />
              ))}
          </div>
          {!m.liked.length && (
            <EmptyState
              title="마음에 드는 곡을 발견해 보세요"
              description="좋아요한 음악은 여기에 모아둘게요."
            />
          )}
        </TabsContent>
      </Tabs>
      <AlertDialog
        open={removeMomentTrack !== null}
        onOpenChange={(open) => { if (!open) setRemoveMomentTrack(null); }}
      >
        <AlertDialogContent className="sky-dialog">
          <AlertDialogHeader>
            <AlertDialogTitle>이 곡의 저장한 순간을 모두 삭제할까요?</AlertDialogTitle>
            <AlertDialogDescription>
              {momentGroupToRemove
                ? `${momentGroupToRemove.track.title}의 ${momentGroupToRemove.moments.length}개 순간`
                : ''}
              만 지워지고, 곡과 플레이리스트는 그대로예요.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (momentGroupToRemove) m.removeMoments(momentGroupToRemove.moments.map((moment) => moment.id));
                setRemoveMomentTrack(null);
                m.setToast('이 곡의 저장한 순간을 모두 삭제했어요.');
              }}
            >
              삭제
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
      <AlertDialog
        open={confirmRemove !== null}
        onOpenChange={(open) => { if (!open) setConfirmRemove(null); }}
      >
        <AlertDialogContent className="sky-dialog">
          <AlertDialogHeader>
            <AlertDialogTitle>
              {removeTargets.length === 1
                ? `‘${removeTargets[0].title}’ 곡을 뺄까요?`
                : `고른 ${removeTargets.length}곡을 뺄까요?`}
            </AlertDialogTitle>
            <AlertDialogDescription>
              플레이리스트에서만 빠지고, 곡 자체는 그대로 남아 있어요.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                const gone = confirmRemove ?? [];
                if (selected) {
                  void m.removeFromPlaylist(selected.id, gone);
                  m.setToast(
                    removeTargets.length === 1
                      ? `‘${removeTargets[0].title}’ 곡을 플레이리스트에서 뺐어요.`
                      : `${gone.length}곡을 플레이리스트에서 뺐어요.`,
                  );
                  playlistPick.cancel();
                }
                setConfirmRemove(null);
              }}
            >
              빼기
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

/**
 * 리포트 카드의 MuBTI 캐릭터 — 서버가 준 유형 일러스트.
 *
 * 서버는 16유형 각각의 캐릭터를 들고 있다(`image_url` = `/api/v1/storage/image/{fileId}`).
 * 그 한 장이 곧 판정 결과라, 화면은 그 이미지를 그대로 쓴다.
 *
 * **이미지가 없으면 마스코트로 내려간다.** 서버가 아직 이미지를 못 올렸거나(부트스트랩이
 * 도는 중) 응답이 `image_url: null` 이어도 카드가 비지 않게 하려는 것이다. 실패를 감지해
 * 대체 이미지로 바꾸는 길은 쓰지 않는다 — `<img onError>` 는 이미지가 늦게 와도
 * 한 번 깨진 뒤에는 되돌아오지 않고, 로딩 중에 빈 자리가 보인다. 대신 **응답에 주소가
 * 없을 때만** 대체하고, 주소가 있으면 그 주소를 정직하게 내보낸다(서버에서 401·404 를
 * 받으면 그때는 이미지가 깨져 보이는 편이 낫다 — 서버가 잘못 준 것을 숨기지 않는다).
 *
 * 앱(Tauri)에서는 주소를 Rust 가 받아 `mupidimg` 스킴으로 돌려주므로
 * `toImageSource` 를 거쳐야 한다. 안 거치면 앱에서 캐릭터만 깨진다.
 */
function ReportMubtiArt({
  code,
  imageUrl,
  alt,
}: {
  code: string;
  imageUrl: string | null;
  alt: string;
}) {
  const source = toImageSource(imageUrl);
  if (!source) {
    return <MupidMascot size={132} className="report-cupid" alt={alt} />;
  }
  return (
    <img
      className="report-cupid"
      src={source}
      alt={alt}
      width={132}
      height={132}
      loading="lazy"
      decoding="async"
      // `key` 는 유형이 바뀔 때 브라우저가 앞 이미지를 재사용하지 않게 한다.
      // 같은 자리에 다른 그림이 들어오는데 캐시가 섞이면 잘못된 유형이 보인다.
      key={code}
    />
  );
}

/**
 * 내 취향 리포트.
 *
 * 세 블록 모두 서버 집계다 — 곡별 청취량 트리맵 · 음악 키워드 · MuBTI 판정.
 * 예전에는 기기에 저장된 `moments` 의 태그 개수로 로컬 계산했는데, 그건 "저장한
 * 순간" 이지 "들은 것" 이 아니어서 실제 청취와 무관했다.
 *
 * 방금 들은 곡이 반영되기까지 20초쯤 걸린다. `useDailyReport` 가 30초 폴링으로
 * 그 지연을 덮는다 — 자세한 사정은 `lib/report.ts`.
 */
export function ReportView({ model: m }: { model: MupidModel }) {
  const { report, loading, error } = useDailyReport(true);
  const mubti = report?.mubti ?? null;
  // 판정하기엔 이른 하루인가. 서버가 표본 하한(5분·3곡)으로 정한다.
  const tooEarly = !!report && !report.sample.sufficient;
  return (
    <div className="content-page">
      <PageHeader title="내 취향 리포트" onBack={() => m.go('home')} />
      {error && <p className="demo-note" role="alert">{error}</p>}
      <div className="report-grid">
        <DailyAlbumTreemap
          model={m}
          tracks={report?.tracks ?? []}
          totalMs={report?.total_listening_ms ?? 0}
          loading={loading}
        />
        <div className="report-details">
          <section className="report-card listening-story">
            {/* 유형 이름을 먼저 읽히게 두고, 그 아래 캐릭터로 분위기를 전한 뒤 설명을 잇는다. */}
            <div className="mubti-heading-row">
              <h2 className="mubti-heading">MUBTI{mubti ? ` · ${mubti.code}` : ''}</h2>
              {/* 지난 날들의 유형은 대화상자로 본다. 본문에 달력을 펼치면 자리를 너무 먹었다. */}
              <WeeklyMubtiButton today={report} />
            </div>
            {mubti ? (
              <>
                <h2 className="report-story">
                  {mubti.subtitle}
                  <br />
                  <em>{mubti.title}</em>
                </h2>
                <ReportMubtiArt
                  code={mubti.code}
                  imageUrl={mubti.image_url}
                  alt={`헤드폰을 끼고 구름 위에서 음악에 잠긴 ${mubti.title} 뮤피드`}
                />
                <p>{mubti.description}</p>
                {/* 축 점수 막대는 그리지 않는다. 화면에 없던 것을 리포트 연동하면서
                    끼워 넣지 않기로 했다. 응답의 `axes` 는 그대로 내려오므로
                    필요해지면 여기서 다시 그리면 된다. */}
              </>
            ) : (
              <>
                <p className="report-story report-story-pending">
                  {tooEarly ? '아직 판정하기 일러요' : '오늘의 유형을 기다리는 중'}
                </p>
                <MupidMascot
                  size={132}
                  className="report-cupid"
                  alt="헤드폰을 끼고 구름 위에서 음악을 기다리는 뮤피드"
                />
                <p>
                  {loading
                    ? '청취 기록을 모으는 중…'
                    : '오늘 3곡 이상, 5분 넘게 들으면 열려요.'}
                </p>
              </>
            )}
          </section>
          <section className="report-card keyword-card">
            <h2>당신의 음악 키워드</h2>
            {report?.keywords.length ? (
              <div className="tags">
                {report.keywords.map((keyword) => (
                  <span key={`${keyword.kind}:${keyword.name}`}>
                    {(keyword.display_name || keyword.name).startsWith('#')
                      ? keyword.display_name || keyword.name
                      : `#${keyword.display_name || keyword.name}`}
                  </span>
                ))}
              </div>
            ) : (
              <p className="demo-note">
                {loading
                  ? '키워드를 모으고 있어요…'
                  : '음악을 들으면 오늘의 키워드가 모여요.'}
              </p>
            )}
            {!!report?.keywords.length && (
              <p className="keyword-description">지금까지 들은 음악을 바탕으로 정해진 키워드예요.</p>
            )}
          </section>
        </div>
      </div>
    </div>
  );
}
/**
 * 마이페이지의 닉네임 수정.
 *
 * 저장은 계정(`PATCH /api/v1/user`)에 한다. 화면 상태만 바꾸면 새로고침에서
 * 예전 이름이 돌아온다. 길이 제한은 서버 규약을 그대로 따른다.
 */
function NicknameForm({ model: m }: { model: MupidModel }) {
  const [draft, setDraft] = useState(m.nickname);
  const [busy, setBusy] = useState(false);
  return (
    <form
      className="profile-nickname-form"
      onSubmit={async (event) => {
        event.preventDefault();
        if (busy) return;
        const next = normalizeNickname(draft);
        if (next === m.nickname) {
          setDraft(next);
          return;
        }
        setBusy(true);
        try {
          await m.saveNickname(next);
          m.setToast('닉네임을 바꿨어요.');
        } catch (error) {
          setDraft(m.nickname);
          m.setToast(
            error instanceof Error && error.message
              ? error.message
              : '닉네임을 저장하지 못했어요. 다시 시도해 주세요.',
          );
        } finally {
          setBusy(false);
        }
      }}
    >
      <label className="field-label" htmlFor="nickname">
        닉네임
      </label>
      <div className="inline-form">
        <input
          id="nickname"
          minLength={NICKNAME_MIN_LENGTH}
          maxLength={NICKNAME_MAX_LENGTH}
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          disabled={busy}
          required
        />
        <button className="primary-button" type="submit" disabled={busy}>
          {busy ? '저장 중…' : '저장'}
        </button>
      </div>
    </form>
  );
}

export function ProfileView({ model: m }: { model: MupidModel }) {
  const tracks = m.tracks;
  const ignoredTracks = tracks.filter(track => m.ignored.includes(track.id));
  const historyTracks = m.history.flatMap(id => { const found = tracks.find(track => track.id === id); return found ? [found] : []; });
  return (
    <div className="content-page">
      <PageHeader title="마이페이지" onBack={() => m.go('home')} />
      <DemoAccount model={m} />
      <section className="profile-card">
        {m.demoSession ? <ProfilePhoto /> : <img src={DEFAULT_PROFILE_PHOTO} width={116} height={116} style={{ borderRadius: '50%' }} alt="기본 프로필" />}
        <div>
          <p className="eyebrow" lang="en">MY LITTLE MUSIC WORLD</p>
          <h2>{m.displayName}님의 음악 이야기</h2>
          <p>저장한 순간으로 나를 알아가요.</p>
        </div>
      </section>
      {/*
        * 선호 장르·분위기 칩을 지웠다. 온보딩은 계정당 한 번이고 서버가
        * 재제출을 409 로 막으므로, 칩을 누를 때마다 실패했다. 닉네임은
        * 온보딩과 무관하게 `PATCH /api/v1/user` 로 고친다.
        */}
      {m.demoSession && (
        <section className="profile-settings-card" aria-label="계정 설정">
          <h2>계정 설정</h2>
          <ThemeToggle />
          <NicknameForm key={m.nickname} model={m} />
          <PasswordForm />
          {m.demoSession?.kind === 'authenticated' && <WithdrawAccount model={m} />}
        </section>
      )}
      <section className="profile-settings-card profile-list-card" aria-label="최근 들은 음악">
      <h2>최근 들은 음악 <span className="profile-section-count">{historyTracks.length}곡</span></h2>
      <section className="track-list profile-history" aria-label="최근 들은 음악 목록">
        {/* 홈의 '최근 들은 음악' 과 같은 뜻이다. 화면이 달라도 경로는 `RECENT` 하나다. */}
        {historyTracks.map(track => <TrackRow
          key={track.id}
          track={track}
          model={m}
          showDetail={false}
          onPlay={() => m.play(track, false, undefined, 'RECENT')}
        />)}
        {!historyTracks.length && <p className="listening-history-empty">아직 감상 기록이 없어요.<span>음악을 들으면 여기에 차곡차곡 모아둘게요.</span></p>}
      </section>
      </section>
      <section className="profile-settings-card profile-list-card profile-ignored-card" aria-label="관심 없는 곡">
      <h2>관심 없는 곡 <span className="profile-section-count">{ignoredTracks.length}곡</span></h2>
      {!!ignoredTracks.length && <button className="text-link" onClick={m.clearIgnored}>전체 해제</button>}
      <section className="track-list profile-history" aria-label="관심 없는 곡 목록">
        {ignoredTracks.map(track => <TrackRow
          key={track.id}
          track={track}
          model={m}
          showDetail={false}
          action={<button className="text-link ignored-restore" onClick={() => m.restoreIgnored(track.id)}>해제</button>}
        />)}
        {!ignoredTracks.length && <p className="listening-history-empty">관심 없는 곡이 없어요.<span>등록한 곡은 추천에서 제외 됩니다.</span></p>}
      </section>
      </section>
    </div>
  );
}
/**
 * 온보딩 설문 화면.
 *
 * 닉네임은 묻지 않는다 — 회원가입에서 이미 받았고, 서버도 설문 본문에서
 * 받지 않는다. 남은 것은 장르·분위기 두 단계다.
 *
 * **계정당 한 번뿐이다.** 마친 사람에게는 이 폼 대신 환영 화면이 뜬다.
 */
export function WelcomeView({ model: m }: { model: MupidModel }) {
  const [step, setStep] = useState(0);
  const [selectedMoods, setSelectedMoods] = useState<string[]>([]);
  const [selectedGenres, setSelectedGenres] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  // 장르가 45개를 넘는다. 전부 펼치면 '다음' 버튼이 화면 밖으로 밀려
  // 고르다 말고 스크롤을 한참 내려야 한다 — 검색으로 좁힌다.
  const [genreQuery, setGenreQuery] = useState('');
  // 고를 수 있는 항목은 서버가 정한다. 못 받아 오면 화면 기본 목록으로 돌아간다.
  const [options, setOptions] = useState<{ moods: string[]; genres: string[] }>({
    moods: moodOptions,
    genres: genreOptions,
  });
  useEffect(() => {
    const abort = new AbortController();
    loadOnboardingOptions().then((value) => {
      if (abort.signal.aborted) return;
      setOptions({ moods: namesOf(value.moods), genres: namesOf(value.genres) });
    });
    return () => { abort.abort(); };
  }, []);
  // 고른 것은 검색과 무관하게 남긴다 — 검색어를 바꿨다고 고른 것이 사라지면
  // 무엇을 골랐는지 확인할 방법이 없다.
  const query = genreQuery.trim().toLowerCase();
  const visibleGenres = (query
    ? options.genres.filter((genre) => genre.toLowerCase().includes(query))
    : [...options.genres])
    .sort((a, b) => a.localeCompare(b, 'ko'));
  return (
    <div className={`welcome-page${m.demoSession && !m.onboardingSubmitted ? ' is-onboarding' : ''}`}>
      {(step > 0 || m.onboardingSubmitted) && <button
        className="icon-button welcome-back"
        aria-label={step > 0 ? '이전 단계로 돌아가기' : '홈으로 돌아가기'}
        disabled={busy}
        onClick={() => { setError(''); setGenreQuery(''); if (step > 0) setStep(step - 1); else m.go('home'); }}
      >
        <ChevronLeft />
      </button>}
      <Brand className="welcome-logo" />
      <div className="welcome-theme"><ThemeToggle /></div>
      {!m.demoSession || m.onboardingSubmitted ? (
        <div className="welcome-composition">
          <div className="welcome-copy">
          <p className="welcome-kicker">내 마음에 닿는 음악, 뮤피드</p>
          <h1>
            좋아했던 순간이
            <br />
            다음 음악이 되는 곳
          </h1>
          <div className="welcome-entry-actions">
          <button className="primary-button" onClick={() => m.demoSession ? m.go('home') : m.openLogin()}>
            뮤피드 시작하기 <ChevronRight />
          </button>
          <figure className="welcome-qr">
            <div className="welcome-qr-emblem">
              {[false, true].map(right => <svg key={String(right)} className={`welcome-qr-wing${right ? ' is-right' : ''}`} viewBox="0 0 120 112" fill="none" aria-hidden="true">
                <defs>
                  <linearGradient id={`wing-body-${right}`} x1="40" y1="12" x2="82" y2="112" gradientUnits="userSpaceOnUse">
                    <stop stopColor="#fff" /><stop offset=".55" stopColor="#fafbff" /><stop offset="1" stopColor="#dce3f2" />
                  </linearGradient>
                  <filter id={`wing-soft-${right}`} x="-40%" y="-40%" width="180%" height="180%"><feGaussianBlur stdDeviation="2.6" /></filter>
                </defs>
                <path d="M111 66C105 52 87 48 68 40C47 31 34 17 24 8C15 0 5 5 5 17C5 28 11 38 19 45C7 41 1 48 5 59C8 68 17 75 27 78C16 78 12 85 18 93C27 104 44 101 59 94C63 107 76 112 90 107C109 102 118 85 111 66Z" fill={`url(#wing-body-${right})`} stroke="#e4e9f5" strokeWidth=".8" />
                <g stroke="#bdc9de" strokeWidth="4" strokeLinecap="round" filter={`url(#wing-soft-${right})`} opacity=".46">
                  <path d="M19 44C29 54 42 57 51 54M27 77C37 82 48 77 54 71" />
                  <path d="M62 93C53 76 65 63 79 65C94 67 98 83 87 89C80 93 73 88 76 82" />
                </g>
                <path d="M14 16C19 29 38 44 55 49" stroke="white" strokeWidth="5" strokeLinecap="round" opacity=".8" filter={`url(#wing-soft-${right})`} />
              </svg>)}
              <div className="welcome-qr-image welcome-qr-desktop"><img src="/mupid-qr.png" width={132} height={132} alt="뮤피드 접속 QR 코드" /></div>
              <a className="welcome-qr-image welcome-qr-mobile" href="https://j15a204.p.ssafy.io/download/mupid.apk" download="mupid.apk" aria-label="뮤피드 Android 앱 다운로드"><img src="/mupid-qr.png" width={132} height={132} alt="" /></a>
            </div>
            <figcaption>음악을 모바일로 이어가세요<span className="welcome-qr-desktop">카메라로 QR 스캔</span><span className="welcome-qr-mobile">QR을 눌러 Android 앱 다운로드</span></figcaption>
          </figure>
          </div>
          </div>
          <div className="welcome-illustration" role="img" aria-label="긴 하트선을 들고 구름 위에 앉은 뮤피드 캐릭터" />
        </div>
      ) : (
        <form
          className="welcome-form"
          onSubmit={async (e) => {
            e.preventDefault();
            if (busy) return;
            if (step === 0) { if (selectedGenres.length) { setError(''); setSelectedMoods([]); setStep(1); } return; }
            setBusy(true); setError('');
            try { await m.completeOnboarding(selectedMoods, selectedGenres); }
            catch (e) {
              // 선택지가 서버와 어긋났다면 새로 받아 **처음부터 다시 고르게** 한다.
              // 고른 것을 그대로 둔 채 다시 누르게 하면 같은 곳에서 또 막힌다.
              if (e instanceof OnboardingOptionsStale) {
                const fresh = await refreshOnboardingOptions();
                setOptions({ moods: namesOf(fresh.moods), genres: namesOf(fresh.genres) });
                setSelectedGenres([]);
                setSelectedMoods([]);
                setStep(1);
              }
              setError(e instanceof Error && e.message ? e.message : '취향을 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.');
            }
            finally { setBusy(false); }
          }}
        >
          <output className="onboarding-step-count">{step + 1} / 2 · {['장르', '분위기'][step]}</output>
          <h1>{['어떤 장르를 좋아하세요?', '어떤 분위기를 좋아하세요?'][step]}</h1>
          {step === 0 && <fieldset key="genres" className="onboarding-choice-group">
            <legend>좋아하는 장르 <small>하나 이상 · 여러 개 선택 가능</small></legend>
            {/* 고른 것을 위에 고정한다. 목록을 스크롤해도 무엇을 골랐는지 보이고,
                되돌리려고 다시 찾아 내려갈 필요가 없다. */}
            {selectedGenres.length > 0 && (
              <div className="onboarding-picked">
                <span className="onboarding-picked-count">{selectedGenres.length}개 선택</span>
                {selectedGenres.map((genre) => (
                  <button
                    type="button"
                    key={genre}
                    className="onboarding-picked-chip"
                    disabled={busy}
                    aria-label={`${genre} 선택 해제`}
                    onClick={() => setSelectedGenres((list) => list.filter((item) => item !== genre))}
                  >
                    {genre} <span aria-hidden="true">×</span>
                  </button>
                ))}
              </div>
            )}
            {/* 목록이 길 때만 검색을 낸다. 8개뿐이던 때와 화면이 달라지지 않게. */}
            {options.genres.length > 12 && (
              <input
                type="search"
                className="onboarding-search"
                value={genreQuery}
                disabled={busy}
                placeholder="장르 검색"
                aria-label="장르 검색"
                onChange={(e) => setGenreQuery(e.target.value)}
              />
            )}
            <div className="filter-chips wrap onboarding-scroll">
              {visibleGenres.map((genre) => (
                <button
                  type="button"
                  key={genre}
                  disabled={busy}
                  aria-pressed={selectedGenres.includes(genre)}
                  className={selectedGenres.includes(genre) ? 'selected' : ''}
                  onClick={() => setSelectedGenres(list => list.includes(genre) ? list.filter(item => item !== genre) : [...list, genre])}
                >
                  {genre}
                </button>
              ))}
            </div>
            {/* 검색 결과가 없을 때 빈 화면만 남으면 고장으로 보인다. */}
            {visibleGenres.length === 0 && (
              <p className="onboarding-empty">‘{genreQuery}’와 맞는 장르가 없어요.</p>
            )}
          </fieldset>}
          {step === 1 && <fieldset key="moods" className="onboarding-choice-group">
          <legend>좋아하는 분위기 <small>하나 이상 · 여러 개 선택 가능</small></legend>
          <div className="filter-chips wrap onboarding-scroll">
            {options.moods.map((mood) => (
              <button
                type="button"
                key={mood}
                aria-pressed={selectedMoods.includes(mood)}
                className={selectedMoods.includes(mood) ? 'selected' : ''}
                disabled={busy}
                onClick={() =>
                  setSelectedMoods((list) =>
                    list.includes(mood)
                      ? list.filter((v) => v !== mood)
                      : [...list, mood],
                  )
                }
              >
                {mood}
              </button>
            ))}
          </div>
          </fieldset>}
          <div className="onboarding-footer">
          <p>{step === 0 ? '좋아하는 장르를 하나 이상 선택해 주세요.' : '좋아하는 분위기를 하나 이상 선택해 주세요.'}</p>
          {error && <p role="alert">{error}</p>}
          <button className="primary-button wide" disabled={busy || (step === 0 ? !selectedGenres.length : !selectedMoods.length)}>
            {busy ? '취향 저장 중…' : step < 1 ? '다음' : '음악 둘러보기'} <ChevronRight />
          </button>
          </div>
        </form>
      )}
    </div>
  );
}
