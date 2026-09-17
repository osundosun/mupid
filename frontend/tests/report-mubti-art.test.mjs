import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');

const [collection, css, responsive, report] = await Promise.all([
  read('components/mupid/collection.tsx'),
  read('app/mupid.css'),
  read('app/responsive-polish.css'),
  read('lib/report.ts'),
]);

/**
 * `/report` 의 MuBTI 캐릭터는 **서버가 준 유형 일러스트**다.
 *
 * 예전에는 로컬 자산(`MupidMascot`)을 그렸고, 응답의 `image_url` 은 읽지도 않았다 —
 * 그래서 서버가 값을 못 만들어도(키 규약이 어긋나 늘 null) 화면은 조용히 마스코트를
 * 그려 아무도 몰랐다. 이 테스트는 그 자리가 **서버 이미지**임을 못박는다.
 */
void test('the MuBTI card draws the server illustration, not a local asset', () => {
  const art = collection.slice(
    collection.indexOf('function ReportMubtiArt'),
    collection.indexOf('export function ReportView'),
  );

  // 응답의 image_url 을 쓴다.
  assert.ok(art.includes('imageUrl'), 'image_url 을 받아야 한다');
  assert.ok(art.includes('toImageSource'), '앱에서는 스킴 변환을 거쳐야 한다');
  assert.ok(art.includes('<img'), '실제 이미지 요소로 그린다');
  assert.ok(art.includes('className="report-cupid"'));

  // 서버가 주소를 못 준 때만 마스코트로 내려간다.
  assert.ok(art.includes('if (!source)'));
  assert.ok(art.includes('<MupidMascot'));

  const view = collection.slice(collection.indexOf('export function ReportView'));
  assert.ok(
    view.includes('imageUrl={mubti.image_url}'),
    '리포트 카드가 응답의 image_url 을 넘겨야 한다',
  );
});

void test('the character image keeps a square ratio and is not hidden behind the card', () => {
  // 서버 일러스트는 정사각(1254×1254)인데, 대체 마스코트용 가로 비율(1391/1123)을
  // 그대로 두면 세로로 눌린다. 이미지에만 정사각을 준다.
  assert.match(responsive, /img\.report-cupid\s*\{[^}]*aspect-ratio: 1;/);

  // 배경을 쓰는 마스코트와 달리 <img> 는 크기를 명시해야 자리를 잡는다.
  assert.match(css, /img\.report-cupid\s*\{[^}]*width: 132px;[^}]*height: 132px;/);
  assert.match(css, /img\.report-cupid\s*\{[^}]*object-fit: contain;/);
});

void test('the report keeps polling so a late-arriving type still paints', () => {
  // 폴링이 없으면 5분 스냅샷 전에 들어온 사용자는 이미지를 영영 못 본다.
  assert.match(report, /POLL_MS/);
  assert.ok(collection.includes('useDailyReport(true)'));
});
