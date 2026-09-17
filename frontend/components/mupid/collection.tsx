'use client';
import { DailyAlbumTreemap } from './daily-album-treemap';
import { QueueList } from './queue-list';
import { PlaylistMenu } from './playlist-menu';
import { SelectionToolbar, useTrackSelection } from './track-selection';
import { MupidMascot } from './mascot';
import { ProfilePhoto } from './profile-photo';
import { DEFAULT_PROFILE_PHOTO } from '@/lib/profile-photo';
import { PasswordForm } from './password-form';
import { useEffect, useState } from 'react';
import { DemoAccount } from './demo-login';
import {
  Check,
  ChevronLeft,
  ChevronRight,
  Heart,
  Music2,
  Plus,
  Pencil,
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
import { QUEUE_LIMIT, type MupidModel } from '@/hooks/use-mupid';
import { NICKNAME_MAX_LENGTH, NICKNAME_MIN_LENGTH, normalizeNickname } from '@/lib/profile';
import { useDailyReport } from '@/lib/report';
import { formatTime, genreOptions, moodOptions } from '@/lib/mupid-data';
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
  TrackRow,
  PlayerControls,
} from './primitives';

// 탭 이름을 그대로 화면 제목으로 쓴다. 어느 탭에 있는지 제목만 봐도 알 수 있게.
const LIBRARY_TAB_TITLES: Record<string, string> = {
  queue: '재생목록',
  playlists: '플레이리스트',
  moments: '저장한 순간',
  liked: '좋아한 곡',
};
export function LibraryView({ model: m }: { model: MupidModel }) {
  const tracks = m.tracks;
  const selected = m.playlists.find((p) => p.id === m.selectedPlaylist);
  // 저장한 순간은 되돌릴 수 없어서 삭제 전에 한 번 확인받는다.
  const [removeMoment, setRemoveMoment] = useState<number | null>(null);
  const momentToRemove = m.moments.find((v) => v.id === removeMoment);
  // 재생목록·플레이리스트에서 여러 곡을 골라 한 번에 뺄 수 있게 한다.
  const queuePick = useTrackSelection(m.queueTracks.map((t) => t.id));
  const playlistPick = useTrackSelection((selected?.tracks ?? []).map((t) => t.id));
  // 곡을 목록에서 빼는 건 한 곡이든 여러 곡이든 똑같이 한 번 확인받는다.
  const [confirmRemove, setConfirmRemove] = useState<{
    scope: 'queue' | 'playlist';
    ids: number[];
  } | null>(null);
  const removeTargets = (confirmRemove?.scope === 'playlist' ? (selected?.tracks ?? []) : m.queueTracks)
    .filter((t) => confirmRemove?.ids.includes(t.id));
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
        <TabsList>
          <TabsTrigger value="queue">재생목록</TabsTrigger>
          <TabsTrigger value="playlists">플레이리스트</TabsTrigger>
          <TabsTrigger value="moments">저장한 순간</TabsTrigger>
          <TabsTrigger value="liked">좋아한 곡</TabsTrigger>
        </TabsList>
        <TabsContent value="queue">
          <div className="intro-copy">
            <p>
              {queuePick.active
                ? '뺄 곡을 고르세요. 전체 선택으로 한 번에 비울 수도 있어요.'
                : `지금 재생 목록에 담긴 순서예요. 손잡이를 끌어 순서를 바꿀 수 있고, ${QUEUE_LIMIT}곡까지 담겨요.`}
            </p>
          </div>
          {!!m.queueTracks.length && (
            <SelectionToolbar
              selection={queuePick}
              total={m.queueTracks.length}
              limit={QUEUE_LIMIT}
              onDelete={() => setConfirmRemove({ scope: 'queue', ids: queuePick.picked })}
            />
          )}
          <QueueList
            model={m}
            selection={queuePick}
            onRemove={(track) => setConfirmRemove({ scope: 'queue', ids: [track.id] })}
          />
          {!m.queueTracks.length && (
            <EmptyState
              title="재생 목록이 비어 있어요"
              description="음악을 재생하면 이어 들을 곡이 여기에 쌓여요."
            >
              <button className="secondary-button" onClick={() => m.go('player')}>
                음악 들으러 가기
              </button>
            </EmptyState>
          )}
        </TabsContent>
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
                  {selected.tracks.length > 0 && m.active && <div className="playlist-transport" aria-label="플레이리스트 재생">
                    <PlayerControls model={m} onPlay={() => {
                      const sameQueue = m.queueTracks.length === selected.tracks.length && m.queueTracks.every(t => selected.tracks.some(s => s.id === t.id));
                      if (sameQueue) m.play();
                      else m.playPlaylist(selected);
                    }} />
                    <div className="playlist-seek">
                      <span>{formatTime(m.time)}</span>
                      <input type="range" min={0} max={m.active.duration} step={1} value={m.time} aria-label="재생 위치" onChange={event => m.seek(Number(event.target.value))} />
                      <span>{formatTime(m.active.duration)}</span>
                    </div>
                  </div>}
                </div>
              </div>
              <div className="playlist-tracks">
              <div className="playlist-tracks-heading"><h3>수록곡</h3></div>
              {!!selected.tracks.length && (
                <SelectionToolbar
                  selection={playlistPick}
                  total={selected.tracks.length}
                  onDelete={() => setConfirmRemove({ scope: 'playlist', ids: playlistPick.picked })}
                />
              )}
              <div className="track-list">
                {selected.tracks.map((t) => (
                  <TrackRow
                    key={t.id}
                    track={t}
                    model={m}
                    selected={playlistPick.has(t.id)}
                    onSelect={playlistPick.active ? () => playlistPick.toggle(t.id) : undefined}
                    onPlay={() => m.playPlaylist(selected, t)}
                    action={
                      <button
                        className="icon-button"
                        aria-label={`${t.title} 플레이리스트에서 빼기`}
                        onClick={() => setConfirmRemove({ scope: 'playlist', ids: [t.id] })}
                      >
                        <X />
                      </button>
                    }
                  />
                ))}
              </div>
              </div>
              {!selected.tracks.length && (
                <EmptyState
                  title="첫 번째 음악을 담아볼까요?"
                  description="현재 듣는 곡부터 플레이리스트를 채워보세요."
                />
              )}
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
            {m.moments.map((moment) => (
              <article className="saved-moment" key={moment.id}>
                <TrackRow
                  track={moment.track}
                  model={m}
                  onPlay={() => m.playSavedMoment(moment)}
                  detail={`${moment.section} · ${formatTime(moment.start)} – ${formatTime(moment.end)}`}
                  action={
                    <button
                      aria-label={`${moment.track.title} 저장 순간 삭제`}
                      className="icon-button"
                      onClick={() => setRemoveMoment(moment.id)}
                    >
                      <X />
                    </button>
                  }
                />
                <button
                  className="text-link"
                  onClick={() => m.openMoment(moment.track, moment)}
                >
                  순간 수정 <ChevronRight />
                </button>
              </article>
            ))}
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
        open={removeMoment !== null}
        onOpenChange={(open) => { if (!open) setRemoveMoment(null); }}
      >
        <AlertDialogContent className="sky-dialog">
          <AlertDialogHeader>
            <AlertDialogTitle>저장한 순간을 삭제할까요?</AlertDialogTitle>
            <AlertDialogDescription>
              {momentToRemove
                ? `${momentToRemove.track.title} · ${momentToRemove.section}`
                : ''}
              {' '}순간만 지워지고, 곡과 플레이리스트는 그대로예요.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (removeMoment !== null) m.removeMoment(removeMoment);
                setRemoveMoment(null);
                m.setToast('저장한 순간을 삭제했어요.');
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
              {confirmRemove?.scope === 'playlist'
                ? '플레이리스트에서만 빠지고, 곡 자체는 그대로 남아 있어요.'
                : '재생 목록에서만 빠져요. 지금 듣고 있는 곡은 계속 재생돼요.'}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                const gone = confirmRemove?.ids ?? [];
                if (confirmRemove?.scope === 'playlist' && selected) {
                  void m.removeFromPlaylist(selected.id, gone);
                  m.setToast(
                    removeTargets.length === 1
                      ? `‘${removeTargets[0].title}’ 곡을 플레이리스트에서 뺐어요.`
                      : `${gone.length}곡을 플레이리스트에서 뺐어요.`,
                  );
                  playlistPick.cancel();
                } else if (removeTargets.length === 1) {
                  m.removeFromQueue(removeTargets[0]);
                  queuePick.cancel();
                } else {
                  m.removeManyFromQueue(gone);
                  queuePick.cancel();
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
          tracks={report?.tracks ?? []}
          totalMs={report?.total_listening_ms ?? 0}
          loading={loading}
        />
        <div className="report-details">
          <section className="report-card listening-story">
            {/* 유형 이름을 먼저 읽히게 두고, 그 아래 캐릭터로 분위기를 전한 뒤 설명을 잇는다. */}
            <h2 className="mubti-heading">MUBTI{mubti ? ` · ${mubti.code}` : ''}</h2>
            {mubti ? (
              <>
                <h2 className="report-story">
                  {mubti.subtitle}
                  <br />
                  <em>{mubti.title}</em>
                </h2>
                <MupidMascot
                  size={132}
                  className="report-cupid"
                  alt="헤드폰을 끼고 구름 위에서 음악에 잠긴 뮤피드"
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
                    {keyword.name.startsWith('#') ? keyword.name : `#${keyword.name}`}
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
  const ignoredSelection = useTrackSelection(ignoredTracks.map(track => track.id));
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
          <NicknameForm key={m.nickname} model={m} />
          <PasswordForm />
        </section>
      )}
      <section className="profile-settings-card profile-ignored-card" aria-label="관심 없는 곡">
      <h2>관심 없는 곡 <span className="profile-section-count">{ignoredTracks.length}곡</span></h2>
      {!!ignoredTracks.length && <div className="ignored-edit-toolbar">
        {ignoredSelection.active ? <>
          <label><input type="checkbox" checked={ignoredSelection.allPicked} onChange={ignoredSelection.toggleAll} />전체 선택</label>
          <span aria-live="polite">{ignoredSelection.picked.length}곡 선택</span>
          <button className="text-link" onClick={ignoredSelection.cancel}>취소</button>
          <button className="secondary-button" disabled={!ignoredSelection.picked.length} onClick={() => {
            ignoredSelection.picked.forEach(id => m.toggleIgnored(id));
            ignoredSelection.cancel();
          }}>선택 해제하기</button>
        </> : <button className="text-link" onClick={ignoredSelection.start}><Pencil size={16} />목록 편집</button>}
      </div>}
      <section className="track-list profile-history" aria-label="관심 없는 곡 목록">
        {ignoredTracks.map(track => <TrackRow
          key={track.id} track={track} model={m} showDetail={false}
          selected={ignoredSelection.has(track.id)}
          onSelect={ignoredSelection.active ? () => ignoredSelection.toggle(track.id) : undefined}
          action={ignoredSelection.active ? undefined : <button className="text-link" style={{ minHeight: 44, paddingInline: 12, whiteSpace: 'nowrap' }} aria-label={`${track.title} 관심 없음 해제`} onClick={() => m.toggleIgnored(track.id)}>해제</button>}
        />)}
        {!ignoredTracks.length && <p className="listening-history-empty">관심 없는 곡이 없어요.<span>등록한 곡은 추천에서 제외돼요.</span></p>}
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
  // 고를 수 있는 항목은 서버가 정한다. 못 받아 오면 화면 기본 목록으로 돌아간다.
  const [options, setOptions] = useState<{ moods: string[]; genres: string[] }>({
    moods: moodOptions,
    genres: genreOptions,
  });
  useEffect(() => {
    const abort = new AbortController();
    loadOnboardingOptions(abort.signal).then((value) => {
      if (abort.signal.aborted) return;
      setOptions({ moods: namesOf(value.moods), genres: namesOf(value.genres) });
    });
    return () => { abort.abort(); };
  }, []);
  return (
    <div className="welcome-page">
      {(step > 0 || m.onboardingSubmitted) && <button
        className="icon-button welcome-back"
        aria-label={step > 0 ? '이전 단계로 돌아가기' : '홈으로 돌아가기'}
        disabled={busy}
        onClick={() => { setError(''); if (step > 0) setStep(step - 1); else m.go('home'); }}
      >
        <ChevronLeft />
      </button>}
      <Brand className="welcome-logo" />
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
              {[false, true].map(right => <svg key={String(right)} className={`welcome-qr-wing${right ? ' is-right' : ''}`} viewBox="0 0 80 96" fill="none" aria-hidden="true">
                <path d="M75 78C57 87 39 82 27 72C21 67 24 61 32 62C20 56 10 46 10 40C10 33 17 33 24 37C14 26 7 13 12 8C18 2 26 13 34 23C42 34 50 37 59 42C72 50 79 65 75 78Z" fill="#fdfdff" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" />
                <path d="M25 38C34 49 46 58 61 63M32 62C40 68 50 72 61 73" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
              </svg>)}
              <div className="welcome-qr-image"><img src="/mupid-qr.png" width={132} height={132} alt="뮤피드 접속 QR 코드" /></div>
            </div>
            <figcaption>음악을 모바일로 이어가세요<span>카메라로 QR 스캔</span></figcaption>
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
            <div className="filter-chips wrap">{options.genres.map(genre => <button type="button" key={genre} disabled={busy} aria-pressed={selectedGenres.includes(genre)} className={selectedGenres.includes(genre) ? 'selected' : ''} onClick={() => setSelectedGenres(list => list.includes(genre) ? list.filter(item => item !== genre) : [...list, genre])}>{genre}</button>)}</div>
          </fieldset>}
          {step === 1 && <fieldset key="moods" className="onboarding-choice-group">
          <legend>좋아하는 분위기 <small>하나 이상 · 여러 개 선택 가능</small></legend>
          <div className="filter-chips wrap">
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
          <p>{step === 0 ? '좋아하는 장르를 하나 이상 선택해 주세요.' : '좋아하는 분위기를 하나 이상 선택해 주세요.'}</p>
          {error && <p role="alert">{error}</p>}
          <button className="primary-button wide" disabled={busy || (step === 0 ? !selectedGenres.length : !selectedMoods.length)}>
            {busy ? '취향 저장 중…' : step < 1 ? '다음' : '음악 둘러보기'} <ChevronRight />
          </button>
        </form>
      )}
    </div>
  );
}
