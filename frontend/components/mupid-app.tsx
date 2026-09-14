'use client';
import { useEffect, useRef, useState } from 'react';
import { mupidService } from '@/lib/services';
import type { Bootstrap } from '@/lib/services/contracts';
import { MupidMascot } from '@/components/mupid/mascot';
import {
  ChartPie,
  Disc3,
  Heart,
  Home,
  Library,
  ListMusic,
  LogIn,
  MessageCircle,
  Search,
  UserRound,
  Volume2,
} from 'lucide-react';
import { useMupid } from '@/hooks/use-mupid';
import { useDesktopPlayerDock } from '@/hooks/use-home-player-dock';
import { MiniTimeline } from '@/components/mupid/mini-timeline';
import { PlaybackStatus } from '@/components/mupid/playback-status';
import {
  AlbumArt,
  Brand,
  LikeButton,
  PlayerControls,
} from '@/components/mupid/primitives';
import { HomeView } from '@/components/mupid/home';
import {
  MomentView,
  PlayerView,
  SearchView,
  SimilarView,
} from '@/components/mupid/discovery';
import {
  LibraryView,
  ProfileView,
  ReportView,
  WelcomeView,
} from '@/components/mupid/collection';
import { FloatingChat } from '@/components/mupid/chat';
import { AppDialogs } from '@/components/mupid/dialogs';
import { DemoLoginDialog } from '@/components/mupid/demo-login';

