/**
 * 청크 스트리밍 플레이어 엔진.
 *
 * 흐름:
 *   스트림 조회 → 청크 메타 → (네트워크) → 링버퍼 → Opus 디코딩 → AudioBuffer
 *   → AudioContext 에 순서대로 예약
 *
 * 설계상 중요한 점 몇 가지.
 *
 * - **AudioContext 는 앱이 뜨는 순간 만든다.** 브라우저는 사용자 제스처 없이
 *   소리를 내지 못하게 막지만 컨텍스트 생성 자체는 막지 않는다. 미리 만들어
 *   두면 첫 재생에서 `resume()` 만 하면 되어 지연이 눈에 띄지 않는다.
 * - **재생이 끝난 버퍼도 버리지 않는다.** 사용자가 뒤로 Seek 하면 다시
 *   받아야 하기 때문이다. 곡이 바뀔 때만 통째로 비운다.
 * - **다음 곡의 앞 5초는 미리 받아 둔다.** 곡이 넘어가는 순간 네트워크를
 *   기다리면 무음이 생긴다.
 * - 예약해 둔 오디오가 떨어지면 재생을 멈추고 `buffering` 으로 바꾼다.
 *   화면은 이 상태를 보고 스피너를 띄운다.
 */

import { createStore, type Store } from '../store';
import { api } from '../api/session';
import type { ChunkInfo, StreamInfo } from '../api/endpoints';
import { parseChunk } from './rtp';
import { decodeOpusFrames, isOpusDecodingSupported, OPUS_SAMPLE_RATE } from './decoder';
import { RingBuffer } from './ring-buffer';

export type PlayerStatus =
  | 'idle'
  | 'loading'
  /** 재생하려는 지점의 버퍼가 아직 없다. 화면은 스피너. */
  | 'buffering'
  | 'playing'
  | 'paused'
  | 'ended'
  | 'error';

export type PlayerState = {
  status: PlayerStatus;
  musicId: number | null;
  positionSec: number;
  durationSec: number;
  /** 현재 지점부터 끊김 없이 재생 가능한 마지막 시각(초). */
  bufferedSec: number;
  volume: number;
  error: string | null;
  /** WebCodecs Opus 디코딩이 가능한 브라우저인가. */
  supported: boolean;
};

/** 재생 중 유지할 예약 길이. 짧으면 끊기고 길면 Seek 반응이 굼떠진다. */
const SCHEDULE_AHEAD_SEC = 6;
/** 다음 곡에서 미리 받아 둘 길이. */
const PRELOAD_HEAD_SEC = 5;
/** 수신 ↔ 디코딩 사이 완충. 청크 하나가 6초라 8개면 약 48초. */
const RING_CAPACITY = 8;
const FADE_SEC = 0.12;

type ChunkSlot = ChunkInfo & { startSec: number; endSec: number };

type TrackSource = {
  musicId: number;
  stream: StreamInfo;
  chunks: ChunkSlot[];
  durationSec: number;
  channels: number;
  /** 디코딩이 끝난 PCM. Seek 를 위해 재생 뒤에도 남겨 둔다. */
  decoded: Map<number, AudioBuffer>;
  /** 지금 받는 중이거나 링버퍼에 들어 있는 청크. 중복 요청을 막는다. */
  inflight: Set<number>;
};

const isAbort = (error: unknown) =>
  error instanceof DOMException && error.name === 'AbortError';

export class PlayerEngine {
  readonly store: Store<PlayerState>;

  private context: AudioContext | null = null;
  private fadeGain: GainNode | null = null;
  private volumeGain: GainNode | null = null;

  private source: TrackSource | null = null;
  /** 대기열 곡의 앞부분만 담아 둔다. 곡이 바뀌면 여기서 꺼내 쓴다. */
  private readonly preloaded = new Map<number, TrackSource>();
  private queue: number[] = [];

  /** 네트워크에서 막 받은 청크 바이트. 디코더가 하나씩 꺼내 간다. */
  private readonly ring = new RingBuffer<{ index: number; bytes: ArrayBuffer }>(
    RING_CAPACITY,
  );
  private draining = false;

  private playing: AudioBufferSourceNode[] = [];
  /** 이 시각(컨텍스트 시계)에 `anchorPosition` 이 들린다. */
  private anchorCtxTime = 0;
  private anchorPosition = 0;
  private nextIndex = 0;
  private nextCtxTime = 0;

