/**
 * 앱 자동 업데이트용 **코드 전용** 웹 아카이브를 만든다.
 *
 * ## 왜 전체 번들을 보내지 않는가
 *
 * `dist/client/` 는 39,227,481 바이트인데 그 구성이 이렇다 (실측).
 *
 *   이미지   32,256,449 (82.2%)   이름이 고정이다 — mupid-cupid-logo.png
 *   폰트      5,498,892 (14.0%)   이름이 고정이다 — PretendardVariable.woff2
 *   코드      1,433,355 ( 3.6%)   js + html + rsc + css
 *
 * 이미지와 폰트는 **이미 APK 안에 들어 있다.** 앱은 `LayeredAssets` 로
 * 디스크 → 임베드를 차례로 보므로, 디스크 번들에 그 파일이 없으면 그냥
 * 임베드에서 채워진다 (`app/src/assets.rs`). 그래서 코드만 보내면 되고,
 * 크기가 36,806,710 → 약 318,000 바이트로 줄어든다 (약 116분의 1).
 *
 * ## 이름이 고정이라는 사실이 두 가지를 정한다
 *
 * 내용 해시가 붙은 파일이 하나도 없다. 그래서
 *
 *  1. 이미지·폰트를 **빼도 안전하다.** 새 빌드가 같은 이름으로 같은 자리를
 *     차지하므로, 빼도 임베드 사본이 그대로 맞는다. 해시 이름이었다면 새
 *     빌드의 새 이름이 임베드에 없어 404 가 났을 것이다.
 *  2. 대신 **캐시가 위험하다.** 같은 이름의 파일 내용이 바뀌면 WebView 는
 *     옛 사본을 계속 줄 수 있다. 이 위험은 코드 자산(`_next/static/...`)에도
 *     똑같이 있다 — 그래서 아카이브 파일 이름 자체를 빌드마다 바꾼다
 *     (`web-<builtAt>.tar.gz`). 서버·프록시·앱 어디에도 옛 사본이 남지 않는다.
 *
 * ## 재현 가능한 바이트
 *
 * 같은 입력이면 같은 아카이브가 나와야 한다. gzip 헤더의 mtime 과 tar 헤더의
 * mtime 을 0 으로 고정한다. 그래야 체크섬이 빌드 타임스탬프와 무관하게
 * 내용만 가리키고, "같은 내용을 다시 올렸는데 다르다고 나오는" 일이 없다.
 *
 * **단, `builtAt` 이 고정되어야 재현성이 성립한다.** CI 는 `MUPID_WEB_BUILD`
 * 를 넣고, 그러면 같은 소스 → 같은 `builtAt` → 같은 바이트가 나온다.
 * 로컬에서 그 변수 없이 돌리면 현재 시각을 쓰므로 매번 달라진다 — 그건
 * 의도된 동작이다(로컬 빌드는 "새 빌드" 로 취급되어야 한다).
 *
 * 사용:
 *   node scripts/web-archive.mjs            # dist/client → dist/update/
 *   MUPID_WEB_BUILD=1789000000000 node scripts/web-archive.mjs
 */

