import type { NextConfig } from 'next';

/**
 * 서버 없이 정적 파일만 내보내는 대상들.
 *
 * - `ec2`: nginx 가 그대로 서빙한다.
 * - `app`: Tauri 앱 번들에 들어간다 (`app/tauri.conf.json` 의 `frontendDist`).
 *
 * 기본(Cloudflare Worker) 배포는 지금까지처럼 RSC 서버를 그대로 쓴다.
 */
export const staticTargets = ['ec2', 'app'];

const nextConfig: NextConfig = staticTargets.includes(
  process.env.MUPID_DEPLOY_TARGET ?? '',
)
  ? { output: 'export' }
  : {};

export default nextConfig;
