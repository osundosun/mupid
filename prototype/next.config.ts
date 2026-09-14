import type { NextConfig } from 'next';

const nextConfig: NextConfig = process.env.MUPID_DEPLOY_TARGET === 'ec2'
  ? { output: 'export' }
  : {};

export default nextConfig;