import { createHash } from 'node:crypto';
import { mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = join(root, 'dist', 'client');
const output = join(root, 'dist', 'update');

/**
 * 서버가 내려주는 경로. nginx 의 `/download/` 블록이 이 디렉터리를 서빙한다.
 *
 * **파일 이름에 빌드 타임스탬프를 넣는다.** 이름이 고정이면 중간 캐시가 옛
 * 아카이브를 계속 줄 수 있다. 내용 해시를 붙이지 않은 이유는 사람이 읽을 때
 * "언제 만든 것인지" 가 곧 필요한 정보이기 때문이다 — 이 설계에서 버전이
 * 곧 빌드 시각이다.
 */
function archiveName(builtAt) {
  return `web-${builtAt}.tar.gz`;
}

/** 매니페스트의 경로. 앱이 이 주소를 받아 읽는다. */
const MANIFEST_PATH = '/download/web.json';

/**
 * 아카이브에서 **빼는** 확장자. 전부 이름이 고정된 정적 자산이고, 임베드
 * 사본이 같은 자리를 채운다.
 *
 * `.svg` 를 빼는 이유는 크기가 아니라 **성격**이다. 아이콘·로고라 코드와 함께
 * 바뀌지 않는다. (크기는 38,785 바이트로 무시할 수준이다.)
 */
const STATIC_EXTENSIONS = new Set([
  '.png', '.webp', '.jpg', '.jpeg', '.gif', '.avif', // 이미지
  '.woff2', '.woff', '.ttf', '.otf', '.eot',        // 폰트
  '.svg', '.ico',                                    // 아이콘·로고
]);

/**
 * 아카이브에 **넣는** 확장자.
 *
 * 목록을 열거하는 쪽을 고른 이유는, 새 정적 확장자가 생겼을 때 조용히
 * 아카이브에 섞여 들어가는 것보다 **빠뜨리는 편이 낫기** 때문이다. 빠뜨리면
 * 임베드 폴백으로 화면은 뜨고, 크기만 조금 늘어난다. 반대로 이미지가 섞여
 * 들어가면 페이로드가 100배가 되는데 눈치채기 어렵다.
 */
const CODE_EXTENSIONS = new Set([
  '.js', '.mjs', '.cjs', '.jsx',   // 스크립트
  '.html', '.htm',                  // 화면
  '.css',                           // 스타일
  '.json', '.webmanifest',          // 매니페스트
  '.rsc',                           // RSC 페이로드 (정적 대상에서 화면 데이터)
  '.txt', '.xml',                   // robots.txt 등
]);

/** tar 한 항목의 헤더는 512 바이트다. */
const BLOCK = 512;

/**
 * 번들 안에 넣는 자기 서술 파일의 경로.
 *
 * **번들이 스스로 자기 나이를 안다**는 것이 이 설계의 요점이다. 서버 값만
 * 보고 비교하면 새 APK 를 설치한 직후에도 "서버 웹 빌드 != 내 빌드" 라서
 * 방금 설치한 APK 가 자기 번들을 다시 내려받는다 (`app/src/update.rs` 의
 * `read_bundle_build`).
 *
 * `app/` 아래에 두는 이유: 화면 라우트와 겹치지 않는다. `dist/client` 에
 * `app/` 디렉터리가 없다는 것을 확인했다 — 화면은 모두 최상위 `*.html` 이다.
 */
const BUILD_INFO_PATH = 'app/build.json';

/**
 * 코드 자산만 골라낸다. 심볼릭 링크는 **따라가지 않고 건너뛴다** —
 * `receive-front.py` 가 정한 규칙과 같다.
 */
function collect() {
  const picked = [];
  const skipped = [];
  let staticBytes = 0;

  const walk = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name);
      // `withFileTypes` 의 isSymbolicLink 로 먼저 거른다. statSync 는 링크를
      // 따라가므로 여기서 쓰면 안 된다.
      if (entry.isSymbolicLink()) {
        skipped.push(relative(source, path));
        continue;
      }
      if (entry.isDirectory()) {
        walk(path);
        continue;
      }
      if (!entry.isFile()) continue;

      const name = relative(source, path).split(sep).join('/');
      const dot = entry.name.lastIndexOf('.');
      const extension = dot === -1 ? '' : entry.name.slice(dot).toLowerCase();
      const size = statSync(path).size;

      if (CODE_EXTENSIONS.has(extension)) {
        picked.push({ name, path, size });
      } else {
        if (STATIC_EXTENSIONS.has(extension)) staticBytes += size;
        skipped.push(name);
      }
    }
  };

  walk(source);
  picked.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  return { picked, skipped, staticBytes };
}

/** tar 는 8진수 ASCII 로 수를 쓴다. */
function octal(value, length) {
  return value.toString(8).padStart(length - 1, '0') + '\0';
}

/**
 * 항목 하나의 헤더 512 바이트를 만든다.
 *
 * ustar 형식을 쓴다. pax 나 GNU 확장은 필요 없다 — 경로가 100자를 넘는
 * 파일이 없고, 넘으면 아래에서 명시적으로 실패시킨다(조용히 깨진 아카이브를
 * 만드는 것보다 낫다).
 */
function header(name, size) {
  if (Buffer.byteLength(name) > 100) {
    throw new Error(`경로가 100자를 넘습니다: ${name}`);
  }
  const block = Buffer.alloc(BLOCK);
  block.write(name, 0, 100, 'utf8');
  block.write(octal(0o644, 8), 100, 8, 'ascii');  // mode
  block.write(octal(0, 8), 108, 8, 'ascii');      // uid
  block.write(octal(0, 8), 116, 8, 'ascii');      // gid
  block.write(octal(size, 12), 124, 12, 'ascii'); // size
  block.write(octal(0, 12), 136, 12, 'ascii');    // mtime — 0 으로 고정 (재현성)
  block.write('        ', 148, 8, 'ascii');       // 체크섬 자리는 공백으로 계산
  block.write('0', 156, 1, 'ascii');              // typeflag: 보통 파일
  block.write('ustar\0', 257, 6, 'ascii');
  block.write('00', 263, 2, 'ascii');

  let checksum = 0;
  for (const byte of block) checksum += byte;
  block.write(checksum.toString(8).padStart(6, '0') + '\0 ', 148, 8, 'ascii');
  return block;
}

