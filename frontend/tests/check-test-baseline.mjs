// 프론트엔드 시험에서 **새로 깨진 것만** 막는다.
//
// ## 왜 기준선(baseline)인가
//
// `frontend/tests` 의 시험들은 오래 CI 에서 빠져 있었다(로컬에서만 돌았다).
// 그 사이 `origin/develop` 에 **39개 실패**가 쌓였다 — CSS 값·레이아웃 구조를
// 단언하는 시험들이 화면이 바뀌면서 어긋난 것이다.
//
// 그래서 이 시험들을 CI 에 그대로 걸면 **모든 MR 이 빨간불**이 된다. 그러면
// 게이트가 아니라 장애물이 되고, 결국 job 을 끄게 된다 — 아무것도 안 보는
// 것과 같아진다.
//
// 대신 `failures-baseline.txt` 에 **이미 깨져 있는 시험 이름**을 적어 두고,
// 그 목록에 없는 시험이 실패하면 여기서 멈춘다. 기존 39개를 정리하는 별도
// 작업이 끝나면 기준선을 비우면 되고, 그러면 그때부터 전부 막힌다.
//
// ## 기준선을 갱신하는 법
//
//   node tests/check-test-baseline.mjs --write-baseline
//
// **기준선을 늘리는 것은 곧 "이 실패를 봐준다" 는 선언이다.** 새 실패가 났을 때
// 이 명령으로 넘기지 않는다 — 고치거나, 고칠 수 없으면 그 이유를 커밋 메시지에
// 남기고 늘린다.
import { readFileSync, readdirSync, writeFileSync, existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const baselinePath = path.join(here, 'failures-baseline.txt');

/** 테스트 러너 출력에서 **실패한 시험 이름**을 뽑는다. */
export function failingNames(output) {
  const names = new Set();
  for (const line of output.split('\n')) {
    // `✖ 이름 (12.3ms)` — 요약 블록도 같은 줄을 다시 찍으므로 Set 으로 합친다.
    const match = /^\s*✖\s+(.+?)(?:\s+\(\d+(?:\.\d+)?ms\))?\s*$/.exec(line);
    if (!match) continue;
    const name = match[1].trim();
    // 파일 단위 실패(`tests\x.test.mjs`)와 요약 헤더는 시험 이름이 아니다.
    // 파일이 로드조차 안 되면 그건 **새 고장**이므로 따로 알린다.
    if (name === 'failing tests:') continue;
    if (/^tests[\\/].+\.test\.mjs$/.test(name)) {
      names.add(`[파일 로드 실패] ${name}`);
      continue;
    }
    names.add(name);
  }
  return names;
}

function runTests() {
  // 셸 글롭에 기대지 않고 파일을 직접 모은다 — CI 러너의 셸이 무엇이든 같게 돈다.
  const files = readdirSync(here)
    .filter((name) => name.endsWith('.test.mjs'))
    .map((name) => path.join('tests', name));
  const result = spawnSync(
    process.execPath,
    ['--experimental-strip-types', '--test', ...files],
    { cwd: path.dirname(here), encoding: 'utf8' },
  );
  return `${result.stdout ?? ''}${result.stderr ?? ''}`;
}

// 이 파일이 직접 실행될 때만 돈다 (import 해서 `failingNames` 만 쓰는 경우 제외).
if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  const output = runTests();
  const failing = failingNames(output);

  // 요약 줄은 그대로 보여준다 — 실패 수가 어떻게 변했는지가 리뷰에 필요하다.
  for (const line of output.split('\n')) {
    if (/^ℹ (tests|pass|fail)\b/.test(line)) console.log(line);
  }

  if (process.argv.includes('--write-baseline')) {
    const sorted = [...failing].sort();
    writeFileSync(baselinePath, `${sorted.join('\n')}\n`, 'utf8');
    console.log(`\n기준선을 ${sorted.length}개로 새로 썼다: ${path.relative(process.cwd(), baselinePath)}`);
    process.exit(0);
  }

  if (!existsSync(baselinePath)) {
    console.error(`\n기준선 파일이 없다: ${baselinePath}`);
    console.error('  node tests/check-test-baseline.mjs --write-baseline  로 만든다');
    process.exit(1);
  }

  const baseline = new Set(
    readFileSync(baselinePath, 'utf8')
      .split('\n')
      .map((line) => line.trim())
      .filter(Boolean),
  );

  const 새실패 = [...failing].filter((name) => !baseline.has(name)).sort();
  const 아직깨짐 = [...failing].filter((name) => baseline.has(name)).length;

  console.log(`\n기준선(이미 깨져 있던 것): ${baseline.size}개`);
  console.log(`지금 실패: ${failing.size}개 — 그중 기준선에 있던 것 ${아직깨짐}개`);

  if (새실패.length > 0) {
    console.error('\n새로 깨진 시험이 있다:');
    for (const name of 새실패) console.error(`  ✖ ${name}`);
    console.error(
      '\n기존 실패가 아니라 **이번 변경이 깨뜨린 것**이다. 고치거나, 정말 ' +
        '봐줘야 하면 --write-baseline 으로 늘리고 커밋 메시지에 이유를 남긴다.',
    );
    process.exit(1);
  }

  console.log('새로 깨진 시험 없음.');
}
