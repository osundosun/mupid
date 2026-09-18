/**
 * 곡의 파형(0~1, `WAVEFORM_SAMPLES` 칸)을 구한다.
 *
 * 우선순위:
 *   1. 서버가 곡 정보에 실어 준 배열(`Track.waveform`) — 있으면 그대로.
 *   2. 이 기기에서 전에 계산해 둔 것(IndexedDB, 메모리).
 *   3. 청크를 받아 여기서 계산한다. 재생과 같은 경로(`/api/v1/chunk/...`)로 청크를
 *      받고, 같은 디코더(`decodeOpusFrames`)로 PCM 을 풀어 칸마다 RMS 를 재고, 곡 안의
 *      최댓값이 1.0 이 되게 맞춘다. 백엔드가 나중에 같은 형식으로 내려주면 1 이 이긴다.
 *   4. 로컬 데모 곡(`localAudio`)은 파일을 통째로 디코드한다(`loadAudioWaveform`).
 *
 * 3 은 곡을 처음부터 끝까지 받아야 한다(6초·128kbps 청크, 3분 곡 ≈ 30개 ≈ 3MB).
 * 그래서 진행률을 흘려 화면이 받은 데까지 먼저 그리게 하고, 끝나면 IndexedDB 에
 * 남겨 다음부터는 받지 않는다. 계산은 순간 저장 화면처럼 파형이 필요한 곳에서만
 * 시작한다 — 목록을 훑는 것만으로 곡마다 3MB 를 받게 하지 않는다.
 */

import { useEffect, useState } from 'react';
import { api } from './api/session';
import { WAVEFORM_SAMPLES } from './api/endpoints';
import { loadAudioWaveform } from './audio-waveform';
import { decodeOpusFrames, isOpusDecodingSupported, OPUS_SAMPLE_RATE } from './player/decoder';
import { parseChunk } from './player/rtp';
import type { Track } from './mupid-data';

export type WaveformProgress = {
  /** 지금까지의 파형. 아직 못 받은 칸은 0. 없으면 null. */
  values: number[] | null;
  /** 0~1. 서버 값이나 캐시면 바로 1. */
  progress: number;
  /** 계산할 수 없는 곡(스트림 없음, 디코더 미지원, 실패). */
  failed: boolean;
};

/** 앞 청크의 마지막 프레임 몇 개를 디코더 워밍업에 쓴다. `engine.ts` 의 PRE_ROLL_FRAMES 와 같다. */
const PRE_ROLL_FRAMES = 24;
/** 한 번에 받아 두는 청크 수. 디코드는 순서대로 하지만 다운로드는 조금 앞서 간다. */
const AHEAD = 2;
const DB_NAME = 'mupid-waveform';
const STORE = 'waveform';

const memory = new Map<string, number[]>();
const inflight = new Map<string, Promise<number[]>>();
const listeners = new Map<string, Set<(p: WaveformProgress) => void>>();

const keyOf = (track: Track) => track.musicId !== undefined ? `music:${track.musicId}` : track.localAudio ? `local:${track.localAudio}` : null;

// ── IndexedDB (없거나 막힌 환경에서는 조용히 건너뛴다) ──────────────────────
function openDb(): Promise<IDBDatabase | null> {
  return new Promise(resolve => {
    try {
      if (typeof indexedDB === 'undefined') return resolve(null);
      const request = indexedDB.open(DB_NAME, 1);
      request.onupgradeneeded = () => { request.result.createObjectStore(STORE); };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => resolve(null);
    } catch { resolve(null); }
  });
}
async function readStored(key: string): Promise<number[] | null> {
  const db = await openDb(); if (!db) return null;
  return new Promise(resolve => {
    try {
      const request = db.transaction(STORE, 'readonly').objectStore(STORE).get(key);
      request.onsuccess = () => { const v = request.result; resolve(Array.isArray(v) && v.length === WAVEFORM_SAMPLES ? v : null); };
      request.onerror = () => resolve(null);
    } catch { resolve(null); }
  });
}
async function writeStored(key: string, values: number[]): Promise<void> {
  const db = await openDb(); if (!db) return;
  try { db.transaction(STORE, 'readwrite').objectStore(STORE).put(values, key); } catch { /* 저장은 못 해도 파형은 보인다. */ }
}

// ── 계산 ────────────────────────────────────────────────────────────────────
const normalize = (energy: Float64Array, count: Float64Array): number[] => {
  const rms = Array.from(energy, (e, i) => count[i] > 0 ? Math.sqrt(e / count[i]) : 0);
  const peak = Math.max(...rms, 1e-6);
  return rms.map(v => v / peak);
};

const emit = (key: string, p: WaveformProgress) => { listeners.get(key)?.forEach(fn => fn(p)); };

