import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';
const source = readFileSync(new URL('../lib/track-waveform.ts', import.meta.url), 'utf8');
function setup() {
  const calls = [], progress = [];
  const mocks = {
    react: {},
    './api/session': { api: { stream: {
      listByMusic: async id => { calls.push(id); return [{ stream_id: 3, bitrate_kbps: 128 }]; },
      listChunks: async () => [{ index: 0, duration_ms: 1000 }, { index: 1, duration_ms: 1000 }],
      downloadChunk: async (_, i) => new Uint8Array([i]).buffer,
    } } },
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
  return { load: context.exports.loadTrackWaveform, calls, progress };
}
test('서버 파형 대신 음원을 기기에서 분석하고 청크마다 진행률을 보낸다', async () => {
  const { load, calls, progress } = setup();
  const values = await load({ musicId: 7, waveform: [9, 9, 9, 9] }, new AbortController().signal, p => progress.push(p));
  assert.deepEqual(Array.from(values), [.5, .5, 1, 1]);
  assert.deepEqual(progress.map(p => p.progress), [.5, 1]);
  assert.deepEqual(Array.from(progress[0].values), [1, 1, 0, 0]);
  await load({ musicId: 7 }, new AbortController().signal);
  assert.deepEqual(calls, [7, 7]);
});
test('이미 취소한 분석은 음원 요청을 시작하지 않는다', async () => {
  const { load, calls } = setup();
  const controller = new AbortController(); controller.abort();
  await assert.rejects(load({ musicId: 7 }, controller.signal), { name: 'AbortError' });
  assert.equal(calls.length, 0);
});
