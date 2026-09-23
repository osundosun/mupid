'use client';
import { VinylRecord } from './vinyl-record';
import { MupidMascot } from './mascot';
import { DesktopHome } from './desktop-home';
import { TodayMoment } from './today-moment';
import { AlbumDeck } from './album-deck';
import { MobileListening } from './mobile-listening';
import { InquiryButton } from './inquiry-dialog';
import {
  ChevronRight,
  Cloud,
  Heart,
  Sparkle,
} from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
import { Heading, TrackRow, Waveform, PlayerControls } from './primitives';
import { forYouHeading, pickForYou } from '@/lib/recommendations';
import { useTrackWaveform } from '@/lib/track-waveform';
export function HomeView({ model: m }: { model: MupidModel }) {
  const forYou = forYouHeading(m.recommendationsReady);
  // '지금 재생 중' 카드의 파형은 현재 기기에서 음원 청크를 분석한다.
  // 듣고 있는 곡이라 청크는 어차피 받는 중이다.
  const wave = useTrackWaveform(m.active);
  return (
    <>
      <DesktopHome model={m} />
      <div className="home-grid">
        <div className="home-feed">
          <div className="greeting">
            <div className="greeting-copy">
              <h1>안녕하세요, {m.displayName}님! <Cloud className="greeting-cloud" aria-hidden="true" /></h1>
              <p>
                오늘의 음악을 만나보세요 :D
              </p>
            </div>
            <InquiryButton model={m} />
          </div>
          <MobileListening model={m} />
          {/* 상단 헤더에 검색창이 이미 있어 홈에서는 중복이라 뺐다. */}
          <TodayMoment model={m} />
          <Heading eyebrow={forYou.eyebrow} title={forYou.title} />
          <AlbumDeck
            model={m}
            label="취향에 이어지는 음악"
            items={pickForYou(m.forYou, m.popularTracks, m.ignored)}
          />
          <Heading
            eyebrow="다시 듣기"
            title="최근 들은 음악"
            action="감상 기록"
            onAction={() => m.go('profile')}
          />
          <div className="recent-grid">
            {!m.history.some(id => m.knownTracks.some(track => track.id === id)) && (
              <p className="listening-history-empty">아직 감상 기록이 없어요.<br />음악을 들으면 여기에 차곡차곡 모아둘게요.</p>
            )}
            {m.history
              .slice(0, 4)
              .map((id) => m.knownTracks.find((t) => t.id === id)!)
              .filter(Boolean)
              .map((t) => (
                /*
                 * 다시 듣기는 추천이 아니다. 홈에 있다는 이유로 `RECOMMEND` 로 세면
                 * 이미 아는 곡을 되꺼낸 것이 추천 성과로 잡힌다.
                 */
                <TrackRow
                  key={t.id}
                  track={t}
                  model={m}
                  showDetail={false}
                  onPlay={() => m.play(t, false, undefined, 'RECENT')}
                />
              ))}
          </div>
        </div>
        <aside className="now-sidebar">
          {/* 곡을 고르기 전에는 탐색을 안내한다. */}
          {!m.active ? <section className="now-card">
            <MupidMascot size={96} />
            <h2>듣고 싶은 음악을 골라보세요</h2>
            <p>추천 음악이나 탐색에서 곡을 선택하면 재생이 시작돼요.</p>
            <button className="primary-button" onClick={() => m.go('search')}>음악 둘러보기</button>
          </section> : <section className="now-card">
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
            <button className="track-artist-link" onClick={() => m.setSelectedArtist(m.active!.artist)}>{m.active.artist}</button>
            <Waveform
              samples={wave.values ?? undefined}
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
              <Sparkle />
              Mupid AI와 대화하기
            </button>
          </section>
        </aside>
      </div>
    </>
  );
}
