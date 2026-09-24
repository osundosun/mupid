import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

/*
 * 진입 경로(`entry_path`)는 **화면이 아니라 누른 자리**가 정한다.
 *
 * 예전에는 `entryPathOf()` 가 지금 보고 있는 화면만 보고 정했다. 그래서 재생 목록에서
 * 다시 꺼낸 곡도, '최근 들은 음악' 에서 다시 튼 곡도 홈에 있다는 이유만으로
 * `RECOMMEND` 로 나갔다 — 추천이 한 일과 사용자가 원래 알던 곡을 되꺼낸 일이 한 통에
 * 섞여 추천 성과가 부풀려졌다.
 *
 * 이 시험은 그 분리가 **양쪽 저장소에서 동시에** 살아 있는지 본다. 프론트만 새 값을
 * 보내면 API 의 `normalize()` 가 모르는 값을 400 이 아니라 **빈 문자열로 떨어뜨려서**,
 * 아무도 모르는 채 경로 없는 이벤트가 원본 테이블에 쌓인다.
 */
const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');
const readApi = (path) => readFile(new URL('../../api/' + path, import.meta.url), 'utf8');

const [types, hook, queueList, home, desktopHome, collection, javaEnum, recs, data, artist] =
  await Promise.all([
    read('lib/analytics/types.ts'),
    read('hooks/use-mupid.ts'),
    read('components/mupid/queue-list.tsx'),
    read('components/mupid/home.tsx'),
    read('components/mupid/desktop-home.tsx'),
    read('components/mupid/collection.tsx'),
    readApi('src/main/java/com/osundosun/mupid/api/event/enumerate/EntryPath.java'),
    read('lib/recommendations.ts'),
    read('lib/mupid-data.ts'),
    read('components/mupid/artist-info.tsx'),
  ]);

const FRONT_VALUES = ['RECOMMEND', 'SEARCH', 'PLAYLIST', 'QUEUE', 'RECENT', 'ARTIST'];

test('EntryPath 계약에 QUEUE · RECENT · ARTIST 가 있다', () => {
  const union = types.match(/export type EntryPath =([^;]+);/)?.[1];
  assert.ok(union, 'EntryPath 선언을 찾지 못했다');
  const declared = [...union.matchAll(/'([A-Z_]+)'/g)].map((m) => m[1]);
  assert.deepEqual(declared, FRONT_VALUES);
});

test('API 의 EntryPath enum 이 프론트와 같은 값 집합을 받는다', () => {
  // 모르는 값은 거절되지 않고 조용히 빈 문자열이 된다. 한쪽만 늘리면 그 사이 쌓인
  // 이벤트는 경로 없이 원본에 박히고 되돌릴 수 없다.
  const body = javaEnum.slice(javaEnum.indexOf('public enum EntryPath'));
  for (const value of FRONT_VALUES) {
    assert.ok(
      new RegExp(`^\\s*${value},?\\s*$`, 'm').test(body),
      `API EntryPath 에 ${value} 가 없다 — 이 값으로 온 이벤트는 entry_path 가 빈 채로 저장된다`,
    );
  }
});

test('entryPathOf 는 부른 쪽이 준 출처를 화면보다 먼저 본다', () => {
  const fn = hook.match(/const entryPathOf = \(([\s\S]*?)\n  \};/)?.[0];
  assert.ok(fn, 'entryPathOf 를 찾지 못했다');
  assert.ok(fn.includes('from?: EntryPath'), '출처를 받지 않는다');
  // 화면 판정보다 먼저 반환해야 한다. 뒤에 두면 검색·보관함 화면에서 덮인다.
  assert.ok(
    fn.indexOf('if (from) return from;') < fn.indexOf("page === 'search'"),
    '화면 판정이 출처보다 앞선다',
  );
});

test('재생 목록에서 튼 곡은 화면과 무관하게 QUEUE 다', () => {
  assert.ok(queueList.includes("m.play(track, false, track.uid, 'QUEUE')"));
});

test('큐가 자동으로 넘긴 재생도 QUEUE 다 — 한 목록을 듣는 동안 경로가 갈리지 않는다', () => {
  // 다음·이전 버튼
  assert.ok(hook.includes("startPlayback(target, 0, target.uid, 'QUEUE')"));
  // 한곡 반복
  assert.ok(hook.includes("startPlayback(current, 0, undefined, 'QUEUE')"));
  // 곡이 끝나 다음 자리로 넘어가는 길까지 세 자리다.
  assert.equal(hook.match(/'QUEUE'/g).length, 3);
});

