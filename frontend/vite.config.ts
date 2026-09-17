import { sites } from '@openai/sites-vite-plugin';
import tailwindcss from '@tailwindcss/postcss';
import vinext from 'vinext';
import { defineConfig } from 'vite';
import hostingConfig from './.openai/hosting.json';

const SITE_CREATOR_PLACEHOLDER_DATABASE_ID =
  '00000000-0000-4000-8000-000000000000';

const { d1, r2 } = hostingConfig;

// macOS Seatbelt blocks FSEvents, so Codex previews need polling for HMR.
const isCodexSeatbeltSandbox = process.env.CODEX_SANDBOX === 'seatbelt';

const localBindingConfig = {
  main: 'vinext/server/fetch-handler',
  compatibility_flags: ['nodejs_compat'],
  d1_databases: d1
    ? [
        {
          binding: d1,
          database_name: 'site-creator-d1',
          database_id: SITE_CREATOR_PLACEHOLDER_DATABASE_ID,
        },
      ]
    : [],
  r2_buckets: r2
    ? [
        {
          binding: r2,
          bucket_name: 'site-creator-r2',
        },
      ]
    : [],
};

// 개발용 API 프록시.
//
// 서버에 CORS 설정이 없고 리프레시 토큰 쿠키가 SameSite=Strict + Secure 라,
// 브라우저에게 같은 출처로 보이게 하는 것이 가장 단순하다. 청크 바이너리도
// 이 경로로 흐르므로 버퍼링을 끄고 그대로 흘려보낸다.
const apiProxy = {
  '/api': {
    target: process.env.MUPID_API_ORIGIN ?? 'http://localhost:9900',
    changeOrigin: true,
  },
  // 청크 업로드 경로. 배치 전용이지만 프록시를 맞춰 두면 디버깅이 쉽다.
  '/upload': {
    target: process.env.MUPID_API_ORIGIN ?? 'http://localhost:9900',
    changeOrigin: true,
  },
};

// 서버 없이 정적 파일만 내보내는 대상. `next.config.ts` 와 같은 목록이다.
//   ec2 — nginx 가 서빙, app — Tauri 앱 번들.
const STATIC_TARGETS = ['ec2', 'app'];

/**
 * 앱 번들의 RSC 호환성 식별자를 고정하는 플러그인.
 *
 * 클라이언트 라우터는 화면을 옮길 때 `<경로>.rsc` 를 받아 응답 헤더의 식별자가
 * 자기 것과 같은지 본다. 다르면 "서버가 새로 배포됐다"고 보고 문서를 통째로 다시
 * 읽는다. vinext 는 빌드마다 UUID 를 새로 만드는데, 정적 배포에는 그 값을 헤더로
 * 실어 줄 서버가 없다 — 그래서 라우터가 **매번** 전체 새로고침으로 떨어진다.
 *
 * 값을 고정해 두면 앱 셸이 같은 값을 헤더로 붙일 수 있다
 * (`app/src/lib.rs` 의 `RSC_COMPATIBILITY_ID`). **두 값은 같아야 한다.**
 *
 * `ec2` 에는 **빈 값**을 박아 이 검사 자체를 끈다. 전에는 아무 것도 걸지 않아
 * 빌드마다 새로 생긴 UUID 가 그대로 남았는데, nginx 는 내보낸 파일을 그냥
 * 돌려줄 뿐이라(`infra/deploy/nginx.conf` 의 `try_files`) 응답에 이 헤더가
 * 없다. 그러면 라우터는 **화면을 옮길 때마다** "서버가 바뀌었다"고 보고 문서를
 * 통째로 다시 읽었다 — 듣던 곡이 끊기고 현재 곡·재생 위치·재생 목록이 모두
 * 초기화된 원인이다. 빈 값이면 라우터가 비교를 건너뛰므로 화면 이동이
 * 클라이언트 안에서 끝난다 (vinext 의 `isRscCompatibilityIdCompatible`:
 * 내 쪽 값이 없으면 언제나 호환으로 본다).
 *
 * 배포 중 낡은 화면을 걷어 내는 몫은 이걸로 사라진다. 되살리려면 nginx 가
 * `X-Vinext-RSC-Compatibility-Id` 를 붙이게 하고 여기서도 `app` 처럼 고정 값을
 * 넣으면 된다 — 헤더 없이 값만 고정하면 지금과 똑같이 매번 새로고침이 된다.
 *
 * vinext 뒤에 두어야 이 `define` 이 나중에 병합되어 이긴다.
 */
const pinRscCompatibilityId = (id: string) => ({
  name: 'mupid:pin-rsc-compatibility-id',
  config: () => ({
    define: { 'process.env.__VINEXT_RSC_COMPATIBILITY_ID': JSON.stringify(id) },
  }),
});

export const RSC_COMPATIBILITY_ID = 'mupid-static-1';

export default defineConfig(async () => {
  const target = process.env.MUPID_DEPLOY_TARGET ?? '';
  if (STATIC_TARGETS.includes(target)) {
    return {
      css: { postcss: { plugins: [tailwindcss()] } },
      server: {
        proxy: apiProxy,
        // 안드로이드 실기기에서 개발 서버를 여는 경우가 있다. Tauri 가
        // `adb reverse` 로 포트를 넘기지만, 기기가 붙지 않은 상황에서는
        // 같은 네트워크의 주소로 직접 들어와야 한다.
        host: process.env.TAURI_DEV_HOST || undefined,
      },
      plugins: [
        vinext(),
        // 정적 대상에는 이 헤더를 붙여 줄 서버가 없다. 앱은 셸이 대신 붙이므로
        // 값을 고정하고, nginx 로 가는 `ec2` 는 빈 값으로 검사를 끈다.
        pinRscCompatibilityId(target === 'app' ? RSC_COMPATIBILITY_ID : ''),
      ],
    };
  }
  // Keep Wrangler and Miniflare state project-local. These are non-secret tool
  // settings; application environment belongs in ignored `.env*` files.
  process.env.WRANGLER_WRITE_LOGS ??= 'false';
  process.env.WRANGLER_LOG_PATH ??= '.wrangler/logs';
  process.env.MINIFLARE_REGISTRY_PATH ??= '.wrangler/registry';

  // Wrangler snapshots its log path while the Cloudflare plugin is imported.
  const { cloudflare } = await import('@cloudflare/vite-plugin');

  return {
    css: { postcss: { plugins: [tailwindcss()] } },
    server: {
      proxy: apiProxy,
      ...(isCodexSeatbeltSandbox
        ? { watch: { useFsEvents: false, usePolling: true } }
        : {}),
    },
    plugins: [
      vinext(),
      sites(),
      cloudflare({
        viteEnvironment: { name: 'rsc', childEnvironments: ['ssr'] },
        config: localBindingConfig,
      }),
    ],
  };
});
