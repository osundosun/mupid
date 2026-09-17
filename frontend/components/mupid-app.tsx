'use client';
import { useEffect, useRef, useState } from 'react';
import { mupidService } from '@/lib/services';
import { clearBootstrap, readBootstrap, writeBootstrap } from '@/lib/services/bootstrap-cache';
import { profilePhoto, profilePhotoStore } from '@/lib/profile-photo';
import { useStore } from '@/lib/store';
import { fetchCatalog } from '@/lib/stream-catalog';
import { restoreSession, sessionStore } from '@/lib/api/session';
import { watchSafeArea } from '@/lib/platform';
import { checkForUpdates } from '@/lib/platform/update';
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
import { VolumeSlider } from '@/components/mupid/volume-slider';
import { PlaybackStatus } from '@/components/mupid/playback-status';
import {
  AlbumArt,
  Brand,
  LikeButton,
  PlayerControls,
} from '@/components/mupid/primitives';
import { HeaderSearch } from '@/components/mupid/header-search';
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
import { QueueDock } from '@/components/mupid/queue-dock';
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
  /*
   * 곡 목록은 **로그인한 사람만** 받을 수 있다(`SecurityConfig`). 그래서 세션을
   * 먼저 복원하고 그 다음에 받는다. 순서가 뒤집히면 첫 방문자의 요청이 401 로
   * 떨어져 로그인 화면에 "음악을 불러오지 못했어요" 가 뜬다.
   *
   * 비로그인이면 빈 목록으로 연다 — 곡이 없어도 앱은 그대로 돌아간다. 로그인
   * 하면 계정이 바뀐 것을 보고 여기서 다시 받는다.
   */
  const accountId = useStore(sessionStore, s =>
    s.status === 'authenticated' ? s.user?.user_id ?? 0 : 0);
  // 이미 받아 둔 값이 있으면 그것으로 시작한다. 이 컴포넌트가 다시 마운트돼도
  // 로딩 화면을 거치지 않아야 아래 `LoadedMupidApp` 이 살아남고, 그래야 듣던
  // 곡과 재생 목록이 그대로 남는다 (`lib/services/bootstrap-cache.ts`).
  const [data, setData] = useState<Bootstrap | null>(() => readBootstrap(accountId));
  const [error, setError] = useState('');
  const [attempt, setAttempt] = useState(0);
  // 시스템 바가 가리는 자리를 CSS 에 건넨다. 곡을 받기 전 화면도 상태바 밑으로
  // 들어가면 안 되므로 여기, 가장 바깥에서 한 번만 건다.
  useEffect(() => watchSafeArea(), []);
  // 기동 뒤 조용히 업데이트를 확인한다. **첫 화면을 막지 않는다** — 결과를
  // 기다리지 않고 던져 두기만 한다. 새 웹 번들이 있으면 디스크에만 활성화되고
  // 화면은 옛 번들로 계속 돈다(다음 콜드 스타트에 교체된다). 그래야 듣던 곡이
  // 끊기지 않는다. 실패해도 아무 일도 일어나지 않는다 — 업데이트 확인이 안
  // 되는 것은 사용자가 알 필요가 없는 일이다(`lib/platform/update.ts`).
  useEffect(() => {
    void checkForUpdates();
  }, []);
  useEffect(() => {
    // 같은 계정 몫을 이미 받아 뒀으면 그대로 쓴다. 화면을 옮길 때마다 카탈로그를
    // 다시 받으면 그동안 로딩 화면이 뜨고 재생이 끊긴다. 계정이 바뀌었을 때는
    // 열쇠가 달라 여기서 걸리지 않으므로 아래로 내려가 새로 받는다.
    const cached = readBootstrap(accountId);
    if (cached) { setData(cached); return; }
    let live = true;
    void (async () => {
      try {
        const session = await restoreSession();
        const catalog = session.status === 'authenticated' ? await fetchCatalog() : [];
        // 서버 카탈로그는 화면 쪽에서 받아 서비스 계층에 넘긴다.
        // 서비스 계층은 브라우저 저장만 건드린다 (docs/server-integration.md).
        const value = await mupidService.bootstrap(catalog);
        // 저장은 `live` 와 무관하게 남긴다. 화면이 먼저 사라졌더라도 받아 온
        // 값은 다음 마운트가 쓸 수 있고, 그래야 두 번 받지 않는다.
        writeBootstrap(accountId, value);
        if (live) setData(value);
      } catch {
        if (live) setError('음악을 불러오지 못했어요.');
      }
    })();
    return () => { live = false; };
  }, [attempt, accountId]);
  /*
   * 곡이 한 곡도 없어도 앱은 그대로 연다 — 음악이 없을 뿐이지 고장이 아니다.
   * 마이페이지·플레이리스트처럼 곡이 필요 없는 화면은 평소처럼 써야 한다.
   * 여기서 막는 것은 **아직 못 받아온 동안** 과 **못 받아온 경우** 뿐이다.
   */
  if (!data) return <main className="mupid-shell" style={{ padding: 32, paddingTop: 'calc(32px + var(--safe-area-inset-top))' }} aria-busy={!error}>
    <output>{error || '음악을 준비하고 있어요…'}</output>
    {/* 받아 둔 값을 먼저 버린다. 남겨 두면 "다시 불러오기" 가 캐시만 되읽고 끝난다. */}
    {error && <button className="secondary-button" onClick={() => { setError(''); clearBootstrap(); setAttempt(v => v + 1); }}>다시 불러오기</button>}
  </main>;
  return <LoadedMupidApp bootstrap={data} />;
}
function LoadedMupidApp({ bootstrap }: { bootstrap: Bootstrap }) {
  const m = useMupid(bootstrap);
  const [accountOpen, setAccountOpen] = useState(false);
  const photo = useStore(profilePhotoStore, s => s.url);
  const ownPhoto = useStore(profilePhotoStore, s => !s.isDefault);
  const accountRef = useRef<HTMLDivElement>(null);
  const accountButton = useRef<HTMLButtonElement>(null);
  useEffect(() => { setAccountOpen(false); }, [m.page]);
  // 상단바 사진. 예전에는 브라우저 저장(`readPhoto`)만 봐서 **계정에 올린
  // 사진이 끝내 안 보였다.** 이제 마이페이지와 같은 자리에서 읽는다.
  useEffect(() => {
    if (!m.demoSession) { profilePhoto.clear(); return; }
    const abort = new AbortController();
    void profilePhoto.refresh(abort.signal).catch(() => {});
    return () => abort.abort();
  }, [m.demoSession]);
  useEffect(() => {
    if (!accountOpen) return;
    const outside = (event: PointerEvent) => { if (!accountRef.current?.contains(event.target as Node)) setAccountOpen(false); };
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') { setAccountOpen(false); accountButton.current?.focus(); } };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape); };
  }, [accountOpen]);
  const desktopDock = useDesktopPlayerDock();
  const hideMini =
    m.page === 'welcome' ||
    // 들을 곡이 없으면 띄울 것도 없다.
    !m.active ||
    // 가입 직후처럼 아직 한 곡도 듣지 않았다면 띄우지 않는다.
    !m.hasPlayed ||
    // 플레이어 화면에는 큰 컨트롤이 있으니 미니 플레이어를 겹치지 않는다.
    (!desktopDock && m.page === 'player');
  const desktopNavPage =
    m.page === 'moment' ? 'player' : m.page === 'similar' ? 'search' : m.page;
  const navPage = desktopNavPage;
  // 로그인 여부를 알기 전에는 아무 화면도 보여주지 않는다. 먼저 그리면 시작
  // 화면이 깜빡였다가 바뀌고, 비로그인에게 앱 골격이 잠깐 노출된다.
  if (!m.authReady) {
    return <main className="mupid-shell" style={{ padding: 32, paddingTop: 'calc(32px + var(--safe-area-inset-top))' }} aria-busy="true">
      <output>로그인 상태를 확인하고 있어요…</output>
    </main>;
  }
  return (
    <div className={`mupid-shell ${hideMini ? 'no-mini' : ''} page-${m.page}`}>
      <a href="#main-content" className="skip-link">
        본문으로 이동
      </a>
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
            <HeaderSearch model={m} />
            <div className="nav-account" ref={accountRef}>
              <button ref={accountButton} className="nav-avatar" aria-label={m.demoSession ? '내 계정 메뉴' : '로그인'} aria-expanded={accountOpen} aria-controls="nav-account-panel" disabled={!m.authReady} onClick={() => m.demoSession ? setAccountOpen(value => !value) : m.openLogin()}>
                {/* 사진을 올리지 않았으면 `photo` 에 기본 이미지가 담겨 온다.
                    사람 아이콘은 그것마저 없을 때 — 토큰이 없는 체험 세션이나
                    조회 실패 — 의 마지막 대비책이다. */}
                {m.demoSession ? photo ? <img src={photo} alt={ownPhoto ? '내 프로필' : '기본 프로필'} /> : <UserRound size={22} aria-hidden="true" /> : <LogIn size={22} />}
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
                <AlbumArt track={m.active!} />
              </button>
              <button className="track-info" onClick={() => m.go('player')}>
                <span className="mini-title">
                  <strong>{m.active!.title}</strong>
                  <PlaybackStatus playing={m.playing} />
                </span>
                <span>{m.active!.artist}</span>
              </button>
              <MiniTimeline model={m} />
              <PlayerControls model={m} compact />
              <LikeButton model={m} />
              <div className="volume-control">
                <Volume2 />
                <VolumeSlider />
              </div>
              {/*
                예전에는 보관함의 '재생목록' 탭으로 **화면을 옮겼다.** 곡을 고르다
                목록을 확인하면 고르던 자리를 잃었다. 지금은 이 자리에서 독을 여닫는다.
              */}
              <button
                className="icon-button queue-button"
                aria-label={m.queueOpen ? '재생 목록 접기' : '재생 목록 펼치기'}
                aria-expanded={m.queueOpen}
                aria-controls="queue-dock"
                onClick={m.toggleQueue}
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
          <QueueDock model={m} />
          <FloatingChat model={m} />
        </>
      )}
      <AppDialogs model={m} />
      <DemoLoginDialog model={m} />
      {m.toast && <output className="app-toast">{m.toast}</output>}
    </div>
  );
}
