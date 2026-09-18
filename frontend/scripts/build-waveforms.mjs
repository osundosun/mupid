#!/usr/bin/env node
/**
 * 카탈로그 전곡의 파형을 미리 계산해 `public/waveforms/{musicId}.json` 으로 남긴다.
 *
 * 왜 여기서 하는가. 파형은 곡의 실제 소리를 디코드해야 나온다. 사용자 기기에서
 * 곡마다 하면 처음 볼 때 곡 한 번 재생분(≈3MB)을 받아야 하고, 백엔드에 맡기면
 * 그쪽 파이프라인이 필요하다. 대신 프론트가 배포 전에 한 번 계산해 정적 파일로
 * 실어 두면, 모든 기기에서 즉시 뜨고 서버는 아무것도 바뀌지 않는다. 화면은
 * `lib/track-waveform.ts` 가 이 파일을 먼저 찾고, 없는 곡만 기기에서 계산한다.
 *
 * 형식은 백엔드 요청서와 같다 — 길이 160, 각 값 0~1, 곡을 160 칸으로 나눠 칸마다
 * RMS 를 재고 곡 안의 최댓값이 1.0 이 되게 정규화. 칸 번호는 재생 위치에 선형 대응.
 *
 * 재생과 같은 경로로 청크를 받는다(`GET /api/v1/chunk/{stream}/{index}`, 'RTPK'
 * 컨테이너 안의 raw Opus 프레임). 디코더는 `opusscript`(순수 JS Opus). 한 곡은
 * 디코더 하나로 청크를 이어서 풀기 때문에 이음새 워밍업 문제가 없다.
 *
 * 사용법:
 *   MUPID_API_ORIGIN=https://api.example.com MUPID_EMAIL=... MUPID_PASSWORD=... \
 *   node scripts/build-waveforms.mjs [--out public/waveforms] [--concurrency 3] \
 *                                    [--only 12,34] [--force] [--limit N]
 *
 *   MUPID_ACCESS_TOKEN 을 주면 로그인 대신 그 토큰을 쓴다.
 *   이미 파일이 있는 곡은 건너뛴다(--force 로 다시). 실패한 곡은 목록을 남기고 계속 간다.
 */

import { mkdir, readFile, writeFile, access } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';

const require = createRequire(import.meta.url);
const OpusScript = require('opusscript');

const SAMPLES = 160;
const SAMPLE_RATE = 48000;
const CHANNELS = 2;
const MAGIC = 0x5254504b; // 'RTPK'
const RTP_HEADER = 12;

// ── 인자 ──────────────────────────────────────────────────────────────────────
const args = process.argv.slice(2);
const flag = (name, fallback) => { const i = args.indexOf(`--${name}`); return i >= 0 ? args[i + 1] : fallback; };
const has = (name) => args.includes(`--${name}`);
const ORIGIN = (process.env.MUPID_API_ORIGIN ?? 'http://localhost:9900').replace(/\/$/, '');
const OUT = path.resolve(flag('out', 'public/waveforms'));
const CONCURRENCY = Number(flag('concurrency', '3'));
const ONLY = flag('only', '') ? new Set(flag('only', '').split(',').map(Number)) : null;
const LIMIT = Number(flag('limit', '0')) || Infinity;
const FORCE = has('force');

// ── API ─────────────────────────────────────────────────────────────────────
let token = process.env.MUPID_ACCESS_TOKEN ?? '';
async function login() {
  const email = process.env.MUPID_EMAIL, password = process.env.MUPID_PASSWORD;
  if (!email || !password) throw new Error('MUPID_ACCESS_TOKEN 또는 MUPID_EMAIL/MUPID_PASSWORD 가 필요해요.');
  const res = await fetch(`${ORIGIN}/api/v1/auth`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ email, password }) });
  if (!res.ok) throw new Error(`로그인 실패 ${res.status}`);
  const body = await res.json();
  token = body?.data?.access_token ?? body?.access_token;
  if (!token) throw new Error('로그인 응답에 access_token 이 없어요.');
}
async function api(pathname, type = 'json', retry = 1) {
  const res = await fetch(`${ORIGIN}${pathname}`, { headers: token ? { authorization: `Bearer ${token}` } : {} });
  if (res.status === 401 && retry > 0 && !process.env.MUPID_ACCESS_TOKEN) { await login(); return api(pathname, type, retry - 1); }
  if (!res.ok) throw new Error(`${pathname} → ${res.status}`);
  if (type === 'buffer') return Buffer.from(await res.arrayBuffer());
  const body = await res.json();
  return body && typeof body === 'object' && 'data' in body ? body.data : body;
}

async function* allMusic() {
  for (let page = 0; ; page++) {
    const data = await api(`/api/v1/music?page=${page}&size=100`);
    const items = data?.items ?? [];
    for (const item of items) yield item;
    if (!items.length || (data.total_pages !== undefined && page + 1 >= data.total_pages)) return;
  }
}

// ── 청크 → 프레임 ────────────────────────────────────────────────────────────
function parseChunk(buf) {
  if (buf.length < 10 || buf.readUInt32BE(0) !== MAGIC) throw new Error('RTPK 아님');
  const count = buf.readUInt32BE(6);
  const frames = [];
  let offset = 10;
  for (let i = 0; i < count; i++) {
    const length = buf.readUInt16BE(offset); offset += 2;
    if (length > RTP_HEADER) frames.push(buf.subarray(offset + RTP_HEADER, offset + length));
    offset += length;
  }
  return frames;
}