  private ticker: ReturnType<typeof setInterval> | null = null;
  private loadToken = 0;
  private abort: AbortController | null = null;

  constructor() {
    this.store = createStore<PlayerState>({
      status: 'idle',
      musicId: null,
      positionSec: 0,
      durationSec: 0,
      bufferedSec: 0,
      volume: 1,
      error: null,
      supported: isOpusDecodingSupported(),
    });
  }

  // ─────────────────────────────────────────────────────────────
  // 오디오 컨텍스트
  // ─────────────────────────────────────────────────────────────

  /** 앱이 뜨자마자 부른다. 소리는 아직 나지 않지만 그래프는 준비된다. */
  prepare(): void {
    if (this.context || typeof window === 'undefined') return;
    const Ctor =
      window.AudioContext ??
      (window as unknown as { webkitAudioContext?: typeof AudioContext })
        .webkitAudioContext;
    if (!Ctor) {
      this.fail('이 브라우저는 Web Audio 를 지원하지 않아요.');
      return;
    }
    const context = new Ctor({ sampleRate: OPUS_SAMPLE_RATE });
    // 페이드용과 볼륨용을 나눈다. 섞어 쓰면 페이드 도중 볼륨을 바꿀 때
    // 서로의 램프를 덮어써 소리가 튄다.
    const fade = context.createGain();
    const volume = context.createGain();
    fade.gain.value = 1;
    volume.gain.value = this.store.get().volume;
    fade.connect(volume).connect(context.destination);
    this.context = context;
    this.fadeGain = fade;
    this.volumeGain = volume;
  }

  /** 사용자 제스처 안에서 불러야 실제로 소리가 난다. */
  private async resumeContext(): Promise<AudioContext> {
    this.prepare();
    const context = this.context;
    if (!context) throw new Error('오디오 컨텍스트를 만들지 못했어요.');
    if (context.state === 'suspended') await context.resume();
    return context;
  }

  // ─────────────────────────────────────────────────────────────
  // 공개 API
  // ─────────────────────────────────────────────────────────────

  setVolume(value: number) {
    const volume = Math.min(1, Math.max(0, value));
    this.store.set({ volume });
    if (this.volumeGain && this.context) {
      this.volumeGain.gain.setTargetAtTime(volume, this.context.currentTime, 0.02);
    }
  }

  /** 다음에 재생할 곡들. 앞 5초를 미리 받아 둔다. */
  setQueue(musicIds: number[]) {
    this.queue = musicIds.slice(0, 4);
    for (const id of this.preloaded.keys()) {
      if (!this.queue.includes(id)) this.preloaded.delete(id);
    }
    void this.preloadQueue();
  }

  /**
   * 곡을 연다. 이미 다른 곡이 재생 중이면 페이드 아웃으로 끊고 넘어간다.
   * 스트림이 없으면(아직 인코딩 전) 오류 상태가 된다.
   */
  async load(musicId: number, options: { autoplay?: boolean } = {}) {
    if (this.source?.musicId === musicId) {
      if (options.autoplay) await this.play();
      return;
    }
    const token = ++this.loadToken;
    await this.fadeOut();
    this.stopSources();
    this.abort?.abort();
    this.abort = new AbortController();
    this.ring.clear();
    // 곡이 바뀌면 이전 곡의 PCM 은 통째로 버린다. 메모리를 잡아먹고,
    // 돌아올 때는 어차피 처음부터 다시 받는 편이 단순하다.
    this.source = null;

    this.store.set({
      status: 'loading',
      musicId,
      positionSec: 0,
      durationSec: 0,
      bufferedSec: 0,
      error: null,
    });

    try {
      const source =
        this.preloaded.get(musicId) ??
        (await this.openSource(musicId, this.abort.signal));
      if (token !== this.loadToken) return;
      this.preloaded.delete(musicId);
      this.source = source;
      this.anchorPosition = 0;
      this.nextIndex = 0;
      this.store.set({
        durationSec: source.durationSec,
        status: options.autoplay ? 'buffering' : 'paused',
      });
      void this.fill();
      if (options.autoplay) await this.play();
    } catch (error) {
      if (token !== this.loadToken || isAbort(error)) return;
      this.fail(
        error instanceof Error ? error.message : '음원을 불러오지 못했어요.',
      );
    }
  }

