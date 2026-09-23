import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

/*
 * 카탈로그 밖의 곡도 재생 목록에 담긴다 (S15P21A204-441).
 *
 * 앱이 처음 받아 오는 곡은 앞 100곡뿐인데(`lib/stream-catalog.ts`), 큐는 **곡 번호**만
 * 들고 있다. 번호를 곡으로 되돌리지 못하면 그 줄은 그려지지 않는다 — 검색으로 찾은 곡의
 * 담기·재생 버튼을 눌러도 재생 목록에 아무 일도 없는 것처럼 보이던 원인이다.
 *
 * 보관함은 같은 문제를 `knownTracks`(카탈로그 + 밖에서 만난 곡)로 이미 풀었다
 * (S15P21A204-443). 큐도 거기서 곡을 찾고, 검색 결과·담기·재생이 그 목록에 곡을 보탠다.
 *
 * 소스를 읽어 확인하는 이유는 모델(`hooks/use-mupid.ts`)이 브라우저 없이는 실행되지
 * 않기 때문이다.
 */

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');

test('재생 목록은 카탈로그가 아니라 knownTracks 에서 곡을 찾는다', async () => {
  const model = await read('hooks/use-mupid.ts');
  assert.match(model, /const track = knownTracks\.find\(\(t\) => t\.id === trackId\);/);
  assert.doesNotMatch(model, /const track = tracks\.find\(\(t\) => t\.id === trackId\);/);
  // 계정에 올릴 서버 번호도 같은 목록에서 찾는다. 카탈로그만 보면 검색으로 담은 곡이
  // 저장에서 빠지고, 그 줄어든 목록이 계정을 덮는다.
  assert.match(model, /queueApi\.saveQueue\(ids, currentId, revision, knownTracks\)/);
  assert.match(model, /queueApi\.saveQueue\(pending\.ids, pending\.currentId, pending\.revision, knownTracks\)/);
});

test('담기와 목록 밖 재생은 그 곡을 먼저 기억한다', async () => {
  const model = await read('hooks/use-mupid.ts');
  assert.match(model, /addToQueue: \(track: Track\) => \{[\s\S]{0,200}?rememberTracks\(\[track\]\);\s*const result = placeInQueue\(/);
  // 재생은 `startPlayback` 도 기억하지만, 울리던 곡을 목록 밖에서 다시 눌러 토글로
  // 갈리는 길은 거기까지 가지 않는다. 자리를 만드는 곳에서 기억해야 빠짐이 없다.
  assert.match(model, /const placePlayedTrack = \(track: Track\) => \{[\s\S]{0,300}?rememberTracks\(\[track\]\);\s*const placed = placeInQueue\(/);
  // 효과 의존성에 넣으므로 참조가 고정돼야 한다.
  assert.match(model, /const rememberTracks = useCallback\(/);
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

test('검색 결과 목록은 그린 곡을 모델에 알려 둔다', async () => {
  const explore = await read('components/mupid/explore.tsx');
  assert.match(explore, /useEffect\(\(\) => \{\s*rememberTracks\(listing\.tracks\);\s*\}, \[listing\.tracks, rememberTracks\]\);/);
  assert.match(explore, /const \{ rememberTracks \} = m;/);
});
