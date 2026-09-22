/*
 * 곡을 바꾸는 순간의 청크 귀속 (S15P21A204-434).
 *
 * 챗봇 추천 곡을 누르면 **화면은 새 곡인데 소리는 듣던 곡**이 계속 나는 일이
 * 있었다. 링버퍼는 엔진에 하나뿐이고 담기는 값에 곡 표시가 없어서, 곡이 바뀌는
 * 찰나에 도착한 이전 곡 청크가 새 곡의 `decoded` 자리에 들어갈 수 있었다.
 *
 * 여기서는 그 순간을 그대로 재현한다 — 이전 곡의 청크 응답을 곡을 바꾼 뒤에
 * 돌려주고, 새 곡이 들고 있는 PCM 이 전부 새 곡 스트림에서 온 것인지 본다.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

const transpile = (code) =>
  ts.transpileModule(code, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  }).outputText;
const load = (code) =>
  import('data:text/javascript;base64,' + Buffer.from(transpile(code)).toString('base64'));

const read = async (relative) =>
  (await readFile(new URL(relative, import.meta.url), 'utf8')).replaceAll('\r\n', '\n');

/*
 * 엔진을 그대로 쓰되 바깥 모듈만 시험용으로 갈아 끼운다. import 줄을 걷어내고
 * 같은 이름을 인자로 받는 함수 안에 본문을 넣는다 — 엔진 코드는 한 글자도
 * 바꾸지 않으므로, 여기서 나는 실패는 엔진의 실패다.
 */
const engineSource = await read('../lib/player/engine.ts');
const { RingBuffer } = await load(await read('../lib/player/ring-buffer.ts'));
const { makeEngine } = await load(`
export function makeEngine(deps: any) {
  const { api, RingBuffer, decodeOpusFrames, parseChunk } = deps;
  type Store<T> = any; type ChunkInfo = any; type StreamInfo = any;
  const localDemoEnabled = () => false;
  const isOpusDecodingSupported = () => true;
  const OPUS_SAMPLE_RATE = 48000;
  const createStore = (initial: any) => {
    let state = initial;
    return {
      get: () => state,
      set: (patch: any) => { state = { ...state, ...(typeof patch === 'function' ? patch(state) : patch) }; },
      subscribe: () => () => {},
    };
  };
${engineSource
  .replace(/^import .*;$/gm, '')
  .replace(/^export /gm, '')
  .split('\n')
  .map((line) => (line ? `  ${line}` : line))
  .join('\n')}
  return { PlayerEngine };
}`);

/** 예약·이득 조작만 받아 주는 최소 오디오 컨텍스트. 소리는 내지 않는다. */
class FakeAudioContext {
  currentTime = 0;
  state = 'running';
  destination = { name: 'destination' };
  async resume() {}
  createGain() {
    const node = {
      gain: {
        value: 1,
        setValueAtTime() {},
        linearRampToValueAtTime() {},
        cancelScheduledValues() {},
        setTargetAtTime() {},
      },
      connect: (target) => target,
      disconnect() {},
    };
    return node;
  }
  createBufferSource() {
    return { buffer: null, onended: null, connect() {}, start() {}, stop() {}, disconnect() {} };
  }
}

/** 마이크로태스크가 다 돌 때까지 기다린다. */
const settleAll = async (rounds = 20) => {
  for (let i = 0; i < rounds; i += 1) await new Promise((resolve) => setTimeout(resolve, 0));
};

/** 스트림 번호를 그대로 바이트에 심는다 — 이 청크가 어느 곡에서 왔는지의 표다. */
const chunkBytes = (streamId) => new Uint8Array([streamId]).buffer;

function harness() {
  /** 아직 응답하지 않은 청크 요청들. 시험이 원하는 순서로 돌려준다. */
  const waiting = [];
  const api = {
    stream: {
      listByMusic: async (musicId) => [{ stream_id: musicId * 10, bitrate_kbps: 128 }],
      listChunks: async (streamId) =>
        [0, 1, 2].map((index) => ({ index, duration_ms: 6000, stream_id: streamId })),
      downloadChunk: (streamId, index) =>
        new Promise((resolve, reject) => waiting.push({ streamId, index, resolve, reject })),
    },
  };
  /** 디코딩을 잡아 둘 수 있는 문. 곡 전환과 디코딩이 겹치는 순간을 만든다. */
  let gate = null;
  const decodeOpusFrames = async (_context, frames) => {
    if (gate) await gate;
    return { streamId: frames[0][0] };
  };
  const deps = {
    api,
    RingBuffer,
    decodeOpusFrames,
    parseChunk: (bytes) => ({ version: 1, frames: [new Uint8Array(bytes)] }),
  };
  return {
    deps,
    /** 받아 둔 요청 하나에 응답한다. 없으면 시험이 전제부터 틀린 것이다. */
    deliver: (streamId, index) => {
      const at = waiting.findIndex((item) => item.streamId === streamId && item.index === index);
      assert.notEqual(at, -1, `요청되지 않은 청크: ${streamId}/${index}`);
      waiting.splice(at, 1)[0].resolve(chunkBytes(streamId));
    },
    holdDecoding: () => {
      let release;
      gate = new Promise((resolve) => { release = resolve; });
      return () => { gate = null; release(); };
    },
  };
}

test('곡을 바꾼 뒤 도착한 이전 곡 청크가 새 곡의 PCM 자리에 들어가지 않는다', async () => {
  globalThis.window = { AudioContext: FakeAudioContext };
  const { deps, deliver, holdDecoding } = harness();
  const { PlayerEngine } = makeEngine(deps);
  const engine = new PlayerEngine();

  // 1번 곡(스트림 10)을 듣는 중. 청크 요청이 나가 있고 아직 응답은 없다.
  await engine.load(1);
  await settleAll();

  // 2번 곡(스트림 20)으로 갈아탄다. 챗봇 추천 곡을 누른 자리다.
  await engine.load(2);
  await settleAll();

  // 방금 듣던 곡은 재생 목록에 그대로 남아 미리받기 대상이 된다.
  engine.setQueue([1]);
  await settleAll();

  // 새 곡의 청크가 먼저 도착해 디코딩이 시작되고, 그 사이에 **이전 곡의**
  // 청크가 뒤늦게 도착한다. 링버퍼는 하나라서 여기서 섞일 수 있었다.
  const release = holdDecoding();
  deliver(20, 0);
  await settleAll();
  deliver(10, 1);
  await settleAll();
  release();
  await settleAll();

  const decoded = engine.source.decoded;
  assert.equal(engine.source.musicId, 2);
  for (const [index, buffer] of decoded) {
    assert.equal(buffer.streamId, 20, `${index}번 청크가 이전 곡(스트림 ${buffer.streamId})의 소리다`);
  }
});

test('곡을 바꾼 뒤에도 새 곡의 청크는 그대로 자기 자리에 들어간다', async () => {
  globalThis.window = { AudioContext: FakeAudioContext };
  const { deps, deliver } = harness();
  const { PlayerEngine } = makeEngine(deps);
  const engine = new PlayerEngine();

  await engine.load(1);
  await settleAll();
  await engine.load(2);
  await settleAll();
  engine.setQueue([1]);
  await settleAll();

  deliver(10, 0);
  await settleAll();
  deliver(20, 0);
  deliver(20, 1);
  await settleAll();

  assert.equal(engine.source.decoded.get(0)?.streamId, 20);
  assert.equal(engine.source.decoded.get(1)?.streamId, 20);
});