// ── 한 곡 ───────────────────────────────────────────────────────────────────
async function waveformFor(music) {
  const streams = (await api(`/api/v1/music/${music.music_id}/stream`))?.streams ?? [];
  if (!streams.length) throw new Error('스트림 없음');
  const stream = streams.reduce((best, s) => (s.bitrate_kbps > best.bitrate_kbps ? s : best));
  const chunks = ((await api(`/api/v1/chunk/${stream.stream_id}`))?.chunks ?? []).slice().sort((a, b) => a.index - b.index);
  if (!chunks.length) throw new Error('청크 없음');
  const totalSamples = chunks.reduce((sum, c) => sum + Math.round(c.duration_ms / 1000 * SAMPLE_RATE), 0);

  // 다운로드는 몇 개 앞서 받고, 디코드는 순서대로.
  const pending = new Map();
  const fetchAt = (i) => { if (i < chunks.length && !pending.has(i)) pending.set(i, api(`/api/v1/chunk/${stream.stream_id}/${chunks[i].index}`, 'buffer')); };
  const decoder = new OpusScript(SAMPLE_RATE, CHANNELS, OpusScript.Application.AUDIO);
  const energy = new Float64Array(SAMPLES), count = new Float64Array(SAMPLES);
  let offset = 0;
  try {
    for (let i = 0; i < chunks.length; i++) {
      for (let k = i; k < i + CONCURRENCY; k++) fetchAt(k);
      const buf = await pending.get(i); pending.delete(i);
      for (const frame of parseChunk(buf)) {
        let pcm;
        try { pcm = decoder.decode(frame); } catch { continue; } // 깨진 프레임 하나는 건너뛴다
        const samples = pcm.length / 2 / CHANNELS; // int16 interleaved
        for (let s = 0; s < samples; s += 64) {
          const end = Math.min(samples, s + 64);
          const bin = Math.min(SAMPLES - 1, Math.floor((offset + s) / totalSamples * SAMPLES));
          let e = 0;
          for (let j = s * CHANNELS; j < end * CHANNELS; j++) { const v = pcm.readInt16LE(j * 2) / 32768; e += v * v; }
          energy[bin] += e; count[bin] += (end - s) * CHANNELS;
        }
        offset += samples;
      }
    }
  } finally {
    decoder.delete();
  }
  const rms = Array.from(energy, (e, i) => (count[i] > 0 ? Math.sqrt(e / count[i]) : 0));
  const peak = Math.max(...rms, 1e-6);
  return rms.map((v) => Math.round((v / peak) * 1000) / 1000);
}

// ── 전체 ───────────────────────────────────────────────────────────────────────
async function main() {
  await mkdir(OUT, { recursive: true });
  if (!token) await login();
  const failed = [];
  let done = 0, skipped = 0, seen = 0;
  const queue = [];
  for await (const music of allMusic()) {
    if (ONLY && !ONLY.has(music.music_id)) continue;
    if (!music.playable) { skipped++; continue; }
    if (++seen > LIMIT) break;
    queue.push(music);
  }
  console.log(`대상 ${queue.length}곡 (재생 불가 ${skipped}곡 제외) → ${OUT}`);
  let cursor = 0;
  const worker = async () => {
    while (cursor < queue.length) {
      const music = queue[cursor++];
      const file = path.join(OUT, `${music.music_id}.json`);
      if (!FORCE && await access(file).then(() => true, () => false)) { skipped++; continue; }
      const started = Date.now();
      try {
        const values = await waveformFor(music);
        await writeFile(file, JSON.stringify(values));
        done++;
        console.log(`✓ ${music.music_id} ${music.title} (${((Date.now() - started) / 1000).toFixed(1)}s) [${done}/${queue.length}]`);
      } catch (error) {
        failed.push({ music_id: music.music_id, title: music.title, error: String(error?.message ?? error) });
        console.warn(`✗ ${music.music_id} ${music.title}: ${error?.message ?? error}`);
      }
    }
  };
  await Promise.all(Array.from({ length: Math.max(1, Math.min(CONCURRENCY, 4)) }, worker));
  // 어떤 곡의 파형이 있는지 한 파일로. 화면이 곡마다 404 를 맞는 대신 이것을 본다.
  const { readdir } = await import('node:fs/promises');
  const ids = (await readdir(OUT)).filter((f) => /^\d+\.json$/.test(f)).map((f) => Number(f.slice(0, -5))).sort((a, b) => a - b);
  await writeFile(path.join(OUT, 'index.json'), JSON.stringify({ samples: SAMPLES, generated_at: new Date().toISOString(), music_ids: ids }));
  if (failed.length) await writeFile(path.join(OUT, 'failed.json'), JSON.stringify(failed, null, 2));
  console.log(`완료 ${done}곡 · 건너뜀 ${skipped}곡 · 실패 ${failed.length}곡 · 총 ${ids.length}곡 파형`);
  if (failed.length) process.exitCode = 1;
}

main().catch((error) => { console.error(error); process.exit(1); });
