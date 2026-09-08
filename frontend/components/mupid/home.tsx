'use client';
import { VinylRecord } from './vinyl-record';
import { MascotAccent, MupidMascot } from './mascot';
import { DesktopHome } from './desktop-home';
import { AlbumDeck } from './album-deck';
import { MobileListening } from './mobile-listening';
import { HomeShortcuts } from './home-shortcuts';
import {
  Bell,
  ChevronRight,
  Cloud,
  Heart,
  Search,
  Sparkles,
  Play,
} from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
import { AlbumArt, Heading, Waveform, PlayerControls } from './primitives';
export function HomeNotifications({ model: m }: { model: MupidModel }) {
  return (
    <div className="notification-wrap">
      <button
        aria-label="알림"
        aria-expanded={m.notificationOpen}
        className="icon-button"
        onClick={() => m.setNotificationOpen(!m.notificationOpen)}
      >
        <Bell />
      </button>
      {m.notificationOpen && (
        <div className="notification-card">
          <strong>새로운 순간을 만나보세요</strong>
          <p>오늘의 Mupid Mix가 준비됐어요.</p>
          <button
            className="text-link"
            onClick={() => {
              m.setSelectedPlaylist(
                m.playlists.find((playlist) => playlist.id === 1)?.id ?? null,
              );
              m.setLibraryTab('playlists');
              m.go('library');
            }}
          >
            내 믹스 확인하기 <ChevronRight />
          </button>
        </div>
      )}
    </div>
  );
}
export function HomeView({ model: m }: { model: MupidModel }) {
  const tracks = m.tracks;
  return (
    <>
      <DesktopHome model={m} />
      <div className="home-grid">
        <div className="home-feed">
          <div className="greeting">
            <div className="greeting-copy">
              <h1>안녕하세요, {m.displayName}님!</h1>
              <p>
                오늘도 좋은 음악과 함께해요 <Cloud />
              </p>
            </div>
            <div className="greeting-companion">
              <MascotAccent size={72} className="greeting-mascot" />
              <HomeNotifications model={m} />
            </div>
          </div>
          <HomeShortcuts model={m} />
          <MobileListening model={m} />
          <form
            className="search-box"
            onSubmit={(e) => {
              e.preventDefault();
              m.go('search');
            }}
          >
            <Search />
            <input
              aria-label="음악 검색"
              placeholder="곡, 아티스트, 듣고 싶은 분위기"
              value={m.query}
              onChange={(e) => m.setQuery(e.target.value)}
            />
            <button className="icon-button" aria-label="검색하기" type="submit">
              <ChevronRight />
            </button>
          </form>
          <Heading
            title="지금의 기분으로"
            action="모두 보기"
            onAction={() => m.go('search')}
          />
          <div className="mix-grid">
            {[
              '새벽의 온도',
              'Dreamy Morning',
              '구름 뒤의 산책',
              '잔잔한 드라이브',
            ].map((name, i) => (
              <button
                className="mix-card"
                key={name}
                onClick={() => m.openTrack(tracks[[2, 0, 1, 5][i] % tracks.length])}
              >
                <span className="mix-cover">
                  <AlbumArt track={tracks[[2, 0, 1, 5][i] % tracks.length]} />
                  <span className="cover-play">
                    <Play fill="currentColor" />
                  </span>
                </span>
                <strong>{name}</strong>
                <p>
                  {
                    [
                      '고요하게 채우는 새벽',
                      '느긋한 아침의 시작',
                      '일상 속 작은 쉼표',
                      '어디든 떠나고 싶은',
                    ][i]
                  }
                </p>
              </button>
            ))}
          </div>
          <Heading
            title={`${m.displayName}의 Mupid Mix`}
            action="모두 보기"
            onAction={() => {
              m.setLibraryTab('playlists');
              m.setSelectedPlaylist(1);
              m.go('library');
            }}
          />
          <AlbumDeck
            model={m}
            label={`${m.displayName}의 Mupid Mix`}
            items={tracks.filter((t) => !m.ignored.includes(t.id)).slice(0, 6)}
          />
          <Heading
            title="다시 만나고 싶은 순간"
            action="감상 기록"
            onAction={() => m.go('profile')}
          />
          <div className="recent-grid">
            {m.history
              .slice(0, 4)
              .map((id) => tracks.find((t) => t.id === id)!)
              .filter(Boolean)
              .map((t) => (
                <button
                  className="recent-track"
                  key={t.id}
                  onClick={() => m.openTrack(t)}
                >
                  <AlbumArt track={t} />
                  <span>
                    <strong>{t.title}</strong>
                    <small>{t.artist}</small>
                  </span>
                </button>
              ))}
          </div>
          <section className="sky-banner home-discovery-note">
            <div>
              <h2>좋아하는 곡에서, 새로운 발견.</h2>
              <p>익숙한 취향에서 조금 더 넓게 들어보세요.</p>
              <button className="text-link" onClick={() => m.go('search')}>
                음악 둘러보기 <ChevronRight />
              </button>
            </div>
            <MupidMascot
              size={130}
              className="cupid"
              alt="구름 위의 작은 큐피드"
            />
          </section>
        </div>
        <aside className="now-sidebar">
          <section className="now-card">
            <span className="eyebrow">NOW PLAYING</span>
            <VinylRecord
              playing={m.playing}
              track={m.active}
              unoptimized
              className="now-art"
              src="/sky-vinyl.webp"
              alt="구름빛 라벨의 LP 레코드"
              width="240"
              height="240"
            />
            <h2>{m.active.title}</h2>
            <p>{m.active.artist}</p>
            <Waveform
              duration={m.active.duration}
              start={m.active.moment}
              end={Math.min(m.active.moment + 20, m.active.duration)}
              progress={m.time}
            />
            <PlayerControls model={m} />
            <button
              className="secondary-button wide"
              onClick={() => m.openMoment()}
            >
              <Heart className="pink" />
              좋아한 구간 남기기
            </button>
            <button className="text-link" onClick={() => m.go('player')}>
              플레이어 크게 보기 <ChevronRight />
            </button>
          </section>
          <section className="ai-invitation">
            <MupidMascot size={76} />
            <h3>이 노래, 다른 느낌으로</h3>
            <p>
              지금의 분위기를 이어갈
              <br />
              다음 음악을 함께 찾아요.
            </p>
            <button className="secondary-button wide" onClick={m.openChat}>
              <Sparkles />
              Mupid AI와 대화하기
            </button>
          </section>
        </aside>
      </div>
    </>
  );
}
