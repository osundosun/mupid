import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { resolve, sep, extname } from 'node:path';
import type { Plugin } from 'vite';

/** 로컬 음원은 public 밖에 두어 배포 번들에 포함하지 않는다. */
export function localDemoAssets(enabled: boolean): Plugin {
  return {
    name: 'mupid-local-demo-assets',
    apply: 'serve',
    configureServer(server) {
      if (!enabled) return;
      const root = resolve(server.config.root, 'work/local-demo');
      server.middlewares.use('/local-demo', async (req, res) => {
        try {
          const path = resolve(root, '.' + decodeURIComponent((req.url ?? '/').split('?')[0]));
          if (!path.startsWith(root + sep)) { res.writeHead(403).end(); return; }
          const info = await stat(path);
          if (!info.isFile()) { res.writeHead(404).end(); return; }
          const mime: Record<string, string> = { '.json': 'application/json; charset=utf-8', '.mp3': 'audio/mpeg', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.webp': 'image/webp' };
          res.setHeader('Content-Type', mime[extname(path)] ?? 'application/octet-stream');
          res.setHeader('Accept-Ranges', 'bytes');
          res.setHeader('Cache-Control', 'no-cache');
          const range = req.headers.range;
          let start = 0, end = info.size - 1;
          if (range) {
            const match = /^bytes=(\d+)-(\d*)$/.exec(range);
            start = match ? Number(match[1]) : -1;
            end = match?.[2] ? Math.min(Number(match[2]), end) : end;
            if (start < 0 || start > end) { res.writeHead(416, { 'Content-Range': `bytes */${info.size}` }).end(); return; }
            res.statusCode = 206;
            res.setHeader('Content-Range', `bytes ${start}-${end}/${info.size}`);
          }
          res.setHeader('Content-Length', end - start + 1);
          if (req.method === 'HEAD') { res.end(); return; }
          createReadStream(path, { start, end }).on('error', () => res.destroy()).pipe(res);
        } catch { res.writeHead(404).end('Local demo file not found'); }
      });
    },
  };
}