/** 항목 목록을 tar 바이트로 만든다. */
function tarball(entries) {
  const parts = [];
  for (const entry of entries) {
    const data = entry.data ?? readFileSync(entry.path);
    parts.push(header(entry.name, data.length));
    parts.push(data);
    // 내용은 512 경계까지 0 으로 채운다.
    const padding = (BLOCK - (data.length % BLOCK)) % BLOCK;
    if (padding > 0) parts.push(Buffer.alloc(padding));
  }
  // 아카이브 끝은 0 블록 두 개.
  parts.push(Buffer.alloc(BLOCK * 2));
  return Buffer.concat(parts);
}

/**
 * 빌드 타임스탬프 (epoch **ms**, UTC). 이것이 곧 버전이다.
 *
 * 순서대로 본다.
 *
 *  1. `MUPID_WEB_BUILD` — CI 가 정한다. APK 빌드도 **같은 값**을
 *     `option_env!("MUPID_WEB_BUILD")` 로 박아 임베드 번들의 나이로 쓴다.
 *     프론트와 APK 가 다른 값을 들고 있으면 앱이 자기 번들을 계속 다시 받는다.
 *  2. `SOURCE_DATE_EPOCH` — 재현 가능 빌드의 표준 변수(초 단위).
 *  3. 현재 시각. 로컬 개발용이다 — 이때는 매번 다른 값이 나온다.
 */
function buildTimestamp() {
  const explicit = Number(process.env.MUPID_WEB_BUILD ?? 0);
  if (Number.isFinite(explicit) && explicit > 0) {
    // 밀리초로 정규화한다. 초로 들어온 값을 그대로 쓰면 앱의 비교식이
    // (초 vs 밀리초) 항상 한쪽으로 치우친다.
    return explicit < 1e12 ? Math.trunc(explicit * 1000) : Math.trunc(explicit);
  }
  const reproducible = Number(process.env.SOURCE_DATE_EPOCH ?? 0);
  if (Number.isFinite(reproducible) && reproducible > 0) {
    return Math.trunc(reproducible * 1000);
  }
  return Date.now();
}

const builtAt = buildTimestamp();

const { picked, skipped, staticBytes } = collect();
if (picked.length === 0) {
  console.error(`\n[web-archive] 코드 자산을 찾지 못했습니다: ${source}`);
  console.error('  먼저 `npm run build:static` 을 돌리세요.\n');
  process.exit(1);
}

// 화면(HTML)이 없으면 앱이 뜨지 않는다. 그 경우는 아카이브를 만들지 않는다.
const htmlCount = picked.filter((entry) => entry.name.endsWith('.html')).length;
if (htmlCount === 0) {
  console.error(`\n[web-archive] HTML 이 없습니다 — 앱이 뜨지 않는 번들입니다.\n`);
  process.exit(1);
}

/**
 * 번들이 스스로 밝히는 자기 나이. 아카이브 **안에** 들어간다.
 *
 * 필드 이름 `builtAt` 은 `app/src/update.rs` 의 `read_bundle_build` 가 읽는
 * 이름과 **반드시 같아야 한다.** 다르면 `None` 이 되어 그 번들은 활성화
 * 단계에서 거부된다 — 증상은 "업데이트가 안 된다" 뿐이다.
 */
const buildInfo = { builtAt, entry: 'index.html', schema: 1 };
const buildInfoBytes = Buffer.from(JSON.stringify(buildInfo, null, 2) + '\n', 'utf8');

// 자기 서술 파일을 목록에 넣는다. **크기 계산과 파일 수에 포함되어야 한다** —
// 앱이 매니페스트의 `files`·`expandedBytes` 와 전개 결과를 대조하므로,
// 여기서 빠뜨리면 모든 활성화가 "개수 불일치" 로 거부된다.
const entries = [
  ...picked,
  { name: BUILD_INFO_PATH, data: buildInfoBytes, size: buildInfoBytes.length },
].sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));

