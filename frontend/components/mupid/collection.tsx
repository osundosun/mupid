'use client';
import { MupidMascot } from './mascot';
import { ProfilePhoto } from './profile-photo';
import { useState } from 'react';
import { DemoAccount } from './demo-login';
import {
  Check,
  ChevronLeft,
  ChevronRight,
  Heart,
  Music2,
  Play,
  Plus,
  Trash2,
  X,
} from 'lucide-react';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
import type { MupidModel } from '@/hooks/use-mupid';
import { formatTime, moodOptions } from '@/lib/mupid-data';
import {
  AlbumArt,
  Brand,
  EmptyState,
  Heading,
  PageHeader,
  TrackRow,
} from './primitives';

export function LibraryView({ model: m }: { model: MupidModel }) {
  const tracks = m.tracks;
  const selected = m.playlists.find((p) => p.id === m.selectedPlaylist);
  return (
    <div className="content-page collection-page">
      <PageHeader
        title={
          selected && m.libraryTab === 'playlists'
            ? selected.name
            : '내 플레이리스트'
        }
        onBack={() => {
          if (selected) m.setSelectedPlaylist(null);
          else m.go('home');
        }}
        aside={
          selected && m.libraryTab === 'playlists' ? (
            <button
              aria-label="플레이리스트 삭제"
              className="icon-button"
              onClick={() => m.setDeletePlaylist(selected.id)}
            >
              <Trash2 />
            </button>
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
          <TabsTrigger value="playlists">플레이리스트</TabsTrigger>
          <TabsTrigger value="moments">좋아한 구간</TabsTrigger>
          <TabsTrigger value="liked">저장한 곡</TabsTrigger>
        </TabsList>
        <TabsContent value="playlists">
          {selected ? (
            <div className="playlist-detail">
              <div className="playlist-summary">
                {selected.tracks[0] ? <AlbumArt track={selected.tracks[0]} /> : <Music2 size={72} aria-label="비어 있는 플레이리스트" />}
                <div>
                  <span className="eyebrow">MY PLAYLIST</span>
                  <h2>{selected.name}</h2>
                  <p>
                    {selected.tracks.length}곡 ·{' '}
                    {Math.ceil(
                      selected.tracks.reduce((sum, t) => sum + t.duration, 0) /
                        60,
                    )}
                    분
                  </p>
                  <button
                    className="primary-button"
                    disabled={!selected.tracks.length}
                    onClick={() => m.playPlaylist(selected)}
                  >
                    <Play fill="currentColor" />
                    전체 재생
                  </button>
                </div>
              </div>
              <div className="playlist-tracks">
              <div className="playlist-tracks-heading"><h3>수록곡</h3><span>{selected.tracks.length}곡</span></div>
              <div className="track-list">
                {selected.tracks.map((t) => (
                  <TrackRow
                    key={t.id}
                    track={t}
                    model={m}
                    action={
                      <button
                        className="icon-button"
                        aria-label={`${t.title} 플레이리스트에서 제거`}
                        onClick={() =>
                          m.setPlaylists((list) =>
                            list.map((p) =>
                              p.id === selected.id
                                ? {
                                    ...p,
                                    tracks: p.tracks.filter(
                                      (v) => v.id !== t.id,
                                    ),
                                  }
                                : p,
                            ),
                          )
                        }
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
              <div className="add-current">
                <span>현재 재생 중인 곡</span>
                <strong>{m.active.title}</strong>
                <button
                  className="secondary-button"
                  disabled={selected.tracks.some((t) => t.id === m.active.id)}
                  onClick={() => {
                    m.setPlaylists((list) =>
                      list.map((p) =>
                        p.id === selected.id
                          ? { ...p, tracks: [...p.tracks, m.active] }
                          : p,
                      ),
                    );
                    m.setToast('플레이리스트에 음악을 담았어요.');
                  }}
                >
                  {selected.tracks.some((t) => t.id === m.active.id) ? (
                    <Check />
                  ) : (
                    <Plus />
                  )}
                  추가
                </button>
              </div>
            </div>
          ) : (
            <>
              <button
                className="new-playlist"
                onClick={() => m.setPlaylistDialog(true)}
              >
                <Plus />새 플레이리스트
              </button>
              <div className="playlist-grid">
                {m.playlists.map((p) => (
                  <button
                    className="playlist-card"
                    key={p.id}
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
                      aria-label={`${moment.track.title} 저장 구간 삭제`}
                      className="icon-button"
                      onClick={() =>
                        m.setMoments((list) =>
                          list.filter((v) => v.id !== moment.id),
                        )
                      }
                    >
                      <X />
                    </button>
                  }
                />
                <button
                  className="text-link"
                  onClick={() => {
                    m.openMoment(moment.track);
                    m.setMomentRange([moment.start, moment.end]);
                    m.setMomentSection(moment.section);
                  }}
                >
                  저장 구간 열기 <ChevronRight />
                </button>
              </article>
            ))}
          </div>
          {!m.moments.length && (
            <EmptyState
              title="아직 남긴 순간이 없어요"
              description="재생 중 하트를 눌러 좋아한 구간을 남겨보세요."
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
    </div>
  );
}

export function ReportView({ model: m }: { model: MupidModel }) {
  const [period, setPeriod] = useState('week');
  const [offset, setOffset] = useState(0);
  const names = ['Chorus', 'Pre-Chorus', 'Verse', 'Bridge', 'Outro'];
  const colors = ['#efacd0', '#739adc', '#b8cffa', '#c8bdea', '#d6e4fa'];
  const counts = names.map(
    (name) =>
      m.moments.filter((moment) =>
        name === 'Chorus'
          ? /코러스/.test(moment.section) && !/프리/.test(moment.section)
          : name === 'Pre-Chorus'
            ? /프리/.test(moment.section)
            : name === 'Verse'
              ? /벌스/.test(moment.section)
              : name === 'Bridge'
                ? /브리지/.test(moment.section)
                : /아웃트로|인트로|직접/.test(moment.section),
      ).length,
  );
  const total = counts.reduce((a, b) => a + b, 0);
  let cursor = 0;
  const segments = counts
    .map((count, i) => {
      const start = cursor;
      cursor += total ? (count / total) * 100 : 0;
      return `${colors[i]} ${start}% ${cursor}%`;
    })
    .join(',');
  const topIndex = counts.indexOf(Math.max(...counts));
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
      <Tabs
        value={period}
        onValueChange={(v) => {
          setPeriod(String(v));
          setOffset(0);
        }}
        className="sky-tabs"
      >
        <TabsList>
          <TabsTrigger value="week">주간</TabsTrigger>
          <TabsTrigger value="month">월간</TabsTrigger>
          <TabsTrigger value="year">연간</TabsTrigger>
        </TabsList>
      </Tabs>
      <div className="report-period">
        <button
          className="icon-button"
          aria-label="이전 기간"
          onClick={() => setOffset((v) => v + 1)}
        >
          <ChevronLeft />
        </button>
        <span>
          {offset === 0
            ? period === 'week'
              ? '이번 주'
              : period === 'month'
                ? '이번 달'
                : '올해'
            : `${offset}${period === 'week' ? '주' : period === 'month' ? '개월' : '년'} 전`}
        </span>
        <button
          className="icon-button"
          aria-label="다음 기간"
          disabled={!offset}
          onClick={() => setOffset((v) => Math.max(0, v - 1))}
        >
          <ChevronRight />
        </button>
      </div>
      {offset > 0 ? (
        <EmptyState
          title="이 기간에는 아직 기록이 없어요"
          description="지금부터 좋아하는 순간을 차곡차곡 쌓아보세요."
        />
      ) : (
        <div className="report-grid">
          <section className="report-card">
            <h2>구간별 선호도</h2>
            <p>이 기기에 저장한 구간 비중</p>
            {total ? (
              <>
                <div
                  className="donut"
                  aria-hidden="true"
                  style={{ background: `conic-gradient(${segments})` }}
                >
                  <div>
                    <span>{names[topIndex]}</span>
                    <strong>
                      {Math.round((counts[topIndex] / total) * 100)}
                      <small>%</small>
                    </strong>
                  </div>
                </div>
                <div className="chart-legend">
                  {names.map((name, i) => (
                    <div key={name}>
                      <i style={{ background: colors[i] }} />
                      <span>{name}</span>
                      <strong>{Math.round((counts[i] / total) * 100)}%</strong>
                    </div>
                  ))}
                </div>
              </>
            ) : (
              <EmptyState
                title="취향을 알아가는 중이에요"
                description="좋아한 구간을 저장하면 그래프가 채워져요."
              />
            )}
          </section>
          <div className="report-details">
            <section className="report-card listening-story">
              <span className="eyebrow">YOUR LISTENING DNA</span>
              <h2 className="report-story">
                고요한 시작,
                <br />
                <em>벅차오르는 순간.</em>
              </h2>
              <p>
                {m.displayName}님은 음악이 한 겹씩 쌓이는
                <br />그 순간의 여운을 좋아하는군요.
              </p>
              <MupidMascot size={90} className="report-cupid" />
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
            <div className="stat-pair">
              <section className="report-card">
                <Heart />
                <strong>
                  {m.moments.length}
                  <small>개</small>
                </strong>
                <p>좋아한 순간</p>
              </section>
              <section className="report-card">
                <Music2 />
                <strong>
                  {m.history.length}
                  <small>곡</small>
                </strong>
                <p>함께한 음악</p>
              </section>
            </div>
          </div>
        </div>
      )}
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
      <PageHeader
        title="나의 뮤피드"
        onBack={() => m.go('home')}
        mascot={false}
      />
      <DemoAccount model={m} />
      <section className="profile-card">
        {m.demoSession ? <ProfilePhoto /> : <MupidMascot size={116} alt="기본 프로필" />}
        <div>
          <p className="eyebrow">MY LITTLE MUSIC WORLD</p>
          <h2>{m.displayName}님의 음악 하늘</h2>
          <p>좋아한 순간으로 나를 알아가요.</p>
        </div>
      </section>
      <section className="report-card">
        {m.demoSession && <NicknameForm key={m.nickname} model={m} />}
        <Heading title="내가 좋아하는 분위기" />
        <div className="filter-chips wrap">
          {moodOptions.map((mood) => (
            <button
              aria-pressed={m.moods.includes(mood)}
              className={m.moods.includes(mood) ? 'selected' : ''}
              key={mood}
              onClick={() =>
                m.setMoods((list) =>
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
      </section>
      <Heading title="최근 감상 기록" />
      <div className="track-list profile-history" role="region" aria-label="최근 감상 기록 목록" tabIndex={0}>
        {m.history
          .map((id) => tracks.find((t) => t.id === id)!)
          .filter(Boolean)
          .map((t) => (
            <TrackRow key={t.id} model={m} track={t} />
          ))}
      </div>
      <button
        className="text-link welcome-link"
        onClick={() => m.go('welcome')}
      >
        뮤피드 시작 화면 보기 <ChevronRight />
      </button>
    </div>
  );
}
export function WelcomeView({ model: m }: { model: MupidModel }) {
  const [step, setStep] = useState(0);
  const [name, setName] = useState(m.onboardingSubmittedAt ? m.nickname : '');
  const [selectedMoods, setSelectedMoods] = useState<string[]>(m.onboardingSubmittedAt ? m.moods : []);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  return (
    <div className="welcome-page">
      {(step > 0 || m.onboardingSubmittedAt) && <button
        className="icon-button welcome-back"
        aria-label={step > 0 ? '시작 화면으로 돌아가기' : '홈으로 돌아가기'}
        disabled={busy}
        onClick={() => step > 0 ? setStep(0) : m.go('home')}
      >
        <ChevronLeft />
      </button>}
      <Brand className="welcome-logo" />
      {!m.demoSession || m.onboardingSubmittedAt ? (
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
            setBusy(true); setError('');
            try { await m.completeOnboarding(name, selectedMoods); }
            catch { setError('취향을 저장하지 못했어요. 브라우저 저장 설정을 확인하고 다시 시도해 주세요.'); }
            finally { setBusy(false); }
          }}
        >
          <h1>어떤 음악을 좋아하세요?</h1>
          <label className="field-label" htmlFor="welcome-name">
            어떻게 불러드릴까요?
          </label>
          <input
            id="welcome-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            maxLength={20}
            required
            disabled={busy}
          />
          <div className="filter-chips wrap">
            {moodOptions.map((mood) => (
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
          <p>좋아하는 분위기를 하나 이상 선택해 주세요.</p>
          {error && <p role="alert">{error}</p>}
          <button className="primary-button wide" disabled={busy || !name.trim() || !selectedMoods.length}>
            {busy ? '취향 저장 중…' : '음악 둘러보기'} <ChevronRight />
          </button>
        </form>
      )}
    </div>
  );
}
