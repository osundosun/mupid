'use client';
import { DailyAlbumTreemap } from './daily-album-treemap';
import { QueueList } from './queue-list';
import { PlaylistMenu } from './playlist-menu';
import { SelectionToolbar, useTrackSelection } from './track-selection';
import { MupidMascot } from './mascot';
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
import { QUEUE_LIMIT, type MupidModel } from '@/hooks/use-mupid';
import { formatTime, genreOptions, moodOptions } from '@/lib/mupid-data';
import { loadOnboardingOptions, namesOf } from '@/lib/onboarding';
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
                    m.setPlaylists((list) =>
                      list.map((p) =>
                        p.id === selected.id
                          ? { ...p, tracks: [...p.tracks, m.active!] }
                          : p,
                      ),
                    );
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
                m.setMoments((list) => list.filter((v) => v.id !== removeMoment));
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
                  m.setPlaylists((list) =>
                    list.map((p) =>
                      p.id === selected.id
                        ? { ...p, tracks: p.tracks.filter((v) => !gone.includes(v.id)) }
                        : p,
                    ),
                  );
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

export function ReportView({ model: m }: { model: MupidModel }) {
  const moodCounts = m.moments
    .flatMap((moment) => moment.track.tags)
    .reduce<Record<string, number>>(
      (acc, tag) => ({ ...acc, [tag]: (acc[tag] || 0) + 1 }),
      {},
    );
  const topTags = Object.entries(moodCounts)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 6);
  return (
    <div className="content-page">
      <PageHeader title="내 취향 리포트" onBack={() => m.go('home')} />
        <div className="report-grid">
          <DailyAlbumTreemap tracks={m.tracks} />
          <div className="report-details">
            <section className="report-card listening-story">
              {/* 유형 이름을 먼저 읽히게 두고, 그 아래 캐릭터로 분위기를 전한 뒤 설명을 잇는다. */}
              <span className="eyebrow">MuBTI · 예시</span>
              <h2 className="report-story">
                여운을 모으는
                <br />
                <em>감성 몰입형</em>
              </h2>
              <MupidMascot
                size={132}
                className="report-cupid"
                alt="헤드폰을 끼고 구름 위에서 음악에 잠긴 뮤피드"
              />
              <p>
                잔잔하게 시작해 감정이 깊어지는 음악에 빠져드는 유형이에요.
                <br />마음에 닿은 순간은 다시 듣고, 곡이 끝난 뒤의 여운까지 즐겨요.
              </p>
              <p className="demo-note">음악 감상 취향을 표현한 예시 유형이에요. 실제 분석이나 성격 진단 결과는 아니에요.</p>
            </section>
            <section className="report-card keyword-card">
              <h2>당신의 음악 키워드</h2>
              <div className="tags">
                {(topTags.length ? topTags.map(([tag]) => tag) : m.moods).map(
                  (tag) => (
                    <span key={tag}>
                      {tag.startsWith('#') ? tag : `#${tag}`}
                    </span>
                  ),
                )}
              </div>
            </section>
          </div>
        </div>
      <p className="demo-note">
        현재 리포트는 기기에 저장된 예시와 활동 기록을 보여줍니다. 기간별 서버
        집계는 연결 예정입니다.
      </p>
    </div>
  );
}
function NicknameForm({ model: m }: { model: MupidModel }) {
  const [draft, setDraft] = useState(m.nickname);
  return (
    <form
      className="profile-nickname-form"
      onSubmit={(event) => {
        event.preventDefault();
        if (draft.trim()) {
          m.setNickname(draft.trim().slice(0, 20));
          m.setToast('닉네임을 바꿨어요.');
        }
      }}
    >
      <label className="field-label" htmlFor="nickname">
        닉네임
      </label>
      <div className="inline-form">
        <input
          id="nickname"
          maxLength={20}
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          required
        />
        <button className="primary-button" type="submit">
          저장
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
            catch (e) { setError(e instanceof Error && e.message ? e.message : '취향을 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.'); }
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
