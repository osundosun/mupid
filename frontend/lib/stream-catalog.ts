/**
 * 서버에 실제로 올라가 있는 곡 목록 (임시 하드코딩).
 *
 * 곡 검색·목록 API 가 아직 없어서 화면이 `music_id` 를 알 방법이 없다.
 * 그때까지만 여기에 적어 둔다. `GET /api/v1/music` 이 생기면 이 파일을 지우고
 * 서버 응답을 `Track` 으로 정규화해 쓰면 된다.
 *
 * `musicId` 가 있는 곡은 `<audio>` 데모 음원이 아니라 청크 스트리밍으로
 * 재생된다 (`lib/player/`).
 *
 * ⚠ 여기 값은 개발 DB 기준이다. DB 를 비우고 배치를 다시 돌리면 `musicId` 가
 * 바뀐다. 재생이 "아직 재생할 수 있는 음원이 없어요" 로 실패하면 먼저
 * `SELECT music_id, title FROM music` 을 확인한다.
 */

import type { Track } from './mupid-data';

/** 데모 곡(1~8)과 겹치지 않는 번호대를 쓴다. */
const FIRST_ID = 1001;

type StreamSeed = {
  musicId: number;
  title: string;
  /** 서버 스트림의 duration_ms 를 초로 옮긴 값. */
  duration: number;
};

const seeds: StreamSeed[] = [
  { musicId: 2, title: '첫 번째 곡', duration: 13.02 },
  { musicId: 1, title: '두 번째 곡', duration: 7.02 },
  { musicId: 3, title: '모노 테스트 곡', duration: 8.02 },
];

export const streamTracks: Track[] = seeds.map((seed, index) => ({
  id: FIRST_ID + index,
  musicId: seed.musicId,
  title: seed.title,
  artist: '무피드 테스트 아티스트',
  album: '무피드 테스트 앨범',
  genre: 'Test',
  mood: '실험적인',
  // 구간 UI 가 duration 안쪽 값을 기대한다. 곡 길이에 맞춰 잡는다.
  moment: Math.max(0, seed.duration * 0.3),
  duration: seed.duration,
  match: 100,
  reason: '서버에서 청크 스트리밍으로 받아오는 곡이에요.',
  image: '/sky-background.webp',
  // 스트리밍 곡은 이 URL 을 쓰지 않는다. <audio> 경로로 새지 않도록 비워 둔다.
  audio: '',
  tags: ['스트리밍'],
  sections: [
    { label: '도입', start: 0, end: seed.duration * 0.4 },
    { label: '전개', start: seed.duration * 0.4, end: seed.duration },
  ],
}));

/** 이 곡을 청크 스트리밍으로 재생해야 하는가. */
export const isStreamed = (
  track: Pick<Track, 'musicId'> | null | undefined,
): track is Track & { musicId: number } =>
  typeof track?.musicId === 'number';
