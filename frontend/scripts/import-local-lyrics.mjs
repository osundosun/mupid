import { readFile, writeFile } from 'node:fs/promises';
import { resolve, basename } from 'node:path';
import { createHash } from 'node:crypto';

// Merge verified lyrics into the existing catalog without replacing tracks or audio.
const root = resolve('work/local-demo');
const catalogPath = resolve(root, 'catalog.json');
const tracks = JSON.parse(await readFile(catalogPath, 'utf8'));
const report = [];
for (const track of tracks) {
  const key = basename(track.localAudio ?? '', '.mp3');
  if (!/^T\d+$/.test(key)) continue;
  let data;
  try { data = JSON.parse(await readFile(resolve(root, 'metadata', `${key}.json`), 'utf8')); }
  catch (error) { if (error.code !== 'ENOENT') throw error; report.push({ key, status: 'metadata missing' }); continue; }
  if (data.track_id !== key || data.flow_title !== track.title) throw new Error(`${key}: wrong track metadata`);
  const hash = createHash('sha256').update(await readFile(resolve(root, 'audio', `${key}.mp3`))).digest('hex');
  if (!data.assets?.audio?.sha256 || hash !== data.assets.audio.sha256) {
    report.push({ key, status: 'audio mismatch — lyrics not changed' });
    continue;
  }
  const lyrics = (data.lyrics_sync ?? []).filter(line => typeof line.text === 'string' && line.text.trim() && Number.isFinite(line.start_sec) && line.start_sec >= 0);
  if (lyrics.some(line => line.start_sec > track.duration)) throw new Error(`${key}: lyrics exceed audio duration`);
  track.localLyrics = lyrics.map((line, index) => ({ lyric_id: index + 1, time_ms: Math.round(line.start_sec * 1000), text: line.text.trim() })).sort((a, b) => a.time_ms - b.time_ms);
  report.push({ key, lines: lyrics.length, status: lyrics.length ? 'connected' : 'no source lyrics' });
}
await writeFile(catalogPath, JSON.stringify(tracks, null, 2) + '\n');
console.table(report);
