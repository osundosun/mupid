import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';
const source = readFileSync(new URL('../lib/track-waveform.ts', import.meta.url), 'utf8');
/** @param served 서버가 줄 파형. null 이면 파형이 없는 곡(404)처럼 던진다. */
function setup(served = null) {
  const calls = [], progress = [], waveformCalls = [];
  const mocks = {
    react: {},
    './api/session': { api: {
      music: {
        waveform: async id => {
          waveformCalls.push(id);
          if (!served) { const e = Error('MUPID_WAVEFORM_NOT_FOUND'); e.status = 404; throw e; }
          return { music_id: id, sample_count: served.length, values: served };
        },
      },
      stream: {
        listByMusic: async id => { calls.push(id); return [{ stream_id: 3, bitrate_kbps: 128 }]; },
        listChunks: async () => [{ index: 0, duration_ms: 1000 }, { index: 1, duration_ms: 1000 }],
        downloadChunk: async (_, i) => new Uint8Array([i]).buffer,
      },
    } },
    './api/endpoints': { WAVEFORM_SAMPLES: 4 },
    './audio-waveform': { loadAudioWaveform: async () => { throw Error('unexpected local audio'); } },
    './player/rtp': { parseChunk: bytes => ({ frames: [new Uint8Array(bytes)] }) },
    './player/decoder': {
      OPUS_SAMPLE_RATE: 128, isOpusDecodingSupported: () => true,
      decodeOpusFrames: async (_, frames) => ({ numberOfChannels: 2, length: 128,
        getChannelData: () => new Float32Array(128).fill(frames[0][0] === 0 ? .5 : 1) }),
    },
  };
  const context = { exports: {}, require: name => { if (!(name in mocks)) throw Error(name); return mocks[name]; },
    OfflineAudioContext: class {}, DOMException, AbortController };
  vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, context);
  return { load: context.exports.loadTrackWaveform, calls, progress, waveformCalls };
}
test('서버에 파형이 있으면 그대로 쓰고 청크를 하나도 받지 않는다', async () => {
  const { load, calls, waveformCalls, progress } = setup([0, .25, .5, 1]);
  const values = await load({ musicId: 7 }, new AbortController().signal, p => progress.push(p));
  assert.deepEqual(Array.from(values), [0, .25, .5, 1]);
  assert.deepEqual(waveformCalls, [7]);
  // 청크를 건드리지 않는 것이 이 변경의 요점이다 — 곡 하나에 수 MB 가 오갔다.
  assert.deepEqual(calls, []);
  assert.deepEqual(progress, []);
});
test('서버 파형은 0~1 로 가둔다 — 그리는 쪽이 값을 그대로 높이로 쓴다', async () => {
  const { load } = setup([-1, 2, Number.NaN, .5]);
  const values = await load({ musicId: 7 }, new AbortController().signal);
  assert.deepEqual(Array.from(values), [0, 1, 0, .5]);
});
test('서버에 파형이 없으면 음원을 기기에서 분석하고 청크마다 진행률을 보낸다', async () => {
  const { load, calls, progress, waveformCalls } = setup();
  const values = await load({ musicId: 7 }, new AbortController().signal, p => progress.push(p));
  assert.deepEqual(Array.from(values), [.5, .5, 1, 1]);
  assert.deepEqual(progress.map(p => p.progress), [.5, 1]);
  assert.deepEqual(Array.from(progress[0].values), [1, 1, 0, 0]);
  await load({ musicId: 7 }, new AbortController().signal);
  assert.deepEqual(waveformCalls, [7, 7]);
  assert.deepEqual(calls, [7, 7]);
});
test('빈 파형이나 한 칸짜리 응답은 없는 것으로 보고 직접 계산한다', async () => {
  const { load, calls } = setup([]);
  const values = await load({ musicId: 7 }, new AbortController().signal);
  assert.deepEqual(Array.from(values), [.5, .5, 1, 1]);
  assert.deepEqual(calls, [7]);
});
test('이미 취소한 분석은 음원 요청을 시작하지 않는다', async () => {
  const { load, calls, waveformCalls } = setup();
  const controller = new AbortController(); controller.abort();
  await assert.rejects(load({ musicId: 7 }, controller.signal), { name: 'AbortError' });
  assert.equal(calls.length, 0);
  assert.equal(waveformCalls.length, 0);
});
test('파형을 받는 사이에 취소되면 청크로 내려가지 않는다', async () => {
  const { load, calls } = setup([0, .5, 1, .5]);
  const controller = new AbortController();
  const pending = load({ musicId: 7 }, controller.signal);
  controller.abort();
  await assert.rejects(pending, { name: 'AbortError' });
  assert.equal(calls.length, 0);
});
