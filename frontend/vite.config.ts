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

export default defineConfig(async () => {
  if (process.env.MUPID_DEPLOY_TARGET === 'ec2') {
    return {
      css: { postcss: { plugins: [tailwindcss()] } },
      server: { proxy: apiProxy },
      plugins: [vinext()],
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
