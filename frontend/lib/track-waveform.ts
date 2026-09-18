/** 사용자 기기에서 음원 청크를 디코딩하고 계산한 부분부터 파형을 표시한다.
 * 서버 파형·배포 JSON·이전 세션의 저장된 파형은 사용하지 않는다. */
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
  /** 0~1. 현재 기기에서 분석한 청크의 비율. */
  progress: number;
  /** 계산할 수 없는 곡(스트림 없음, 디코더 미지원, 실패). */
  failed: boolean;
};

/** 앞 청크의 마지막 프레임 몇 개를 디코더 워밍업에 쓴다. `engine.ts` 의 PRE_ROLL_FRAMES 와 같다. */
const PRE_ROLL_FRAMES = 24;
/** 한 번에 받아 두는 청크 수. 디코드는 순서대로 하지만 다운로드는 조금 앞서 간다. */
const AHEAD = 2;
const keyOf = (track: Track) => track.musicId !== undefined ? `music:${track.musicId}` : track.localAudio ? `local:${track.localAudio}` : null;

// ── 계산 ────────────────────────────────────────────────────────────────────
const normalize = (energy: Float64Array, count: Float64Array): number[] => {
  const rms = Array.from(energy, (e, i) => count[i] > 0 ? Math.sqrt(e / count[i]) : 0);
  const peak = Math.max(...rms, 1e-6);
  return rms.map(v => v / peak);
};

async function computeFromChunks(track: Track, onProgress: (value: WaveformProgress) => void, signal: AbortSignal): Promise<number[]> {
  if (!isOpusDecodingSupported()) throw new Error('unsupported');
  const musicId = track.musicId!;
  const streams = await api.stream.listByMusic(musicId, signal);
  if (!streams.length) throw new Error('no-stream');
  const stream = streams.reduce((best, s) => s.bitrate_kbps > best.bitrate_kbps ? s : best);
  const chunks = (await api.stream.listChunks(stream.stream_id, signal)).slice().sort((a, b) => a.index - b.index);
  if (!chunks.length) throw new Error('no-chunks');
  const totalSamples = chunks.reduce((sum, c) => sum + Math.round(c.duration_ms / 1000 * OPUS_SAMPLE_RATE), 0);
  if (!Number.isFinite(totalSamples) || totalSamples <= 0) throw new Error('invalid-duration');
  const energy = new Float64Array(WAVEFORM_SAMPLES);
  const count = new Float64Array(WAVEFORM_SAMPLES);
  // 디코더가 만든 AudioBuffer 를 담을 그릇. 소리를 내지 않으니 오프라인으로.
  const context = new OfflineAudioContext(2, 1, OPUS_SAMPLE_RATE);
  const downloads = new Map<number, Promise<ArrayBuffer>>();
  const fetchAt = (i: number) => {
    if (i >= chunks.length || downloads.has(i)) return;
    const request = api.stream.downloadChunk(stream.stream_id, chunks[i].index, signal);
    // 선행 다운로드가 먼저 실패해도 처리되지 않은 rejection을 만들지 않는다.
    void request.catch(() => {});
    downloads.set(i, request);
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
    if (signal.aborted) throw new DOMException('aborted', 'AbortError');
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
    onProgress({ values: normalize(energy, count), progress: (i + 1) / chunks.length, failed: false });
  }
  return normalize(energy, count);
}

async function computeFromLocal(url: string): Promise<number[]> {
  // `loadAudioWaveform` 은 4~92 로 펼친 높이를 준다. 0~1 로 되돌린다.
  const heights = await loadAudioWaveform(url, WAVEFORM_SAMPLES);
  return heights.map(h => Math.max(0, Math.min(1, (h - 4) / 88)));
}

/** 매 호출마다 현재 기기에서 새로 분석한다. */
export async function loadTrackWaveform(track: Track, signal: AbortSignal, onProgress: (value: WaveformProgress) => void = () => {}): Promise<number[] | null> {
  if (signal.aborted) throw new DOMException('aborted', 'AbortError');
  if (track.musicId !== undefined) return computeFromChunks(track, onProgress, signal);
  if (track.localAudio) return computeFromLocal(track.localAudio);
  return null;
}

const empty: WaveformProgress = { values: null, progress: 0, failed: false };
export function useTrackWaveform(track: Track | null | undefined): WaveformProgress {
  const key = track ? keyOf(track) : null;
  const [state, setState] = useState<{ key: string | null; value: WaveformProgress }>({ key: null, value: empty });
  useEffect(() => {
    if (!track || !key) { setState({ key, value: { ...empty, progress: 1, failed: !!track } }); return; }
    const controller = new AbortController();
    const publish = (value: WaveformProgress) => {
      if (!controller.signal.aborted) setState({ key, value });
    };
    publish(empty);
    void loadTrackWaveform(track, controller.signal, publish)
      .then(values => publish({ values, progress: 1, failed: values === null }))
      .catch(() => {
        publish({ values: null, progress: 1, failed: true });
        controller.abort();
      });
    return () => controller.abort();
    // 곡 ID 또는 로컬 음원 주소가 바뀔 때만 다시 분석한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  return state.key === key ? state.value : empty;
}
