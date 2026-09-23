/**
 * 서버 곡 → 화면의 곡(`Track`) 변환과 번호 규칙.
 *
 * 화면에 보이는 곡은 전부 서버에서 온다 — 데모 곡은 없앴다. 앱을 열 때 앞 100곡을
 * 받아 두던 "카탈로그" 도 없앴다(S15P21A204-446). 곡을 받아 오는 일은
 * `lib/server-tracks.ts` 가 한다. 이 파일 이름은 그 시절에 붙은 것이다.
 *
 * 재생은 청크 스트리밍 한 경로뿐이다 (`lib/player/`).
 */

import type { MusicSummary } from './api/endpoints';
import { DEFAULT_ALBUM_COVER, moodLabel, type Track } from './mupid-data';
import { toImageSource } from './platform';

/**
 * 화면용 곡 번호는 서버 번호에 이 값을 더해 만든다.
 *
 * 저장해 둔 예전 데이터(데모 곡 번호 1~8)와 섞이지 않게 하려는 것이다.
 * 서버로 보낼 때는 언제나 `musicId` 를 쓴다.
 *
 * **추천도 같은 규칙을 써야 한다.** 다르게 매기면 같은 곡이 대기열·제외 목록·재생
 * 중 표시에서 서로 다른 곡으로 읽힌다 → `lib/recommendations.ts`
 */
export const ID_OFFSET = 1000;

/**
 * 서버 곡 → 화면의 곡. **검색·추천·큐·보관함이 모두 같은 변환을 쓴다.**
 *
 * 두 벌이 되면 커버를 고르는 규칙이나 `id` 규칙이 한쪽만 고쳐져, 같은 곡이 목록에
 * 따라 다르게 보인다.
 */
export const toTrack = (music: MusicSummary): Track => {
  const duration = (music.duration_ms ?? 0) / 1000;
  // 무드는 서버가 코드 이름(영문 `code_name`)으로 준다. 화면 값(`mood`·`tags`)은
  // 한국어로 옮긴다 — 온보딩 답(`display_name`)과 같은 말이어야 취향 비교
  // (`pickTodayMoments`)와 탐색 필터(`filterExploreTracks`)가 맞는다.
  const moods = music.moods.map(moodLabel);
  const tags = [...music.genres, ...moods];
  // 곡마다 표지가 따로 있는 카탈로그(flow_music)가 있고, 앨범 커버만 있는 곡도
  // 있다. 곡 표지 → 앨범 커버 순으로 내려간다.
  //
  // `??` 가 아니라 `||` 인 것은 빈 문자열 때문이다. 없다는 뜻으로 `null` 이
  // 오기도 하고 `""` 가 오기도 하는데, `??` 는 `""` 를 값으로 쳐서 앨범 커버
  // 차례를 통째로 건너뛴다 — 곡 표지만 비어 있고 앨범 커버는 멀쩡한 곡이
  // 기본 커버로 떨어지던 자리다.
  const cover = music.image_url?.trim() || music.album.image_url?.trim() || '';
  return {
    id: ID_OFFSET + music.music_id,
    musicId: music.music_id,
    title: music.title,
    artist: music.artist.name,
    artistId: music.artist.artist_id,
    artistImage: toImageSource(music.artist.image_url?.trim()),
    album: music.album.title,
    genre: music.genres[0] ?? '',
    mood: moods[0] ?? '',
    // 구간 UI 가 duration 안쪽 값을 기대한다. 곡 길이에 맞춰 잡는다.
    moment: Math.max(0, duration * 0.3),
    duration,
    // 추천 점수·추천 이유는 아직 서버가 주지 않는다 (api-todo P3).
    match: 100,
    reason: '',
    // 앱에서는 원격 이미지를 웹뷰가 직접 받지 않는다. `toImageSource` 가
    // `mupidimg` 로 감싸고 Rust 가 대신 받아 온다 (`app/src/image.rs`).
    //
    // 주소가 아예 없을 때만 여기서 기본 커버로 내려간다. **주소는 있는데 받아
    // 오지 못하는 경우는 여기서 알 수 없다** — 등록된 커버 파일이 사라졌거나
    // 임시값으로 들어가 있으면 서버가 그 경로에 `FILE_NOT_FOUND` 를 준다
    // (`api` 의 `StorageImageServiceImpl`: ALBUM·TRACK 은 기본 이미지 콜백이
    // 없다). 그쪽은 화면이 이미지를 걸어 본 뒤에야 알 수 있어서 `AlbumArt` 의
    // `onError` 가 받아 낸다.
    image: toImageSource(cover) || DEFAULT_ALBUM_COVER,
    tags: tags.length ? tags : ['스트리밍'],
    // 구간은 `GET /api/v1/music/{id}/analysis` 가 준다. 다만 분석 파이프라인이
    // 아직 돌지 않아 대부분 비어 있어서, 여기서는 곡을 둘로 나눠 둔다.
    // 재생 대상이 되면 `use-mupid` 가 분석 결과로 갈아 끼운다.
    //
    // 길이를 모르면(`streams` 에 행이 없어 `duration_ms` 가 null) 나눌 기준이
    // 없어 비워 둔다. 읽는 쪽은 `getSongSectionAt` 이 받아 준다.
    sections: duration
      ? [
          { label: '도입', start: 0, end: duration * 0.4 },
          { label: '전개', start: duration * 0.4, end: duration },
        ]
      : [],
  };
};

/** 이 곡을 청크 스트리밍으로 재생해야 하는가. */
export const isStreamed = (
  track: Pick<Track, 'musicId'> | null | undefined,
): track is Track & { musicId: number } => typeof track?.musicId === 'number';

/** 화면 번호 → 서버 번호. 서버 곡이 아니면(데모 곡 1~8 등) `undefined`. */
export const musicIdOfTrackId = (trackId: number): number | undefined =>
  trackId > ID_OFFSET ? trackId - ID_OFFSET : undefined;
