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
 * `ec2` 대상에는 걸지 않는다. 거기서는 배포 중에 예전 화면이 새 청크를 받을 수
 * 있고, 그때는 전체 새로고침이 맞는 동작이다.
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
        ...(target === 'app'
          ? [pinRscCompatibilityId(RSC_COMPATIBILITY_ID)]
          : []),
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
