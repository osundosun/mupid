import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

/*
 * 카탈로그 밖의 곡도 재생 목록에 담긴다 (S15P21A204-441).
 *
 * 앱이 처음 받아 오는 곡은 앞 100곡뿐인데(`lib/stream-catalog.ts`), 큐·좋아요·
 * 기록은 모두 **곡 번호**만 들고 있다. 번호를 곡으로 되돌리는 곳은 모델의 곡
 * 목록 하나여서, 검색으로 찾은 곡(카탈로그 밖)은 담아도 그 줄이 그려지지 않았다 —
 * 담기·재생 버튼을 눌러도 재생 목록에 아무 일도 없는 것처럼 보였다.
 *
 * 이 파일이 지키는 것은 "만난 곡은 기억한다" 하나다. 소스를 읽어 확인하는 이유는
 * 모델(`hooks/use-mupid.ts`)이 브라우저 없이는 실행되지 않기 때문이다.
 */

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');

test('모델은 카탈로그 밖에서 만난 곡을 곡 목록에 흡수한다', async () => {
  const model = await read('hooks/use-mupid.ts');
  // 카탈로그(bootstrap)와 새로 만난 곡을 합친 것이 화면이 보는 곡 목록이다.
  assert.match(model, /const \{ tracks: catalog, preferences: initial \} = bootstrap;/);
  assert.match(model, /const \[discovered, setDiscovered\] = useState<Track\[\]>\(\[\]\);/);
  assert.match(model, /const tracks = useMemo\(\s*\(\) => \(discovered\.length \? \[\.\.\.catalog, \.\.\.discovered\] : catalog\),/);
  // 더할 것이 없으면 같은 배열을 돌려준다 — 새 배열이면 `tracks` 를 보는 효과들이 헛돈다.
  assert.match(model, /return added\.length \? \[\.\.\.current, \.\.\.added\] : current;/);
  // 화면도 쓸 수 있어야 한다.
  assert.match(model, /^\s*rememberTracks,$/m);
});

test('담기와 목록 밖 재생은 그 곡을 먼저 기억한다', async () => {
  const model = await read('hooks/use-mupid.ts');
  assert.match(model, /addToQueue: \(track: Track\) => \{[\s\S]{0,200}?rememberTracks\(\[track\]\);\s*const result = placeInQueue\(/);
  assert.match(model, /const placePlayedTrack = \(track: Track\) => \{[\s\S]{0,300}?rememberTracks\(\[track\]\);\s*const placed = placeInQueue\(/);
});

test('계정 큐로 받아 온 곡도 기억한다 — 새로고침해도 담아 둔 곡이 남는다', async () => {
  const [model, queue] = await Promise.all([
    read('hooks/use-mupid.ts'),
    read('lib/play-queue.ts'),
  ]);
  // 서버 응답의 곡 정보를 그대로 화면의 곡으로 옮긴다. 카탈로그로 거르지 않는다.
  assert.match(queue, /const toSnapshot = \(state: \{ revision: number; tracks: MusicSummary\[\] \}\): QueueSnapshot => \{\s*const tracks = state\.tracks\.map\(toTrack\);/);
  assert.doesNotMatch(queue, /function toScreenIds/, '서버 큐를 카탈로그로 거르면 카탈로그 밖의 곡이 사라진다');
  assert.match(queue, /export async function fetchQueue\(signal\?: AbortSignal\)/);
  // 받아 온 곡을 기억한 **뒤에** 큐를 세운다.
  assert.match(model, /rememberTracks\(saved\.tracks\);\s*queueAdopted\.current = true;/);
  assert.match(model, /rememberTracks\(saved\.tracks\);\s*queueRevision\.current = saved\.revision;/);
});

test('검색 결과 목록은 그린 곡을 모델에 알려 두고, 곡 목록이 자라도 결과를 되감지 않는다', async () => {
  const explore = await read('components/mupid/explore.tsx');
  assert.match(explore, /useEffect\(\(\) => \{\s*rememberTracks\(listing\.tracks\);\s*\}, \[listing\.tracks, rememberTracks\]\);/);
  // 곡 목록은 이제 자란다. 검색 효과가 그것을 보고 있으면 한 곡을 담는 순간
  // 검색이 처음부터 다시 돌아 이어 붙인 결과가 8곡으로 되감긴다.
  assert.doesNotMatch(explore, /appliedGenre, m\.tracks\]/);
  assert.match(explore, /demoCatalog\.current\.filter/);
});
