'use client';
import { useEffect, useRef, useState } from 'react';
import { formatListening } from '@/lib/report';
import { squarify } from '@/lib/treemap';
import type { ReportTrack } from '@/lib/api/endpoints';

/** 제목을 얹을 만한 최소 타일 크기(%). 이보다 작으면 앨범아트만 보여 준다. */
const LABEL_MIN_WIDTH = 14;
const LABEL_MIN_HEIGHT = 12;

type Tile = ReportTrack & { x: number; y: number; w: number; h: number };

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
              {tile.image_url && <img src={tile.image_url} alt="" />}
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