test('목록이 끝나 추천이 이어 튼 곡은 RECOMMEND 다 — 화면으로 갈음하지 않는다', () => {
  /*
   * 곡을 고른 것은 추천이므로 `RECOMMEND` 인데, **직접 넘겨야** 한다. `entryPathOf` 에
   * 맡기면 화면으로 갈음해 버려서, 목록이 끝난 그 순간 사용자가 검색 화면에 있었으면
   * `SEARCH`, 보관함에 있었으면 `PLAYLIST` 가 된다 — 같은 자동 재생인데 어느 화면을
   * 보고 있었는지에 따라 값이 갈린다.
   *
   * `AUTOPLAY` 는 두지 않는다. 이유와 그 대가는 `decision/0014` 에 있다.
   */
  assert.ok(
    hook.includes("startPlayback(track, track.recommended?.start, placement.uid, 'RECOMMEND')"),
  );
  // 값 집합 자체는 맨 위 시험이 못박는다. 여기서는 훅만 본다.
  assert.ok(!/'AUTOPLAY'/.test(hook), '쓰지 않기로 한 AUTOPLAY 가 들어왔다');
});

test('아티스트 패널에서 찾아 튼 곡은 ARTIST 다 — 코드 순서에 매달리지 않는다', () => {
  // 패널은 모든 곡 줄의 아티스트명에서 열린다. 화면으로 갈음하면 같은 행동이
  // RECOMMEND·SEARCH·PLAYLIST 로 흩어진다.
  assert.ok(artist.includes("m.play(track, false, undefined, 'ARTIST')"));
  // go('player') 가 play() 뒤에 있어 예전에는 두 줄의 순서가 값을 갈랐다.
  // 출처를 직접 넘기므로 이제 순서와 무관하지만, 그 순서 자체는 그대로여야 한다.
  assert.ok(
    artist.indexOf("m.play(track, false, undefined, 'ARTIST')") < artist.indexOf("m.go('player')"),
  );
});

test('오늘의 Moment 는 서버가 준 상관 키를 버리지 않는다', () => {
  // 구간을 안 붙이기로 하면서 recommendation_id 까지 함께 떨어져 나갔다. 홈 전면에
  // 걸리는 추천인데 성과를 잴 수 없었다.
  assert.match(recs, /id: group\.recommendation_id, type: 'HOME' as const, rank: row\.rank/);
  assert.ok(data.includes('recSource?: { id: number; type: RecType; rank: number }'));
  // startPlayback 이 구간 추천 다음 자리에서 이것을 읽는다.
  assert.ok(hook.includes('track.recSource'));
});

test('배치 미실행 폴백의 recommendation_id 0 은 싣지 않는다', () => {
  // 저장되지 않은 묶음이라 가리킬 대상이 없다. 그대로 실으면 없는 추천 번호로 재생이
  // 기록돼, 사유를 되짚지도 못하면서 추천 성과에는 잡힌다.
  assert.match(recs, /group\.recommendation_id > 0/);
});

test('오늘의 Moment 에 recommended 를 붙이지 않는다 — 없는 구간을 그리게 된다', () => {
  // 상관 키를 `recommended` 에 담으면 플레이어의 추천 구간 표시와 구간 이름이 빈 값으로
  // 뜨고, play(track, true) 가 undefined 지점으로 재생을 건다.
  const daily = recs.slice(
    recs.indexOf('export async function fetchTodayMoments'),
    recs.indexOf('export async function fetchNextTracks'),
  );
  assert.ok(daily.length > 0, 'fetchTodayMoments 를 찾지 못했다');
  assert.ok(!/\brecommended:/.test(daily), 'fetchTodayMoments 가 recommended 를 붙인다');
});

test("'최근 들은 음악' 은 홈·프로필 어디서 눌러도 RECENT 다", () => {
  const recent = "m.play(t, false, undefined, 'RECENT')";
  assert.ok(home.includes(recent), '모바일 홈이 RECENT 를 넘기지 않는다');
  assert.ok(
    desktopHome.includes("m.play(track, false, undefined, 'RECENT')"),
    '데스크톱 홈이 RECENT 를 넘기지 않는다',
  );
  assert.ok(
    collection.includes("m.play(track, false, undefined, 'RECENT')"),
    '프로필 감상 기록이 RECENT 를 넘기지 않는다',
  );
});
