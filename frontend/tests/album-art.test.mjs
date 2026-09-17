import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, access } from 'node:fs/promises';
import { DEFAULT_ALBUM_COVER, resolveAlbumCover } from '../lib/mupid-data.ts';

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');
const [primitives, catalog, collection, css] = await Promise.all([
  read('components/mupid/primitives.tsx'),
  read('lib/stream-catalog.ts'),
  read('components/mupid/collection.tsx'),
  read('app/mupid.css'),
]);

test('기본 커버 그림이 실제로 public 에 있다', async () => {
  await access(new URL('../public' + DEFAULT_ALBUM_COVER, import.meta.url));
  const svg = await read('public' + DEFAULT_ALBUM_COVER);
  // 커버 자리는 정사각이다. 비율이 어긋나면 어느 크기에서든 여백이 생긴다.
  assert.match(svg, /viewBox="0 0 320 320"/);
});

test('커버 주소가 비어 있으면 기본 커버로 내려간다', () => {
  // 서버가 곡 표지도 앨범 커버도 주지 않으면 빈 문자열이 실려 온다.
  assert.equal(resolveAlbumCover('').src, DEFAULT_ALBUM_COVER);
  assert.equal(resolveAlbumCover('   ').src, DEFAULT_ALBUM_COVER);
  assert.equal(resolveAlbumCover(undefined).src, DEFAULT_ALBUM_COVER);
  assert.equal(resolveAlbumCover(null).src, DEFAULT_ALBUM_COVER);
  for (const empty of ['', '   ', undefined, null]) {
    assert.equal(resolveAlbumCover(empty).isFallback, true);
  }
});

test('쓸 수 있는 주소는 그대로 건다', () => {
  const url = '/api/v1/storage/image/12';
  assert.deepEqual(resolveAlbumCover(url), { src: url, isFallback: false });
  // 앱에서는 `toImageSource` 가 감싼 `mupidimg` 주소가 들어온다.
  const wrapped = 'http://mupidimg.localhost/L2FwaS92MS9zdG9yYWdl';
  assert.deepEqual(resolveAlbumCover(wrapped), {
    src: wrapped,
    isFallback: false,
  });
});

test('한 번 실패한 주소는 기본 커버로 바뀌고 거기서 멈춘다', () => {
  const url = '/api/v1/storage/image/99';
  const first = resolveAlbumCover(url, null);
  assert.equal(first.src, url);

  // 읽기에 실패했다고 알려 주면 기본 커버로 갈아 끼운다.
  const retry = resolveAlbumCover(url, first.src);
  assert.equal(retry.src, DEFAULT_ALBUM_COVER);
  assert.equal(retry.isFallback, true);

  // 기본 커버마저 못 읽어도 갈아 끼울 것이 없다 — 여기서 멈춰야 `onError` 가
  // 상태를 다시 바꾸며 무한히 돌지 않는다.
  const stop = resolveAlbumCover(DEFAULT_ALBUM_COVER, DEFAULT_ALBUM_COVER);
  assert.equal(stop.src, DEFAULT_ALBUM_COVER);
  assert.equal(stop.isFallback, true);

  // 다른 곡으로 넘어가면 그 곡의 주소는 다시 시도한다.
  const other = resolveAlbumCover('/api/v1/storage/image/13', url);
  assert.equal(other.isFallback, false);
});

test('AlbumArt 가 실패를 한 번만 받아 내고 빈 자리는 그대로 둔다', () => {
  assert.ok(primitives.includes('resolveAlbumCover(track.image, failedSrc)'));
  // 이미 기본 커버면 핸들러를 떼어 다시 그리기를 끊는다.
  assert.ok(
    primitives.includes('onError={isFallback ? undefined : () => setFailedSrc(src)}'),
  );
  assert.ok(
    primitives.includes("className={isFallback ? 'album-art-fallback' : undefined}"),
  );
  // 곡을 아직 고르지 않은 자리는 예전처럼 빈 칸이다. 기본 커버와 다른 상태다.
  assert.ok(
    primitives.includes('album-art album-art-empty'),
    '곡이 없을 때의 빈 커버는 그대로 남아야 한다',
  );
  assert.ok(primitives.includes('if (!track) return'));
  // 기본 커버여도 어느 곡의 자리인지 읽어 줘야 한다.
  assert.ok(primitives.includes('alt={`${track.title} 앨범 커버`}'));
  // 앱의 `mupidimg` 경로는 next/image 최적화를 타면 안 된다.
  assert.match(primitives, /unoptimized[\s\S]{0,400}src=\{src\}/);
});

test('카탈로그가 빈 주소를 기본 커버로 옮기고 앨범 커버 차례를 건너뛰지 않는다', () => {
  assert.ok(catalog.includes('toImageSource(cover) || DEFAULT_ALBUM_COVER'));
  assert.ok(
    catalog.includes("music.image_url?.trim() || music.album.image_url?.trim() || ''"),
    '빈 문자열에서도 앨범 커버로 내려가야 한다',
  );
  assert.ok(
    !catalog.includes('/sky-background.webp'),
    '풍경 사진은 커버 자리에 맞지 않는다',
  );
});

test('빈 플레이리스트만 음표로 남는다', () => {
  // 곡이 있으면 커버가 없어도 AlbumArt 가 맡는다. 음표는 "담은 곡이 없다" 는
  // 뜻으로만 쓰인다.
  const icons = collection.match(/aria-label="비어 있는 플레이리스트"/g) ?? [];
  assert.equal(icons.length, 2, '카드와 상세 두 곳');
  assert.ok(collection.includes('{p.tracks[0] ? <AlbumArt track={p.tracks[0]} />'));
  assert.ok(
    collection.includes('{selected.tracks[0] ? <AlbumArt track={selected.tracks[0]} />'),
  );
});

test('기본 커버에는 사진용 크롭과 데모 곡 색 연출이 걸리지 않는다', () => {
  assert.match(
    css,
    /\.album-art img\.album-art-fallback\s*\{[^}]*filter: none;[^}]*transform: none;/,
  );
  assert.match(
    css,
    /\.album-art img\.album-art-fallback\s*\{[^}]*object-position: center;/,
  );
  // 데모 곡 연출은 그대로 남아 있어야 한다.
  assert.match(css, /\.art-1 img\s*\{[^}]*hue-rotate/);
  assert.match(css, /\.art-7 img\s*\{[^}]*saturate/);
  assert.match(css, /\.album-art img\s*\{[^}]*transform: scale\(1\.8\);/);
});