const tar = tarball(entries);
// mtime 을 0 으로 고정한다. 없으면 gzip 헤더에 현재 시각이 들어가 매번 달라진다.
const archive = gzipSync(tar, { level: 9, mtime: 0 });

const digest = createHash('sha256').update(archive).digest('hex');
const codeBytes = entries.reduce((sum, entry) => sum + entry.size, 0);
const name = archiveName(builtAt);

mkdirSync(output, { recursive: true });
writeFileSync(join(output, name), archive);

/**
 * 매니페스트. 앱이 "새 것이 있나" 를 판단하는 근거다.
 *
 * **필드 이름은 `app/src/update.rs` 의 `WebManifest` 와 1:1 로 맞춘다**
 * (`rename_all = "camelCase"`). 어긋나면 앱이 파싱에 실패하고, 그 실패는
 * 조용하다 — 화면에는 아무 일도 일어나지 않는다.
 *
 * `files`·`expandedBytes` 를 넣는 이유는 **부분 수신과 압축 폭탄을 막기**
 * 위해서다. 아카이브를 다 받은 뒤 체크섬을 보는 것만으로는 부족하고,
 * 펼친 뒤 기대한 파일이 다 있는지 확인해야 반쪽짜리 번들이 활성화되지 않는다.
 */
const manifest = {
  schema: 1,
  builtAt,
  bundle: `/download/${name}`,
  sha256: digest,
  bytes: archive.length,
  // 아카이브에 든 파일 수. `app/build.json` 을 포함한다.
  files: entries.length,
  // **tar 바이트 길이가 아니라 파일 크기의 합이다.**
  //
  // 여기에 `tar.length` 를 쓰면 안 된다. tar 는 항목마다 512 경계까지 0 을
  // 채우고 끝에 0 블록 두 개를 붙이므로, 그 값은 실제 내용보다 항상 크다
  // (실측: 1,478,144 vs 1,433,426 — 44,718 바이트 차이). 앱은 펼치면서
  // **파일 크기만** 더하므로(`update.rs` 의 `expand`) 두 값이 어긋나고,
  // 그러면 모든 활성화가 "펼친 크기가 다릅니다" 로 거부된다.
  expandedBytes: codeBytes,
  entry: 'index.html',
};

writeFileSync(join(output, 'web.json'), JSON.stringify(manifest, null, 2) + '\n');

// ── 앱이 하는 검사를 여기서 미리 한다 ──────────────────────────
// 앱은 이 값들을 상한으로 쓴다(`update.rs`). 넘으면 받지도 않고 거부하므로,
// 배포 전에 여기서 잡는 편이 훨씬 싸다.
const limits = [
  ['아카이브', archive.length, 4 * 1024 * 1024],
  ['펼친 총량', manifest.expandedBytes, 24 * 1024 * 1024],
  ['항목 수', manifest.files, 2048],
  ['가장 큰 항목', Math.max(...entries.map((entry) => entry.size)), 8 * 1024 * 1024],
];
for (const [label, value, limit] of limits) {
  if (value > limit) {
    console.error(`\n[web-archive] ${label}이 앱의 상한을 넘습니다: ${value} > ${limit}`);
    console.error('  앱이 이 번들을 거부합니다. 상한은 app/src/update.rs 에 있습니다.\n');
    process.exit(1);
  }
}

const kb = (value) => `${(value / 1024).toFixed(1)}KB`;
console.log(`[web-archive] ${name}`);
console.log(`  builtAt(버전)  ${builtAt}  (${new Date(builtAt).toISOString()})`);
console.log(`  코드 자산      ${entries.length}개 (build.json 포함) / ${codeBytes.toLocaleString()} bytes`);
console.log(`  HTML           ${htmlCount}개`);
console.log(`  아카이브       ${archive.length.toLocaleString()} bytes (${kb(archive.length)})  gzip -9`);
console.log(`  제외           ${skipped.length}개 / ${staticBytes.toLocaleString()} bytes (정적 — 임베드가 채운다)`);
console.log(`  sha256         ${digest}`);
console.log(`  manifest       ${join(output, 'web.json')}  → 앱은 ${MANIFEST_PATH} 로 받는다`);
if (!process.env.MUPID_WEB_BUILD && !process.env.SOURCE_DATE_EPOCH) {
  console.log('  주의           재현성 없음 — 현재 시각을 썼다. CI 는 MUPID_WEB_BUILD 를 넣는다.');
}