const navigation = [
  { id: 'home', label: '메인', mobileLabel: '메인', icon: Home },
  { id: 'search', label: '탐색', mobileLabel: '탐색', icon: Search },
  { id: 'library', label: '보관함', mobileLabel: '보관함', icon: Library },
  { id: 'report', label: '내 취향', mobileLabel: '내 취향', icon: ChartPie },
] as const;
const desktopNavigation = [
  navigation[0],
  navigation[1],
  { id: 'player', label: '플레이어', icon: Disc3 },
  ...navigation.slice(2),
] as const;
// The same destinations as desktop: five listening tabs below, profile above.
const mobileNavigation = [
  navigation[0],
  navigation[1],
  { ...desktopNavigation[2], mobileLabel: '플레이어' },
  navigation[2],
  navigation[3],
] as const;
export function MupidApp() {
  const [data, setData] = useState<Bootstrap | null>(null);
  const [error, setError] = useState('');
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let live = true;
    void mupidService.bootstrap().then(value => { if (live) setData(value); })
      .catch(() => { if (live) setError('음악을 불러오지 못했어요.'); });
    return () => { live = false; };
  }, [attempt]);
  if (!data || !data.tracks.length) return <main className="mupid-shell" style={{ padding: 32 }} aria-busy={!error && !data}>
    <output>{error || (data ? '아직 등록된 음악이 없어요.' : '음악을 준비하고 있어요…')}</output>
    {(error || data) && <button className="secondary-button" onClick={() => { setError(''); setData(null); setAttempt(v => v + 1); }}>다시 불러오기</button>}
  </main>;
  return <LoadedMupidApp bootstrap={data} />;
}
function LoadedMupidApp({ bootstrap }: { bootstrap: Bootstrap }) {
  const m = useMupid(bootstrap);
  const [accountOpen, setAccountOpen] = useState(false);
  const [photo, setPhoto] = useState('');
  const accountRef = useRef<HTMLDivElement>(null);
  const accountButton = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    setAccountOpen(false);
    let live = true;
    void mupidService.readPhoto().then(value => { if (live) setPhoto(value); }).catch(() => {});
    return () => { live = false; };
  }, [m.page, m.demoSession]);
  useEffect(() => {
    if (!accountOpen) return;
    const outside = (event: PointerEvent) => { if (!accountRef.current?.contains(event.target as Node)) setAccountOpen(false); };
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') { setAccountOpen(false); accountButton.current?.focus(); } };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape); };
  }, [accountOpen]);
  const desktopDock = useDesktopPlayerDock();
  const { audioRef } = m;
  const hideMini =
    m.page === 'welcome' ||
    // 가입 직후처럼 아직 한 곡도 듣지 않았다면 띄우지 않는다.
    !m.hasPlayed ||
    // 플레이어 화면에는 큰 컨트롤이 있으니 미니 플레이어를 겹치지 않는다.
    (!desktopDock && m.page === 'player');
  const desktopNavPage =
    m.page === 'moment' ? 'player' : m.page === 'similar' ? 'search' : m.page;
  const navPage = desktopNavPage;
  return (
    <div className={`mupid-shell ${hideMini ? 'no-mini' : ''} page-${m.page}`}>
      <a href="#main-content" className="skip-link">
        본문으로 이동
      </a>
      {/* Instrumental demo audio has no spoken content to caption. */}
      {/* oxlint-disable-next-line jsx-a11y/media-has-caption -- Instrumental music sample. */}
      <audio
        ref={audioRef}
        src={m.active.audio}
        preload="none"
        onPlay={() => m.setPlaying(true)}
        onPause={() => m.setPlaying(false)}
        onTimeUpdate={m.onTimeUpdate}
        onEnded={m.onEnded}
        onLoadedMetadata={m.onLoadedMetadata}
        onError={() => {
          m.setPlaying(false);
          m.setToast('음원을 불러오지 못했어요. 다른 곡을 선택해 주세요.');
        }}
      />
      {m.page === 'welcome' ? (
        <main id="main-content">
          <WelcomeView model={m} />
        </main>
      ) : (
        <>
          <header className="app-header">
            <button
              className="brand"
              aria-label="뮤피드 홈"
              onClick={() => m.go('home')}
            >
              <Brand />
            </button>
            <nav className="desktop-app-nav" aria-label="PC 메인 메뉴">
              {desktopNavigation.map(({ id, label, icon: Icon }) => (
                <button
                  key={id}
                  aria-current={desktopNavPage === id ? 'page' : undefined}
                  onClick={() => m.go(id)}
                >
                  <Icon aria-hidden="true" />
                  <span>{label}</span>
                </button>
              ))}
            </nav>
            <form className="nav-search" role="search" onSubmit={event => { event.preventDefault(); m.go('search'); }}>
              <input aria-label="곡 또는 아티스트 검색" placeholder="곡, 아티스트 검색" value={m.query} onChange={event => m.setQuery(event.target.value)} />
              <button type="submit" aria-label="검색"><Search size={20} /></button>
            </form>
            <div className="nav-account" ref={accountRef}>
              <button ref={accountButton} className="nav-avatar" aria-label={m.demoSession ? '내 계정 메뉴' : '로그인'} aria-expanded={accountOpen} aria-controls="nav-account-panel" disabled={!m.authReady} onClick={() => m.demoSession ? setAccountOpen(value => !value) : m.openLogin()}>
                {m.demoSession ? photo ? <img src={photo} alt="내 프로필" /> : <UserRound size={22} aria-hidden="true" /> : <LogIn size={22} />}
              </button>
              {accountOpen && <div id="nav-account-panel" className="nav-account-panel">
                <button onClick={() => { setAccountOpen(false); m.go('profile'); }}>마이페이지</button>
                <button onClick={() => { setAccountOpen(false); void m.signOutDemo(); }}>로그아웃</button>
              </div>}
            </div>
          </header>
          <div className="app-layout">
            <aside className="side-nav">
              <div className="side-sticky">
                <div className="side-label">내 음악 공간</div>
                {navigation.map(({ id, label, icon: Icon }) => (
                  <button
                    className={`nav-item ${navPage === id ? 'active' : ''}`}
                    aria-current={navPage === id ? 'page' : undefined}
                    key={id}
                    onClick={() => m.go(id)}
                  >
                    <Icon />
                    {label}
                  </button>
                ))}
                <div className="side-label">나만의 음악 이야기</div>
                <button
                  className="nav-item"
                  onClick={() => {
                    m.setLibraryTab('moments');
                    m.go('library');
                  }}
                >
                  <Heart />
                  저장한 순간
                </button>
                <button className="nav-item" onClick={m.openChat}>
                  <MessageCircle />
                  Mupid AI
                </button>
                <div className="sidebar-bottom">
                  <MupidMascot size={74} />
                  <p>
                    마음에 닿는 음악을
                    <br />
                    함께 찾아요.
                  </p>
                </div>
              </div>
            </aside>
            <main id="main-content" tabIndex={-1} className="main-content">
              {m.page === 'home' && <HomeView model={m} />}
              {m.page === 'search' && <SearchView model={m} />}
              {m.page === 'library' && <LibraryView model={m} />}
              {m.page === 'report' && <ReportView model={m} />}
              {m.page === 'profile' && <ProfileView model={m} />}
              {m.page === 'player' && <PlayerView model={m} />}
              {m.page === 'moment' && <MomentView model={m} />}
              {m.page === 'similar' && <SimilarView model={m} />}
            </main>
          </div>
          {!hideMini && (
            <section className="mini-player" aria-label="미니 플레이어">
              <button
                className="mini-cover"
                onClick={() => m.go('player')}
                aria-label="현재 곡 플레이어 열기"
              >
                <AlbumArt track={m.active} />
              </button>
              <button className="track-info" onClick={() => m.go('player')}>
                <span className="mini-title">
                  <strong>{m.active.title}</strong>
                  <PlaybackStatus playing={m.playing} />
                </span>
                <span>{m.active.artist}</span>
              </button>
              <MiniTimeline model={m} />
              <PlayerControls model={m} compact />
              <LikeButton model={m} />
              <div className="volume-control">
                <Volume2 />
                <input
                  type="range"
                  aria-label="볼륨"
                  min={0}
                  max={1}
                  step={0.05}
                  value={m.volume}
                  onChange={(e) => m.setVolume(Number(e.target.value))}
                />
              </div>
              <button
                className="icon-button queue-button"
                aria-label="재생목록 열기"
                onClick={() => { m.setLibraryTab('queue'); m.go('library'); }}
              >
                <ListMusic />
              </button>
            </section>
          )}
          <nav className="mobile-nav" aria-label="메인 메뉴">
            {mobileNavigation.map(({ id, label, mobileLabel, icon: Icon }) => (
              <button
                key={id}
                aria-label={label}
                aria-current={navPage === id ? 'page' : undefined}
                className={navPage === id ? 'active' : ''}
                onClick={() => m.go(id)}
              >
                <span className="mobile-nav-icon">
                  <Icon aria-hidden="true" />
                </span>
                <span className="mobile-nav-label">{mobileLabel}</span>
              </button>
            ))}
          </nav>
          <FloatingChat model={m} />
        </>
      )}
      <AppDialogs model={m} />
      <DemoLoginDialog model={m} />
      {m.toast && <output className="app-toast">{m.toast}</output>}
    </div>
  );
}
