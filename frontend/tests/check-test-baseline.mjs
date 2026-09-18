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
    // 두 형식을 모두 받는다. **기호는 Node 버전에 따라 다르다** — 로컬(Node 24)
    // 은 `✖ 이름 (12.3ms)` 를 찍는데, CI 이미지의 Node 22 는 `not ok 12 - 이름`
    // (TAP) 을 찍는다. 한쪽만 보면 다른 쪽에서 **실패를 0개로 읽어 초록으로
    // 통과한다** — 실제로 CI 가 `지금 실패: 0개` 를 찍었다.
    let name = null;
    const spec = /^\s*✖\s+(.+?)(?:\s+\(\d+(?:\.\d+)?ms\))?\s*$/.exec(line);
    if (spec) {
      name = spec[1].trim();
    } else {
      // `not ok 3 - 이름` / `not ok 3 - 이름 # TODO`
      const tap = /^\s*not ok\s+\d+\s+-\s+(.+?)\s*$/.exec(line);
      if (tap) {
        name = tap[1].replace(/\s*#\s*(TODO|SKIP).*$/i, '').trim();
      }
    }
    if (name === null) continue;
    // 시험 이름이 아닌 것들
    if (name === 'failing tests:') continue;
    if (name === 'tests') continue;
    // 파일 단위 실패(`tests\x.test.mjs`) — 파일이 로드조차 안 되면 그건
    // **새 고장**이므로 따로 알린다.
    if (/^tests[\\/].+\.test\.mjs$/.test(name)) {
      names.add(`[파일 로드 실패] ${name}`);
      continue;
    }
    names.add(name);
  }
  return names;
}

/** 요약 줄에서 (tests, pass, fail) 을 읽는다. */
export function readSummary(output) {
  const pick = (label) => {
    // `ℹ tests 248` (Node 22/24 spec) 와 `# tests 248` (TAP) 를 모두 받는다.
    const match = new RegExp(`^(?:ℹ|#)\\s+${label}\\s+(\\d+)\\s*$`, 'm').exec(output);
    return match ? Number(match[1]) : null;
  };
  return { tests: pick('tests'), pass: pick('pass'), fail: pick('fail') };
}

function runTests() {
  // 셸 글롭에 기대지 않고 파일을 직접 모은다 — CI 러너의 셸이 무엇이든 같게 돈다.
  const files = readdirSync(here)
    .filter((name) => name.endsWith('.test.mjs'))
    .map((name) => path.join('tests', name));
  // **`maxBuffer` 를 크게 준다.** 기본값 1MB 인데, 이 시험들의 실패 출력에는
  // 단언 diff 가 통째로 실려서 그걸 넘긴다(CSS 파일 하나가 45KB 로 찍힌다).
  // 넘기면 `spawnSync` 가 자식을 끊고 **빈 출력**을 준다 — 그러면 실패를
  // 0개로 읽어 **초록으로 통과한다.** 실제로 그렇게 됐다(CI 에서 `지금 실패:
  // 0개` 가 찍혔는데 로컬에서는 34개였다).
  const result = spawnSync(
    process.execPath,
    ['--experimental-strip-types', '--test', ...files],
    { cwd: path.dirname(here), encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 },
  );
  if (result.error) {
    // 조용히 빈 문자열을 돌려주지 않는다. 읽지 못한 것과 실패가 없는 것은
    // 완전히 다른데, 빈 출력은 둘을 구분해 주지 않는다.
    throw new Error(`시험을 실행하지 못했다: ${result.error.message}`);
  }
  return { output: `${result.stdout ?? ''}${result.stderr ?? ''}`, status: result.status };
}

// 이 파일이 직접 실행될 때만 돈다 (import 해서 `failingNames` 만 쓰는 경우 제외).
if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  const { output, status } = runTests();
  const summary = readSummary(output);
  const failing = failingNames(output);

  // 요약 줄은 그대로 보여준다 — 실패 수가 어떻게 변했는지가 리뷰에 필요하다.
  if (summary.tests !== null) {
    console.log(`ℹ tests ${summary.tests}`);
    console.log(`ℹ pass ${summary.pass}`);
    console.log(`ℹ fail ${summary.fail}`);
  }

  // **시험이 실제로 돌았는지 먼저 확인한다.** 이 검사가 없으면 러너가 죽어
  // 아무것도 안 돌아도 "실패 0개" 로 읽혀 **초록으로 통과한다.**
  //
  // 판별 기준은 "요약이 있는가" 다. 테스트 러너는 항상 요약을 찍으므로,
  // 그게 없으면 시험이 돈 게 아니다.
  if (summary.tests === null || summary.fail === null) {
    console.error('\n시험이 실제로 돌지 않았다 — 요약 줄(`tests N` / `fail N`)이 없다.');
    console.error('  통과로 넘기면 아무것도 검증하지 않은 초록불이 된다.');
    console.error(`  자식 종료 코드: ${status} / 출력 길이: ${output.length}자`);
    console.error('  --- 출력 앞부분 ---');
    console.error(output.split('\n').slice(0, 25).join('\n'));
    process.exit(1);
  }

  // **가장 중요한 불변식 — 요약의 실패 수와 실제로 뽑아낸 이름 수가 같아야 한다.**
  //
  // 이게 없으면 파서가 출력 형식을 놓쳤을 때(예: CI 의 Node 22 가 TAP 으로
  // 찍는 것을 Node 24 의 `✖` 만 보고 있었을 때) 실패를 0개로 읽고 **초록으로
  // 통과한다.** 실제로 그렇게 됐다 — CI 로그가 `지금 실패: 0개` 를 찍었는데
  // 같은 커밋이 로컬에서는 34개였다. 원인을 두 번 잘못 짚은 끝에(줄바꿈 →
  // maxBuffer) 이 불변식을 넣어서야 형식과 무관하게 막히게 됐다.
  //
  // 숫자끼리 맞대므로 **형식이 무엇이든** 어긋나면 걸린다. 조용히 넘어갈
  // 방법이 없다.
  if (failing.size !== summary.fail) {
    console.error(
      `\n실패 수가 맞지 않는다: 요약은 ${summary.fail}개인데 이름은 ${failing.size}개만 뽑았다.`,
    );
    console.error('  출력 형식이 바뀌었거나 파서가 못 따라간 것이다.');
    console.error('  통과로 넘기면 그 실패들은 영영 안 보인다. 파서를 고친다.');
    console.error('  --- 요약 줄 ---');
    for (const line of output.split('\n')) {
      if (/^(?:ℹ|#)\s+(tests|pass|fail|suites)\b/.test(line)) console.error('  ' + line);
    }
    process.exit(1);
  }
  // 러너가 0 이 아닌 코드로 끝났는데 실패가 없다고 나오면 앞뒤가 안 맞는다.
  if (status !== 0 && summary.fail === 0) {
    console.error(`\n러너는 실패(${status})로 끝났는데 요약은 실패 0개다.`);
    console.error('  통과로 넘기면 검사가 무력해진다.');
    process.exit(1);
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