  async play() {
    const source = this.source;
    if (!source) return;
    const state = this.store.get();
    if (state.status === 'playing') return;
    if (!state.supported) {
      this.fail('이 브라우저는 Opus 스트리밍 재생을 지원하지 않아요.');
      return;
    }
    await this.resumeContext();
    this.startAt(this.anchorPosition);
  }

  pause() {
    if (this.store.get().status !== 'playing') return;
    this.anchorPosition = this.currentPosition();
    this.stopSources();
    this.stopTicker();
    this.store.set({ status: 'paused', positionSec: this.anchorPosition });
  }

  async seek(positionSec: number) {
    const source = this.source;
    if (!source) return;
    const target = Math.min(
      Math.max(0, positionSec),
      Math.max(0, source.durationSec - 0.05),
    );
    const wasPlaying = this.store.get().status === 'playing';
    await this.fadeOut();
    this.stopSources();
    this.anchorPosition = target;
    this.store.set({ positionSec: target });
    void this.fill();
    if (wasPlaying) {
      await this.resumeContext();
      this.startAt(target);
    } else {
      this.store.set({ status: 'paused' });
      this.fadeIn();
    }
  }

  /** 곡을 닫고 그래프를 비운다. 컨텍스트는 살려 둔다(다음 재생이 빨라진다). */
  stop() {
    this.loadToken += 1;
    this.abort?.abort();
    this.stopSources();
    this.stopTicker();
    this.ring.clear();
    this.source = null;
    this.store.set({
      status: 'idle',
      musicId: null,
      positionSec: 0,
      durationSec: 0,
      bufferedSec: 0,
      error: null,
    });
  }

  // ─────────────────────────────────────────────────────────────
  // 스케줄링
  // ─────────────────────────────────────────────────────────────

  private startAt(position: number) {
    const source = this.source;
    const context = this.context;
    if (!source || !context) return;

    const index = this.indexAt(position);
    if (index < 0) {
      this.store.set({ status: 'ended', positionSec: source.durationSec });
      return;
    }
    const buffer = source.decoded.get(index);
    if (!buffer) {
      // 아직 못 받은 지점이다. 받는 즉시 이어서 시작한다.
      this.store.set({ status: 'buffering' });
      void this.fill();
      return;
    }

    const slot = source.chunks[index];
    const when = context.currentTime + 0.05;
    this.anchorCtxTime = when;
    this.anchorPosition = position;
    this.nextIndex = index + 1;
    this.nextCtxTime = when + (slot.endSec - position);
    this.schedule(buffer, when, position - slot.startSec);

    this.store.set({ status: 'playing' });
    this.fadeIn();
    this.startTicker();
    this.pump();
  }

  private schedule(buffer: AudioBuffer, when: number, offset: number) {
    const context = this.context;
    if (!context || !this.fadeGain) return;
    const node = context.createBufferSource();
    node.buffer = buffer;
    node.connect(this.fadeGain);
    node.start(when, Math.max(0, offset));
    node.onended = () => {
      this.playing = this.playing.filter((item) => item !== node);
    };
    this.playing.push(node);
  }

  /** 예약이 모자라면 채우고, 데이터가 없으면 버퍼링으로 떨어뜨린다. */
  private pump() {
    const source = this.source;
    const context = this.context;
    if (!source || !context) return;
    if (this.store.get().status !== 'playing') return;

    while (
      this.nextIndex < source.chunks.length &&
      this.nextCtxTime < context.currentTime + SCHEDULE_AHEAD_SEC
    ) {
      const buffer = source.decoded.get(this.nextIndex);
      if (!buffer) {
        // 예약해 둔 소리가 아직 남아 있으면 조금 더 기다려 본다.
        if (this.nextCtxTime > context.currentTime + 0.25) break;
        this.anchorPosition = source.chunks[this.nextIndex].startSec;
        this.stopSources();
        this.store.set({ status: 'buffering', positionSec: this.anchorPosition });
        void this.fill();
        return;
      }
      this.schedule(buffer, this.nextCtxTime, 0);
      this.nextCtxTime += source.chunks[this.nextIndex].endSec -
        source.chunks[this.nextIndex].startSec;
      this.nextIndex += 1;
    }
    void this.fill();
  }

  private currentPosition(): number {
    const source = this.source;
    const context = this.context;
    if (!source) return 0;
    if (this.store.get().status !== 'playing' || !context) {
      return this.anchorPosition;
    }
    return Math.min(
      source.durationSec,
      this.anchorPosition + (context.currentTime - this.anchorCtxTime),
    );
  }

