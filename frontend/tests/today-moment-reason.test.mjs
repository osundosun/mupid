import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

const inline = (text) => `data:text/javascript;base64,${Buffer.from(text).toString('base64')}`;
const source = await readFile(new URL('../lib/recommendations.ts', import.meta.url), 'utf8');
const js = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
}).outputText
  .replace("'./api/session'", JSON.stringify(inline('export const api = { recommendation: { daily: async () => globalThis.__dailyReasonFixture } };')))
  .replace("'./stream-catalog'", JSON.stringify(inline('export const toTrack = music => ({ id: music.id, title: music.title });')));
const { fetchTodayMoments } = await import(inline(js));

test('daily moment exposes server reasons to the player and never invents missing reasons', async () => {
  globalThis.__dailyReasonFixture = { date: '2026-09-21', groups: [{
    title: '오늘의 Moment', reason: '그룹 추천 근거', tracks: [
      { music: { id: 1 }, reason: '곡 추천 근거' },
      { music: { id: 2 }, reason: '  ' },
    ],
  }] };
  const { tracks } = await fetchTodayMoments();
  assert.equal(tracks[0].recommendationReason, '곡 추천 근거');
  assert.equal(tracks[1].recommendationReason, '그룹 추천 근거');
  globalThis.__dailyReasonFixture.groups[0].reason = '';
  assert.equal((await fetchTodayMoments()).tracks[1].recommendationReason, undefined);
  delete globalThis.__dailyReasonFixture;
});
