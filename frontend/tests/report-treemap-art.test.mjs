import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');
const [treemap, image, baseUrl, session] = await Promise.all([
  read('components/mupid/daily-album-treemap.tsx'),
  read('lib/platform/image.ts'),
  read('lib/api/base-url.ts'),
  read('lib/api/session.ts'),
]);

/**
 * '오늘 들은 음악' 트리맵의 커버는 **서버가 경로로 주는 이미지**다.
 *
 * 예전에는 응답의 `image_url` 을 `<img src>` 에 그대로 걸었다. 개발 서버에서는
 * Vite 프록시가 `/api` 를 넘겨 주어 멀쩡했지만, 배포된 앱에서는 화면의 출처가
 * `http://tauri.localhost` 라 그 경로에 아무것도 없어 커버가 전부 깨졌다.
 * 리포트의 MuBTI 캐릭터가 같은 이유로 이미 `toImageSource` 를 거친다.
 *
 * 조회 주소는 파일 번호(`/api/v1/storage/image/{fileId}`)다. 키 기반이던 때는
 * 저장 키 모양이 곧 조회 계약이라 키를 손으로 만든 코드 하나가 주소를 조용히
 * null 로 만들었는데, 번호로 바꾼 뒤로는 그 종류의 사고가 나지 않는다.
 */
void test('트리맵 커버가 스킴 변환을 거친다', () => {
  const art = treemap.slice(
    treemap.indexOf('function TileArt'),
    treemap.indexOf('export function DailyAlbumTreemap'),
  );
  assert.ok(art.includes('toImageSource'), '앱에서는 스킴 변환을 거쳐야 한다');
  assert.ok(
    art.includes('resolveAlbumCover(toImageSource(imageUrl), failedSrc)'),
    '목록의 커버(`AlbumArt`)와 같은 규칙으로 기본 커버를 고른다',
  );
  // 기본 커버를 건 뒤에는 핸들러를 떼어 다시 그리기가 끝없이 돌지 않게 한다.
  assert.ok(art.includes('onError={isFallback ? undefined : () => setFailedSrc(src)}'));
  // 곡 제목은 타일의 `aria-label` 이 읽어 준다. 여기서 또 읽으면 두 번이다.
  assert.ok(art.includes('alt=""'));

  // 주소를 날것으로 거는 길이 남아 있으면 안 된다.
  assert.ok(
    !treemap.includes('<img src={tile.image_url}'),
    '경로를 그대로 걸면 배포된 앱에서 다시 깨진다',
  );
  assert.ok(treemap.includes('<TileArt imageUrl={tile.image_url} />'));
});

/**
 * 웹이라고 늘 같은 출처인 것은 아니다. 화면과 API 의 출처가 갈리는 배포에서는
 * `VITE_MUPID_API_BASE` 가 채워지는데, `fetch` 에는 `ApiClient` 가 그 주소를
 * 붙여 주지만 `<img src>` 에는 붙여 줄 사람이 없다.
 */
void test('웹에서도 서버 이미지 경로에 API 주소를 붙인다', () => {
  assert.ok(
    image.includes("url.startsWith('/api/') ? `${apiBaseUrl}${url}` : url"),
    '서버 이미지 경로만 골라 API 주소를 붙인다',
  );
  // 같은 출처 배포(ec2)에서는 빈 문자열이라 주소가 그대로 남는다.
  assert.ok(baseUrl.includes('VITE_MUPID_API_BASE'));
  assert.ok(baseUrl.includes("?? ''"));
});

/**
 * 주소는 한 곳에만 있어야 한다. `session.ts` 가 들고 있으면 `<img>` 쪽이 그것을
 * 부르는 순간 `platform → api/session → platform` 으로 순환이 된다.
 */
void test('API 주소를 한 곳에서만 읽는다', () => {
  assert.ok(session.includes("import { apiBaseUrl } from './base-url'"));
  assert.ok(session.includes('baseUrl: apiBaseUrl'));
  assert.ok(
    !session.includes('VITE_MUPID_API_BASE'),
    '주소를 읽는 자리는 `base-url.ts` 하나다',
  );
  assert.ok(image.includes("import { apiBaseUrl } from '../api/base-url'"));
  assert.ok(
    !image.includes("from '../api/session'"),
    '플랫폼 계층이 세션을 부르면 순환이 된다',
  );
});
