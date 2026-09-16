'use client';
import { VinylRecord } from './vinyl-record';
import { MupidMascot } from './mascot';
import { DesktopHome } from './desktop-home';
import { TodayMoment } from './today-moment';
import { AlbumDeck } from './album-deck';
import { MobileListening } from './mobile-listening';
import {
  ChevronRight,
  Cloud,
  Heart,
  Sparkles,
} from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
import { AlbumArt, Heading, Waveform, PlayerControls } from './primitives';
import { forYouHeading, pickForYou } from '@/lib/recommendations';
export function HomeView({ model: m }: { model: MupidModel }) {
  const tracks = m.tracks;
  const forYou = forYouHeading(m.recommendationsReady);
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
          </div>
          <MobileListening model={m} />
          {/* 상단 헤더에 검색창이 이미 있어 홈에서는 중복이라 뺐다. */}
          <TodayMoment model={m} />
          <Heading
            eyebrow={forYou.eyebrow}
            title={forYou.title}
            action="모두 보기"
            onAction={() => {
              m.setLibraryTab('playlists');
              m.setSelectedPlaylist(1);
              m.go('library');
            }}
          />
          <AlbumDeck
            model={m}
            label="취향에 이어지는 음악"
            items={pickForYou(m.forYou, tracks, m.ignored)}
          />
          <Heading
            eyebrow="다시 듣기"
            title="최근 들은 음악"
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
        </div>
        <aside className="now-sidebar">
          {/* 들을 곡이 없으면 이 카드 대신 그 사실만 알린다. */}
          {!m.active ? <section className="now-card">
            <MupidMascot size={96} />
            <h2>아직 들을 곡이 없어요</h2>
            <p>음악이 등록되면 여기에서 바로 들을 수 있어요.</p>
          </section> : <section className="now-card">
            {m.playing && <span className="eyebrow">지금 재생 중</span>}
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
              저장한 순간 남기기
            </button>
            <button className="text-link" onClick={() => m.go('player')}>
              플레이어 크게 보기 <ChevronRight />
            </button>
          </section>}
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