async function computeFromChunks(track: Track, key: string, signal: AbortSignal): Promise<number[]> {
  if (!isOpusDecodingSupported()) throw new Error('unsupported');
  const musicId = track.musicId!;
  const streams = await api.stream.listByMusic(musicId, signal);
  if (!streams.length) throw new Error('no-stream');
  const stream = streams.reduce((best, s) => s.bitrate_kbps > best.bitrate_kbps ? s : best);
  const chunks = (await api.stream.listChunks(stream.stream_id, signal)).slice().sort((a, b) => a.index - b.index);
  if (!chunks.length) throw new Error('no-chunks');
  const totalSamples = chunks.reduce((sum, c) => sum + Math.round(c.duration_ms / 1000 * OPUS_SAMPLE_RATE), 0);
  const energy = new Float64Array(WAVEFORM_SAMPLES);
  const count = new Float64Array(WAVEFORM_SAMPLES);
  // 디코더가 만든 AudioBuffer 를 담을 그릇. 소리를 내지 않으니 오프라인으로.
  const context = new OfflineAudioContext(2, 1, OPUS_SAMPLE_RATE);
  const downloads = new Map<number, Promise<ArrayBuffer>>();
  const fetchAt = (i: number) => {
    if (i >= chunks.length || downloads.has(i)) return;
    downloads.set(i, api.stream.downloadChunk(stream.stream_id, chunks[i].index, signal));
  };
  let offset = 0;
  let tail: Uint8Array[] | undefined;
  for (let i = 0; i < chunks.length; i++) {
    for (let k = i; k <= i + AHEAD; k++) fetchAt(k);
    const bytes = await downloads.get(i)!;
    downloads.delete(i);
    if (signal.aborted) throw new DOMException('aborted', 'AbortError');
    const { frames } = parseChunk(bytes);
    const expected = Math.round(chunks[i].duration_ms / 1000 * OPUS_SAMPLE_RATE);
    const buffer = await decodeOpusFrames(context, frames, { channels: 2, frameDurationMs: 20, expectedSamples: expected, preRoll: tail });
    tail = frames.slice(-PRE_ROLL_FRAMES).map(f => new Uint8Array(f));
    const channels = Array.from({ length: buffer.numberOfChannels }, (_, c) => buffer.getChannelData(c));
    const length = buffer.length;
    // 샘플 하나하나가 아니라 64개씩 묶어 칸을 정한다. 칸 경계가 6초 청크 안에서
    // 여러 번 바뀌므로 묶음 단위로 나누면 충분히 정확하고 훨씬 빠르다.
    const STEP = 64;
    for (let s = 0; s < length; s += STEP) {
      const end = Math.min(length, s + STEP);
      const bin = Math.min(WAVEFORM_SAMPLES - 1, Math.floor((offset + s) / totalSamples * WAVEFORM_SAMPLES));
      let e = 0;
      for (const ch of channels) for (let j = s; j < end; j++) e += ch[j] * ch[j];
      energy[bin] += e; count[bin] += (end - s) * channels.length;
    }
    offset += length;
    emit(key, { values: normalize(energy, count), progress: (i + 1) / chunks.length, failed: false });
  }
  return normalize(energy, count);
}

async function computeFromLocal(url: string): Promise<number[]> {
  // `loadAudioWaveform` 은 4~92 로 펼친 높이를 준다. 0~1 로 되돌린다.
  const heights = await loadAudioWaveform(url, WAVEFORM_SAMPLES);
  return heights.map(h => Math.max(0, Math.min(1, (h - 4) / 88)));
}

/** 파형을 구한다. 서버 값 → 캐시 → 계산. 계산 중간값은 `subscribe` 로 흘린다. */
export function loadTrackWaveform(track: Track, signal: AbortSignal): Promise<number[] | null> {
  if (track.waveform && track.waveform.length > 1) return Promise.resolve(track.waveform);
  const key = keyOf(track);
  if (!key) return Promise.resolve(null);
  const cached = memory.get(key);
  if (cached) return Promise.resolve(cached);
  const running = inflight.get(key);
  if (running) return running;
  const job = (async () => {
    const stored = await readStored(key);
    if (stored) { memory.set(key, stored); return stored; }
    const values = track.musicId !== undefined
      ? await computeFromChunks(track, key, signal)
      : await computeFromLocal(track.localAudio!);
    memory.set(key, values);
    void writeStored(key, values);
    return values;
  })();
  inflight.set(key, job);
  job.finally(() => inflight.delete(key)).catch(() => {});
  return job;
}

/**
 * 화면용 훅. 파형이 필요한 화면(순간 저장 등)에서만 부른다 — 부르는 순간 계산이
 * 시작되기 때문이다. 받는 도중에는 받은 데까지의 파형과 진행률을 준다.
 */
export function useTrackWaveform(track: Track | null | undefined): WaveformProgress {
  const [state, setState] = useState<WaveformProgress>({ values: null, progress: 0, failed: false });
  const key = track ? keyOf(track) : null;
  const served = track?.waveform && track.waveform.length > 1 ? track.waveform : null;
  useEffect(() => {
    if (!track) { setState({ values: null, progress: 0, failed: false }); return; }
    if (served) { setState({ values: served, progress: 1, failed: false }); return; }
    if (!key) { setState({ values: null, progress: 1, failed: true }); return; }
    const cached = memory.get(key);
    if (cached) { setState({ values: cached, progress: 1, failed: false }); return; }
    setState({ values: null, progress: 0, failed: false });
    const controller = new AbortController();
    const onProgress = (p: WaveformProgress) => setState(p);
    if (!listeners.has(key)) listeners.set(key, new Set());
    listeners.get(key)!.add(onProgress);
    loadTrackWaveform(track, controller.signal)
      .then(values => { if (!controller.signal.aborted) setState({ values, progress: 1, failed: values === null }); })
      .catch(error => { if (!controller.signal.aborted && !(error instanceof DOMException && error.name === 'AbortError')) setState({ values: null, progress: 1, failed: true }); });
    return () => {
      listeners.get(key)?.delete(onProgress);
      // 다른 화면이 같은 곡을 기다리고 있으면 받던 것을 끊지 않는다.
      if (!listeners.get(key)?.size) controller.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- key 와 served 로 충분하다
  }, [key, served]);
  return state;
}
