'use client';
import { DailyAlbumTreemap } from './daily-album-treemap';

import { PlaylistMenu } from './playlist-menu';
import { SelectionToolbar, useTrackSelection } from './track-selection';
import { MupidMascot } from './mascot';
import { toImageSource } from '@/lib/platform/image';
import { ProfilePhoto } from './profile-photo';
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
import { type MupidModel } from '@/hooks/use-mupid';
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
  AlbumArt,
  Brand,
  EmptyState,
  Heading,
  PageHeader,
  TrackRow,
  PlayerControls,
} from './primitives';

// 탭 이름을 그대로 화면 제목으로 쓴다. 어느 탭에 있는지 제목만 봐도 알 수 있게.
const LIBRARY_TAB_TITLES: Record<string, string> = {

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
  // 플레이리스트에서 여러 곡을 골라 한 번에 뺄 수 있게 한다.
  // 재생 목록 쪽 같은 흐름은 독이 들고 있다 (`components/mupid/queue-dock.tsx`).
  const playlistPick = useTrackSelection((selected?.tracks ?? []).map((t) => t.id));
  // 곡을 목록에서 빼는 건 한 곡이든 여러 곡이든 똑같이 한 번 확인받는다.
  const [confirmRemove, setConfirmRemove] = useState<number[] | null>(null);
  const removeTargets = (selected?.tracks ?? []).filter((t) => confirmRemove?.includes(t.id));
  return (
    <div className="content-page collection-page">
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
                {/*
                  * 곡이 하나도 없는 목록만 음표로 둔다. "아직 아무것도 담지
                  * 않았다" 는 것은 커버가 없는 것과 다른 사정이라, 기본 커버로
                  * 합치면 담은 줄 알고 열어 보게 된다.
                  *
                  * 담긴 곡의 커버를 못 구한 경우는 `AlbumArt` 가 기본 커버로
                  * 받아 낸다. 여기서 따로 볼 것이 없다.
                  */}
                {selected.tracks[0] ? <AlbumArt track={selected.tracks[0]} /> : <Music2 size={72} aria-label="비어 있는 플레이리스트" />}
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
                      {/* 상세 화면과 같은 기준이다 — 빈 목록만 음표, 커버를
                        * 못 구한 곡은 `AlbumArt` 의 기본 커버. */}
                      {p.tracks[0] ? <AlbumArt track={p.tracks[0]} /> : <Music2 size={72} aria-label="비어 있는 플레이리스트" />}
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
              description="재생 중 하트를 눌러 저장한 순간을 남겨보세요."
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
          <div className="search-results">
            {m.liked
              .map((id) => tracks.find((t) => t.id === id)!)
              .filter(Boolean)
              .map((t) => (
                <TrackRow
                  key={t.id}
                  track={t}
                  model={m}
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
 * 서버는 16유형 각각의 캐릭터를 들고 있다(`image_url` = `/api/v1/storage/image/MUBTI/VTFC.webp`).
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
          tracks={report?.tracks ?? []}
          totalMs={report?.total_listening_ms ?? 0}
          loading={loading}
        />
        <div className="report-details">
          <section className="report-card listening-story">
            {/* 유형 이름을 먼저 읽히게 두고, 그 아래 캐릭터로 분위기를 전한 뒤 설명을 잇는다. */}
            <span className="eyebrow">MuBTI{mubti ? ` · ${mubti.code}` : ''}</span>
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
                <h2 className="report-story">
                  {tooEarly ? '아직 판정하기 일러요' : '오늘의 유형을 기다리는 중'}
                </h2>
                <MupidMascot
                  size={132}
                  className="report-cupid"
                  alt="헤드폰을 끼고 구름 위에서 음악을 기다리는 뮤피드"
                />
                <p>
                  {loading
                    ? '오늘의 청취 기록을 모으고 있어요.'
                    : '오늘 5분 넘게, 세 곡 이상 들으면 취향 유형을 알려드릴게요.'}
                </p>
              </>
            )}
            <p className="demo-note">
              음악 감상 취향을 표현한 유형이에요. 성격 진단 결과는 아니에요.
            </p>
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
      <p className="demo-note">
        오늘 들은 기록을 바탕으로 집계해요. 방금 들은 곡은 20초쯤 뒤에 반영돼요.
      </p>
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
  return (
    <div className="content-page">
      <PageHeader title="마이페이지" onBack={() => m.go('home')} />
      <DemoAccount model={m} />
      <section className="profile-card">
        {m.demoSession ? <ProfilePhoto /> : <MupidMascot size={116} alt="기본 프로필" />}
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
        <section className="report-card taste-card">
          <NicknameForm key={m.nickname} model={m} />
        </section>
      )}
      <Heading title="최근 감상 기록" />
      <section className="track-list profile-history" aria-label="최근 감상 기록 목록" tabIndex={0}>
        {m.history
          .map((id) => tracks.find((t) => t.id === id)!)
          .filter(Boolean)
          .map((t) => (
            <TrackRow key={t.id} model={m} track={t} queueable />
          ))}
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
          <button className="primary-button" onClick={() => m.demoSession ? m.go('home') : m.openLogin()}>
            뮤피드 시작하기 <ChevronRight />
          </button>
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