  private indexAt(position: number): number {
    const source = this.source;
    if (!source) return -1;
    return source.chunks.findIndex(
      (slot) => position >= slot.startSec && position < slot.endSec,
    );
  }

  private startTicker() {
    this.stopTicker();
    this.ticker = setInterval(() => {
      const source = this.source;
      if (!source) return;
      const position = this.currentPosition();
      this.store.set({ positionSec: position, bufferedSec: this.bufferedUntil() });
      if (position >= source.durationSec - 0.05) {
        this.stopSources();
        this.stopTicker();
        this.anchorPosition = source.durationSec;
        this.store.set({ status: 'ended', positionSec: source.durationSec });
        return;
      }
      this.pump();
    }, 200);
  }

  private stopTicker() {
    if (this.ticker) clearInterval(this.ticker);
    this.ticker = null;
  }

  private stopSources() {
    for (const node of this.playing) {
      node.onended = null;
      try {
        node.stop();
      } catch {
        /* 아직 시작 전이면 예외가 난다 */
      }
      node.disconnect();
    }
    this.playing = [];
  }

  /** 현재 위치부터 끊김 없이 이어지는 마지막 시각. */
  private bufferedUntil(): number {
    const source = this.source;
    if (!source) return 0;
    let index = Math.max(0, this.indexAt(this.currentPosition()));
    let until = source.chunks[index]?.startSec ?? 0;
    while (index < source.chunks.length && source.decoded.has(index)) {
      until = source.chunks[index].endSec;
      index += 1;
    }
    return until;
  }

  // ─────────────────────────────────────────────────────────────
  // 페이드
  // ─────────────────────────────────────────────────────────────

  private async fadeOut(): Promise<void> {
    const gain = this.fadeGain;
    const context = this.context;
    if (!gain || !context || this.playing.length === 0) return;
    const now = context.currentTime;
    gain.gain.cancelScheduledValues(now);
    gain.gain.setValueAtTime(gain.gain.value, now);
    gain.gain.linearRampToValueAtTime(0.0001, now + FADE_SEC);
    await new Promise((resolve) => setTimeout(resolve, FADE_SEC * 1000));
  }

  private fadeIn() {
    const gain = this.fadeGain;
    const context = this.context;
    if (!gain || !context) return;
    const now = context.currentTime;
    gain.gain.cancelScheduledValues(now);
    gain.gain.setValueAtTime(0.0001, now);
    gain.gain.linearRampToValueAtTime(1, now + FADE_SEC);
  }

  // ─────────────────────────────────────────────────────────────
  // 수신 · 디코딩
  // ─────────────────────────────────────────────────────────────

  /** 곡의 스트림과 청크 메타를 받아 재생 단위를 만든다. */
  private async openSource(
    musicId: number,
    signal: AbortSignal,
  ): Promise<TrackSource> {
    const streams = await api.stream.listByMusic(musicId, signal);
    if (streams.length === 0) {
      throw new Error('아직 재생할 수 있는 음원이 없어요.');
    }
    // 완료된 스트림만 내려온다. 비트레이트가 가장 높은 것을 고른다.
    const stream = streams.reduce((best, item) =>
      item.bitrate_kbps > best.bitrate_kbps ? item : best,
    );
    const chunks = await api.stream.listChunks(stream.stream_id, signal);
    if (chunks.length === 0) throw new Error('청크 정보를 받지 못했어요.');

    let cursor = 0;
    const slots: ChunkSlot[] = chunks
      .slice()
      .sort((a, b) => a.index - b.index)
      .map((chunk) => {
        const startSec = cursor;
        cursor += chunk.duration_ms / 1000;
        return { ...chunk, startSec, endSec: cursor };
      });

    return {
      musicId,
      stream,
      chunks: slots,
      durationSec: cursor,
      // 서버 메타에 채널 수가 없다. 청킹 파이프라인이 스테레오로 굽는다.
      channels: 2,
      decoded: new Map(),
      inflight: new Set(),
    };
  }

