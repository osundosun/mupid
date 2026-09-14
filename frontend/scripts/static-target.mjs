/**
 * 서버 없는 대상(`ec2`, `app`)으로 vinext 를 돌린다.
 *
 * `MUPID_DEPLOY_TARGET` 을 보고 `next.config.ts` 는 `output: 'export'` 로,
 * `vite.config.ts` 는 Cloudflare 플러그인 없이 간다. 산출물은 `dist/client/`
 * 한 벌이고, 그것이 곧 nginx 의 웹 루트이자 Tauri 앱 번들이다
 * (`app/tauri.conf.json` 의 `frontendDist`).
 *
 * 스크립트로 감싼 이유는 둘이다.
 *
 *  1. `MUPID_DEPLOY_TARGET=app vinext build` 는 Windows 의 cmd 에서 돌지 않는다.
 *     팀은 Windows 와 macOS 를 함께 쓴다.
 *  2. 앱 빌드에서는 RSC 호환성 식별자가 제대로 박혔는지 확인한다 (아래).
 *
 * 사용:
 *   node scripts/static-target.mjs build app
 *   node scripts/static-target.mjs dev app
 */

import { spawn } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const [command = 'build', target = 'app'] = process.argv.slice(2);
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 고정한 RSC 호환성 식별자를 `vite.config.ts` 에서 글자로 읽는다.
 *
 * 박는 일은 거기(`pinRscCompatibilityId`)서 하고 여기서는 결과만 검사한다.
 * 값을 복사해 두면 언젠가 어긋나는데, 그때 증상은 "화면을 옮기면 전체가 다시
 * 로딩된다" 뿐이라 원인을 찾기 어렵다. import 하지 않는 것은 그 파일이 Vite
 * 플러그인을 불러오기 때문이다.
 */
const pinnedId = readFileSync(join(root, 'vite.config.ts'), 'utf8').match(
  /RSC_COMPATIBILITY_ID = '([^']+)'/,
)?.[1];

/** 고정한 식별자가 정말 번들에 들어갔는지 본다. 조용히 어긋나면 증상이 고약하다. */
function verify() {
  const fail = (reason) => {
    console.error(`\n[static-target] ${reason}`);
    console.error('  그대로 두면 앱에서 화면을 옮길 때마다 전체가 다시 로딩됩니다.\n');
    process.exit(1);
  };
  if (!pinnedId) fail('vite.config.ts 에서 RSC_COMPATIBILITY_ID 를 찾지 못했습니다.');

  const dir = join(root, 'dist', 'client', '_next', 'static', 'chunks');
  const found = readdirSync(dir).some(
    (name) =>
      name.endsWith('.js') && readFileSync(join(dir, name), 'utf8').includes(pinnedId),
  );
  if (!found) {
    fail(
      `RSC 호환성 식별자(${pinnedId})가 번들에 없습니다.` +
        ' vite.config.ts 의 pinRscCompatibilityId 가 먹지 않은 것 같습니다.',
    );
  }
}

const child = spawn(
  process.execPath,
  [resolve(root, 'node_modules', 'vinext', 'dist', 'cli.js'), command],
  {
    cwd: root,
    stdio: 'inherit',
    env: { ...process.env, MUPID_DEPLOY_TARGET: target },
  },
);

// 신호로 죽었으면 종료 코드가 없다. 0 으로 끝내면 CI 가 성공으로 읽는다.
child.on('exit', (code) => {
  if (code === 0 && command === 'build' && target === 'app') verify();
  process.exit(code ?? 1);
});
