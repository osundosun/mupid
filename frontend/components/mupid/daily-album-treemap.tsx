'use client';
import { useEffect, useRef, useState } from 'react';
import { formatListening } from '@/lib/report';
import { resolveAlbumCover } from '@/lib/mupid-data';
import { toImageSource } from '@/lib/platform/image';
import { squarify } from '@/lib/treemap';
import type { ReportTrack } from '@/lib/api/endpoints';

/** 제목을 얹을 만한 최소 타일 크기(%). 이보다 작으면 앨범아트만 보여 준다. */
const LABEL_MIN_WIDTH = 14;
const LABEL_MIN_HEIGHT = 12;

type Tile = ReportTrack & { x: number; y: number; w: number; h: number };

/**
 * 타일 한 칸의 앨범 아트.
 *
 * **주소를 그대로 걸면 배포된 앱에서만 전부 깨진다.** 서버는 커버를
 * `/api/v1/storage/image/ALBUM/…` 처럼 **경로로** 준다. 웹은 API 와 같은 출처라
 * 그대로 열리지만, 앱(Tauri)의 출처는 `http://tauri.localhost` 라 그 경로에는
 * 아무것도 없다 — 웹뷰가 엉뚱한 응답을 그리거나 깨진 아이콘을 남긴다.
 * `toImageSource` 를 거치면 Rust 가 대신 받아 `mupidimg` 스킴으로 돌려준다
 * (`lib/platform/image.ts`). 리포트의 MuBTI 캐릭터도 같은 이유로 이미 그렇게 한다.
 *
 * 개발 서버에서는 Vite 프록시가 `/api` 를 넘겨 주어 경로만으로도 열린다. 로컬에서만
 * 멀쩡했던 것이 그래서다.
 *
 * 주소가 없거나 읽지 못하면 기본 커버로 내려간다. 목록의 커버(`AlbumArt`)와 같은
 * 규칙이라 실패는 한 번으로 끝난다 — 기본 커버를 건 뒤에는 `onError` 를 떼어 다시
 * 그리기가 끝없이 돌지 않는다.
 */
function TileArt({ imageUrl }: { imageUrl: string | null }) {
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
  const { src, isFallback } = resolveAlbumCover(toImageSource(imageUrl), failedSrc);
  // 곡 제목은 타일의 `aria-label` 이 이미 읽어 준다. 여기서 또 읽으면 두 번이다.
  return <img
    src={src}
    alt=""
    loading="lazy"
    decoding="async"
    onError={isFallback ? undefined : () => setFailedSrc(src)}
  />;
}

/**
 * 곡 목록을 면적이 청취 시간에 비례하는 타일로 바꾼다.
 * 배치 계산은 `lib/treemap` 의 순수 함수가 한다.
 */
function layout(tracks: ReportTrack[]): Tile[] {
  const rects = squarify(tracks.map((track) => track.listening_ms));
  return tracks.map((track, index) => ({ ...track, ...rects[index] }));
}

/**
 * 오늘 곡별로 얼마나 들었는가.
 *
 * 값은 서버가 `PLAY_PROGRESS` 하트비트에서 집계한 **실제로 귀에 닿은 시간**이다.
 * 재생기를 켜 둔 시간이 아니라서 일시정지·건너뛴 구간이 빠져 있다.
 */
export function DailyAlbumTreemap({
  tracks,
  totalMs,
  loading,
}: {
  tracks: ReportTrack[];
  totalMs: number;
  loading: boolean;
}) {
  const tiles = layout(tracks);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [position, setPosition] = useState({ x: 0, y: 0 });
  const map = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement | null>(null);
  const selected = tiles.find((tile) => tile.music_id === selectedId);

  useEffect(() => {
    if (selectedId === null) return;
    const outside = (event: PointerEvent) => {
      if (!map.current?.contains(event.target as Node)) setSelectedId(null);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setSelectedId(null);
        trigger.current?.focus();
      }
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', escape);
    };
  }, [selectedId]);

  // 곡이 사라지면(날짜가 바뀌거나 갱신되면) 열려 있던 설명도 닫는다.
  useEffect(() => {
    if (selectedId !== null && !tiles.some((tile) => tile.music_id === selectedId)) {
      setSelectedId(null);
    }
  }, [tiles, selectedId]);

  return <section className="report-card daily-album-report">
    <h2>오늘 들은 음악</h2>
    <p>{totalMs > 0 ? `모두 ${formatListening(totalMs)} 들었어요` : '일간 리포트'}</p>
    {!tracks.length
      ? <p className="daily-album-detail" role="status">
          {loading ? '오늘 들은 음악을 불러오고 있어요…' : '오늘은 아직 들은 음악이 없어요.'}
        </p>
      : <>
          <div className="daily-album-map" ref={map} aria-label="곡별 청취 비율">
            {tiles.map((tile) => <button
              key={tile.music_id}
              className="daily-album-cell"
              style={{
                left: `${tile.x}%`,
                top: `${tile.y}%`,
                width: `${tile.w}%`,
                height: `${tile.h}%`,
              }}
              aria-expanded={selectedId === tile.music_id}
              aria-controls={selectedId === tile.music_id ? 'daily-album-popup' : undefined}
              aria-label={`${tile.title}, ${formatListening(tile.listening_ms)}, 청취 정보 보기`}
              onClick={(event) => {
                trigger.current = event.currentTarget;
                const area = map.current!.getBoundingClientRect();
                const rect = event.currentTarget.getBoundingClientRect();
                setPosition({
                  x: Math.max(0, Math.min(rect.left - area.left, area.width - Math.min(240, area.width))),
                  y: Math.max(0, Math.min(rect.bottom - area.top + 6, area.height - 145)),
                });
                setSelectedId((value) => (value === tile.music_id ? null : tile.music_id));
              }}
            >
              <TileArt imageUrl={tile.image_url} />
              {/* 좁은 타일에 제목을 얹으면 글자가 잘려 오히려 안 읽힌다. */}
              {tile.w >= LABEL_MIN_WIDTH && tile.h >= LABEL_MIN_HEIGHT && <span>{tile.title}</span>}
            </button>)}
            {selected && <section
              id="daily-album-popup"
              className="daily-album-popup"
              aria-label="선택한 곡 청취 정보"
              style={{ left: position.x, top: position.y }}
            >
              <button
                className="daily-popup-close"
                aria-label="청취 정보 닫기"
                onClick={() => {
                  setSelectedId(null);
                  trigger.current?.focus();
                }}
              >×</button>
              <div role="status">
                <strong>{selected.title}</strong>
                <p>{selected.artist_name}</p>
                <p>청취 시간 · {formatListening(selected.listening_ms)}</p>
                <p>비율 · {(selected.share * 100).toFixed(1)}%</p>
              </div>
            </section>}
          </div>
          <p className="demo-note">타일 크기가 곧 들은 시간이에요. 앨범을 눌러 자세히 볼 수 있어요.</p>
        </>}
  </section>;
}