  /** 지금 필요한 구간을 링버퍼에 채운다. */
  private async fill() {
    const source = this.source;
    if (!source) return;
    const from = Math.max(0, this.indexAt(this.currentPosition()));
    const untilSec = this.currentPosition() + SCHEDULE_AHEAD_SEC * 2;

    for (let index = from; index < source.chunks.length; index += 1) {
      if (source.chunks[index].startSec > untilSec) break;
      if (source.decoded.has(index) || source.inflight.has(index)) continue;
      if (this.ring.isFull) break;
      source.inflight.add(index);
      void this.receive(source, index);
    }
  }

  private async receive(source: TrackSource, index: number) {
    try {
      const bytes = await api.stream.downloadChunk(
        source.stream.stream_id,
        index,
        this.abort?.signal,
      );
      if (this.source !== source && !this.preloaded.has(source.musicId)) return;
      if (!this.ring.push({ index, bytes })) {
        // 링버퍼가 가득 찼다. 이 청크는 잊고 나중에 다시 요청한다.
        source.inflight.delete(index);
        return;
      }
      void this.drain(source);
    } catch (error) {
      source.inflight.delete(index);
      if (isAbort(error)) return;
      // 한 청크 실패로 곡 전체를 죽이지 않는다. 다음 fill 에서 다시 받는다.
      if (this.source === source && this.store.get().status === 'buffering') {
        this.fail(
          error instanceof Error ? error.message : '음원을 받지 못했어요.',
        );
      }
    }
  }

  /** 링버퍼에서 하나씩 꺼내 PCM 으로 바꾼다. 한 번에 하나만 돈다. */
  private async drain(source: TrackSource) {
    if (this.draining) return;
    this.draining = true;
    try {
      this.prepare();
      const context = this.context;
      if (!context) return;
      for (;;) {
        const item = this.ring.shift();
        if (!item) break;
        const slot = source.chunks[item.index];
        if (!slot) continue;
        try {
          const { frames } = parseChunk(item.bytes);
          const buffer = await decodeOpusFrames(context, frames, {
            channels: source.channels,
            frameDurationMs: 20,
            expectedSamples: Math.round(
              (slot.duration_ms / 1000) * OPUS_SAMPLE_RATE,
            ),
          });
          source.decoded.set(item.index, buffer);
        } catch (error) {
          if (this.source === source) {
            this.fail(
              error instanceof Error
                ? error.message
                : '음원을 디코딩하지 못했어요.',
            );
          }
        } finally {
          source.inflight.delete(item.index);
        }
      }
    } finally {
      this.draining = false;
    }
    // 버퍼링 때문에 멈춰 있었다면 여기서 이어서 시작한다.
    if (this.source === source && this.store.get().status === 'buffering') {
      this.store.set({ bufferedSec: this.bufferedUntil() });
      if (source.decoded.has(Math.max(0, this.indexAt(this.anchorPosition)))) {
        void this.play();
      }
    }
    void this.fill();
  }

  /** 대기열 곡의 앞부분을 미리 받아 둔다. */
  private async preloadQueue() {
    for (const musicId of this.queue) {
      if (this.preloaded.has(musicId) || this.source?.musicId === musicId) {
        continue;
      }
      try {
        const source = await this.openSource(musicId, new AbortController().signal);
        this.preloaded.set(musicId, source);
        this.prepare();
        const context = this.context;
        if (!context) return;
        for (const slot of source.chunks) {
          if (slot.startSec >= PRELOAD_HEAD_SEC) break;
          const bytes = await api.stream.downloadChunk(
            source.stream.stream_id,
            slot.index,
          );
          const { frames } = parseChunk(bytes);
          source.decoded.set(
            slot.index,
            await decodeOpusFrames(context, frames, {
              channels: source.channels,
              frameDurationMs: 20,
              expectedSamples: Math.round(
                (slot.duration_ms / 1000) * OPUS_SAMPLE_RATE,
              ),
            }),
          );
        }
      } catch {
        // 미리 받기는 실패해도 재생에 지장이 없다. 조용히 넘어간다.
        this.preloaded.delete(musicId);
      }
    }
  }

  private fail(message: string) {
    this.stopSources();
    this.stopTicker();
    this.store.set({ status: 'error', error: message });
  }
}

let engine: PlayerEngine | null = null;

/** 브라우저에서만 만들어진다. SSR 에서는 null 을 돌려준다. */
export function getPlayer(): PlayerEngine | null {
  if (typeof window === 'undefined') return null;
  if (!engine) {
    engine = new PlayerEngine();
    engine.prepare();
  }
  return engine;
}
