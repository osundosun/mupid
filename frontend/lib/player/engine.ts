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
import { localDemoEnabled } from '../api/local-demo';
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

/**
 * 막대 위치를 실제 이득으로 바꾼다.
 *
 * 사람은 소리 크기를 로그로 느낀다. 위치를 그대로 이득에 넣으면 **위쪽 절반은
 * 줄여도 거의 그대로**고(1.0 → 0.5 는 -6dB 뿐이다) 아래쪽 몇 칸에서 갑자기
 * 사라진다. 그래서 같은 거리를 움직여도 어떤 구간이냐에 따라 변화가 딴판이라
 * 조절이 겉돈다. 제곱을 취하면 눈금과 귀가 얼추 맞는다.
 */
const gainFor = (position: number) => position * position;

/** 처음 볼륨. 막대 위치 기준이다. */
export const DEFAULT_VOLUME = 0.65;

/** 재생 중 유지할 예약 길이. 짧으면 끊기고 길면 Seek 반응이 굼떠진다. */
const SCHEDULE_AHEAD_SEC = 6;
/** 다음 곡에서 미리 받아 둘 길이. */
const PRELOAD_HEAD_SEC = 5;
/** 수신 ↔ 디코딩 사이 완충. 청크 하나가 6초라 8개면 약 48초. */
const RING_CAPACITY = 8;
const FADE_SEC = 0.12;
/** 재생을 끊을 때 소리를 줄이는 시간. 파형을 수직으로 자르면 딸깍 소리가 난다. */
const STOP_FADE_SEC = 0.01;
/**
 * 청크를 풀기 전에 디코더를 덥히는 데 쓸 앞 청크 프레임 수. 20ms × 24 = 480ms.
 *
 * RFC 7845 는 Seek 때 80ms 를 권하지만, 그걸로는 이음새가 들린다. 실측하면
 * 이 음원의 디코더 상태는 훨씬 천천히 수렴한다 — 같은 구간을 끊지 않고
 * 해독한 파형과의 오차(RMS, 신호 RMS 는 0.02~0.08):
 *
 *   프리롤   80ms   160ms    240ms     320ms     480ms
 *   오차   0.0020  0.00014  0.0000067  0.0000005  0  (비트 단위로 동일)
 *
 * 80ms 는 신호의 5% 까지 어긋나 조용한 대목에서 또렷이 들린다. 480ms 면
 * 이어붙인 자리가 원본과 완전히 같아진다. 값은 청크 하나(300프레임)의 8% 라
 * 디코딩 비용도 그만큼만 는다.
 */
const PRE_ROLL_FRAMES = 24;
/** 꼬리를 들고 있을 청크 수. 받는 순서가 조금 뒤섞여도 프리롤이 남아 있게 한다. */
const TAIL_KEEP = 4;
/**
 * 청크를 못 받았을 때 다시 받기까지 기다리는 시간. 시도할수록 늘린다.
 *
 * 곧바로 다시 부르면 안 된다 — `receive` 실패가 `drain` → `fill` → `receive`
 * 를 다시 부르는 구조라, 서버가 잠깐 흔들리는 동안 같은 청크를 쉬지 않고
 * 두드리며 스스로 회복을 방해한다 (실측: 1초에 17번).
 */
const RETRY_BASE_MS = 400;
const RETRY_MAX_MS = 4000;
/**
 * 연속 실패가 이만큼 쌓이면 화면에 사정을 알린다. **재시도는 멈추지 않는다.**
 *
 * 네트워크 실패로 재생을 `error` 로 끝내면 안 된다 — `fail()` 은 티커를 멈추고
 * status 를 'error' 로 바꾸는데, 그러면 `pump` 도 `drain` 의 복구 조건도 다시는
 * 성립하지 않아 **회선이 돌아와도 그 곡은 영영 재개되지 않는다.** 실측으로
 * 확인했다: nginx 를 36초 멈췄다 되살렸더니 이후 70초 동안 예약이 0건이었다.
 */
const RETRY_NOTICE = 6;

/**
 * 프리롤이 될 앞 청크를 기다려 주는 한도.
 *
 * 같이 요청한 둘이라 보통은 엇비슷하게 도착한다. 한쪽 응답만 매달리는 일은
 * 모바일 회선에서 드물지 않은데, 그때까지 소리를 미루면 **잡음 한 번을 피하려고
 * 정적을 몇 초씩 듣는 셈**이 된다. 한도를 넘기면 프리롤 없이 푼다.
 */
const PRE_ROLL_WAIT_SEC = 2;

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
  /**
   * 청크마다 마지막 프레임 몇 개. 다음 청크를 풀 때 프리롤로 쓴다.
   *
   * 청크 본문은 들고 있지 않는다 — 프레임은 원본 버퍼를 가리키는 뷰라서,
   * 그대로 두면 127KB 짜리 청크가 통째로 메모리에 남는다. 복사해서 담는다.
   */
  frameTails: Map<number, Uint8Array[]>;
  /**
   * 받아는 놨지만 아직 풀지 않은 청크의 프레임과, 그렇게 기다리기 시작한 시각.
   *
   * 앞 청크가 아직 오는 중이면 여기서 기다린다 — 그 꼬리가 있어야 프리롤이
   * 붙기 때문이다. 자세한 이야기는 `drain` 에 있다.
   */
  pending: Map<number, { frames: Uint8Array[]; since: number }>;
  /** 연속으로 못 받은 횟수. 하나라도 성공하면 0 으로 돌아간다. */
  failures: number;
  /** 이 시각(Date.now 기준) 전에는 다시 받지 않는다. 실패 뒤 물러서기. */
  retryAfter: number;
  /**
   * 프리롤을 얻으려고 받아 본 청크. 재생에 꼭 필요한 청크가 아니라서
   * **한 번만 시도한다** — 실패한 것을 계속 다시 받으면, 소리는 멀쩡히
   * 나는 채로 회선만 갉아먹는다. 그 이음새는 프리롤 없이 간다.
   */
  tailTried: Set<number>;
};

const isAbort = (error: unknown) =>
  error instanceof DOMException && error.name === 'AbortError';

export class PlayerEngine {
  readonly store: Store<PlayerState>;

  private context: AudioContext | null = null;
  /*
   * 로컬 데모 재생.
   *
   * 청크 스트리밍(`load`)과 완전히 다른 길이다. 서버 음악 번호가 없는 곡은
   * `<audio>` 로 바로 틀고, 나머지 조작(볼륨·탐색·정지)은 아래 메서드들이
   * 스트리밍 경로보다 먼저 이쪽을 본다.
   */
  private localAudio: HTMLAudioElement | null = null;
  private localSource: MediaElementAudioSourceNode | null = null;

  async loadLocal(id: number, url: string, duration: number) {
    if (!localDemoEnabled() || !url.startsWith('/local-demo/audio/')) {
      throw new Error('로컬 재생은 로컬 데모에서만 사용할 수 있어요.');
    }
    this.stop();
    const audio = new Audio(url);
    this.localAudio = audio;
    audio.volume = gainFor(this.store.get().volume);
    this.store.set({ musicId: -id, status: 'loading', durationSec: duration, supported: true });
    const update = () => {
      if (this.localAudio !== audio) return;
      this.store.set({
        positionSec: audio.currentTime,
        durationSec: Number.isFinite(audio.duration) ? audio.duration : duration,
        bufferedSec: audio.buffered.length ? audio.buffered.end(audio.buffered.length - 1) : 0,
      });
    };
    for (const event of ['timeupdate', 'durationchange', 'progress', 'seeked']) {
      audio.addEventListener(event, update);
    }
    for (const [event, status] of [
      ['playing', 'playing'], ['pause', 'paused'], ['waiting', 'buffering'], ['ended', 'ended'],
    ] as const) {
      audio.addEventListener(event, () => {
        if (this.localAudio === audio) { update(); this.store.set({ status }); }
      });
    }
    audio.addEventListener('error', () => {
      if (this.localAudio === audio) this.store.set({ status: 'error', error: '로컬 음원을 불러오지 못했어요.' });
    });
    try {
      const context = await this.resumeContext();
      if (this.localAudio !== audio) return;
      this.localSource = context.createMediaElementSource(audio);
      this.localSource.connect(context.destination);
      await audio.play();
    }
    catch (error) {
      if (this.localAudio === audio) {
        this.store.set({ status: 'error', error: '재생 버튼을 다시 눌러 주세요.' });
        throw error;
      }
    }
  }
  private fadeGain: GainNode | null = null;
  private volumeGain: GainNode | null = null;

  private source: TrackSource | null = null;
  /** 대기열 곡의 앞부분만 담아 둔다. 곡이 바뀌면 여기서 꺼내 쓴다. */
  private readonly preloaded = new Map<number, TrackSource>();
  private queue: number[] = [];

  /** 네트워크에서 막 받은 청크 바이트. 디코더가 하나씩 꺼내 간다. */
  private readonly ring = new RingBuffer<{ index: number; frames: Uint8Array[] }>(
    RING_CAPACITY,
  );
  private seeking = false;
  /** 옮기는 동안 새로 지정된 목적지. 막대를 끌면 쉬지 않고 들어온다. */
  private seekPending: number | null = null;

  private draining = false;
  /** 디코딩이 도는 동안 새 청크가 들어왔는가. 들어왔으면 한 바퀴 더 돈다. */
  private drainPending = false;

  private playing: AudioBufferSourceNode[] = [];
  /** 이 시각(컨텍스트 시계)에 `anchorPosition` 이 들린다. */
  private anchorCtxTime = 0;
  private anchorPosition = 0;
  private nextIndex = 0;
  private nextCtxTime = 0;

  private ticker: ReturnType<typeof setInterval> | null = null;
  /** 프리롤을 기다리는 청크를 다시 볼 시계. `recheckPending` 이 건다. */
  private pendingTimer: ReturnType<typeof setTimeout> | null = null;
  private loadToken = 0;
  private abort: AbortController | null = null;

  constructor() {
    this.store = createStore<PlayerState>({
      status: 'idle',
      musicId: null,
      positionSec: 0,
      durationSec: 0,
      bufferedSec: 0,
      volume: DEFAULT_VOLUME,
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
    // `latencyHint: 'playback'` 은 **출력 버퍼를 키워 달라는 뜻**이다.
    //
    // 기본값('interactive')으로 만들면 이 기기의 `baseLatency` 가 0.004초,
    // 곧 192프레임이었다. 오디오 스레드가 4ms 안에 다음 조각을 내놓지
    // 못하면 그 자리가 언더런이다 — 하드웨어는 기다려 주지 않으므로 무음이나
    // 직전 데이터를 대신 내보내고, 파형이 수직으로 끊겨 '퍽' 하고 들린다.
    // 안드로이드는 코어 주파수를 낮추거나 스레드를 옮기는 일이 잦고 이 앱은
    // 6초마다 Opus 청크를 푼다. 4ms 여유로는 빠듯하다.
    //
    // 'playback' 이면 1024프레임(약 21ms)으로 다섯 배 넉넉해진다. 음악
    // 재생은 악기나 게임과 달리 출력 지연이 문제되지 않는다 — 예약은
    // 컨텍스트 시계 기준이라 재생 위치 계산도 그대로다.
    const context = new Ctor({
      sampleRate: OPUS_SAMPLE_RATE,
      latencyHint: 'playback',
    });
    // 페이드용과 볼륨용을 나눈다. 섞어 쓰면 페이드 도중 볼륨을 바꿀 때
    // 서로의 램프를 덮어써 소리가 튄다.
    const fade = context.createGain();
    const volume = context.createGain();
    fade.gain.value = 1;
    volume.gain.value = gainFor(this.store.get().volume);
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

  /**
   * 볼륨을 바꾼다. `value` 는 막대 위치(0~1)이고 이득은 `gainFor` 로 굽힌다.
   *
   * 값을 바로 꽂지 않고 `setTargetAtTime` 으로 20ms 에 걸쳐 옮긴다. 바로 꽂으면
   * 파형이 끊겨 '지직' 소리가 난다.
   */
  setVolume(value: number) {
    const volume = Math.min(1, Math.max(0, value));
    this.store.set({ volume });
    if (this.localAudio) this.localAudio.volume = gainFor(volume);
    if (this.volumeGain && this.context) {
      this.volumeGain.gain.setTargetAtTime(
        gainFor(volume),
        this.context.currentTime,
        0.02,
      );
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
    if (this.localAudio) this.stop();
    if (this.source?.musicId === musicId) {
      // 이미 들고 있는 곡이면 다시 받지 않는다. 다만 **끝까지 들은 곡이면
      // 처음으로 되돌린다** — 그러지 않으면 재생 위치가 끝에 붙어 있어
      // 눌러도 아무 일이 없다. 한곡 반복이 안 되던 것이 이것 때문이다.
      if (this.store.get().status === 'ended') this.anchorPosition = 0;
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
    if (this.localAudio) { await this.resumeContext(); await this.localAudio.play(); return; }
    const source = this.source;
    if (!source) return;
    const state = this.store.get();
    if (state.status === 'playing') return;
    if (!state.supported) {
      this.fail('이 브라우저는 Opus 스트리밍 재생을 지원하지 않아요.');
      return;
    }
    await this.resumeContext();
    // 재생할 청크가 없는 자리(=끝)에 서 있으면 처음부터 튼다. 그대로 시작하면
    // 곧바로 `ended` 만 다시 나가서 눌러도 아무 일이 없다. 끝까지 들은 뒤든,
    // 끝에서 멈춰 둔 뒤든 같다.
    const at = this.indexAt(this.anchorPosition) < 0 ? 0 : this.anchorPosition;
    this.startAt(at);
  }

  pause() {
    if (this.localAudio) { this.localAudio.pause(); return; }
    if (this.store.get().status !== 'playing' && this.store.get().status !== 'buffering') return;
    this.anchorPosition = this.currentPosition();
    this.stopSources();
    this.stopTicker();
    this.store.set({ status: 'paused', positionSec: this.anchorPosition });
  }

  /**
   * 재생 위치를 옮긴다.
   *
   * **자리부터 옮기고 소리는 나중에 맞춘다.** 예전에는 페이드아웃을 기다린
   * 뒤에야 위치를 바꿨는데, 그 120ms 동안 티커가 200ms 마다 옛 위치를 그대로
   * 내보내서 화면 바가 누른 자리로 갔다가 원래 자리로 돌아왔다가 다시 튀었다.
   * 티커를 먼저 멈추고 자리를 확정한 뒤에 소리를 맞춘다.
   *
   * 막대를 끄는 동안에는 `change` 가 쉬지 않고 들어온다. 이미 옮기는 중이면
   * 목적지만 갈아 두고 돌고 있는 쪽이 마지막 자리까지 이어서 처리한다 —
   * 여러 번이 한꺼번에 돌면 페이드와 예약이 엇갈린다.
   */
  async seek(positionSec: number) {
    if (this.localAudio) {
      const target = Math.max(0, Math.min(positionSec, this.store.get().durationSec));
      this.localAudio.currentTime = target;
      this.store.set({ positionSec: target });
      return;
    }
    const source = this.source;
    if (!source) return;
    const target = Math.min(
      Math.max(0, positionSec),
      Math.max(0, source.durationSec - 0.05),
    );

    // 화면이 기다리지 않도록 자리는 지금 바로 확정한다.
    this.stopTicker();
    this.anchorPosition = target;
    this.store.set({ positionSec: target });

    this.seekPending = target;
    if (this.seeking) return;
    this.seeking = true;
    // 버퍼를 기다리는 중이어도 사용자에게는 '재생 중'이다. 옮긴 뒤에도 이어서
    // 나야 한다. 끝까지 들은 곡은 그 자리에서 다시 듣겠다는 뜻이니 함께 튼다.
    const status = this.store.get().status;
    const wasPlaying =
      status === 'playing' || status === 'buffering' || status === 'ended';
    try {
      await this.fadeOut();
      this.stopSources();
      for (;;) {
        const to = this.seekPending;
        if (to === null) break;
        this.seekPending = null;
        this.anchorPosition = to;
        this.store.set({ positionSec: to });
        void this.fill();
        if (!wasPlaying) continue;
        await this.resumeContext();
        // 그 사이 또 옮겼으면 소리를 내지 않고 마지막 자리로 간다.
        if (this.seekPending !== null) continue;
        this.startAt(to);
      }
    } finally {
      this.seeking = false;
      this.seekPending = null;
    }
    if (!wasPlaying) {
      this.store.set({ status: 'paused' });
      this.fadeIn();
    }
  }

  /** 곡을 닫고 그래프를 비운다. 컨텍스트는 살려 둔다(다음 재생이 빨라진다). */
  stop() {
    this.localSource?.disconnect();
    this.localSource = null;
    const localAudio = this.localAudio;
    this.localAudio = null;
    if (localAudio) { localAudio.pause(); localAudio.removeAttribute('src'); localAudio.load(); }
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
    // 소리가 실제로 나기 시작하는 시각을 넘긴다. 지금부터 올리면 첫 소리가
    // 이미 절반쯤 올라간 게인으로 튀어나온다.
    this.fadeIn(when);
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

  /**
   * 청크의 프레임을 꺼내고, 다음 청크가 쓸 꼬리를 기억해 둔다.
   *
   * **받는 즉시** 부른다. 디코딩할 때 부르면, 받는 순서와 푸는 순서가 어긋난
   * 사이에 앞 청크의 꼬리가 아직 없을 수 있다 (`fill` 이 여러 청크를 동시에
   * 받는다). 그러면 그 이음새만 프리롤 없이 풀려 소리가 튄다.
   *
   * 여기까지는 **도착한 청크**끼리의 순서 이야기다. 앞 청크가 아직 도착도
   * 하지 않은 경우는 `drain` 이 기다려서 막는다.
   */
  private keepFrames(source: TrackSource, index: number, bytes: ArrayBuffer) {
    const { frames } = parseChunk(bytes);
    source.frameTails.set(
      index,
      // 원본 버퍼를 붙잡지 않도록 복사한다. 뷰를 그대로 두면 청크 하나가
      // 통째로(127KB) 메모리에 남는다.
      frames.slice(-PRE_ROLL_FRAMES).map((frame) => new Uint8Array(frame)),
    );
    // 오래된 꼬리는 버린다. 받는 순서가 조금 뒤섞여도 되도록 몇 개는 남긴다.
    for (const kept of source.frameTails.keys()) {
      if (kept < index - TAIL_KEEP) source.frameTails.delete(kept);
    }
    return frames;
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
      // 울릴 시각이 이미 지났을 수 있다. 화면이 한참 멈춰 있었다는 뜻인데,
      // 모바일 WebView 는 앱이 뒤로 가면 타이머를 재우면서도 오디오 시계는
      // 그대로 굴린다. 지난 시각으로 `start` 를 부르면 그 청크가 **처음부터
      // 지금** 울리고, 뒤 청크들도 줄줄이 같은 순간에 겹쳐 울린다.
      // 늦은 만큼 건너뛰고 울린다 — 타임라인은 그대로 유지된다.
      const length = source.chunks[this.nextIndex].endSec -
        source.chunks[this.nextIndex].startSec;
      const late = context.currentTime - this.nextCtxTime;
      if (late < length) {
        this.schedule(
          buffer,
          late > 0 ? context.currentTime : this.nextCtxTime,
          Math.max(0, late),
        );
      }
      this.nextCtxTime += length;
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
      // 끝까지 울린 뒤에 정리한다. 예전에는 0.05초 먼저 끊었는데, 틱이
      // 200ms 라 실제로는 곡이 끝나기 전 최대 50ms 를 파형 한가운데서
      // 잘라내는 일이 생겼다 — 곡이 끝날 때마다 딸깍거리던 원인이다.
      if (position >= source.durationSec) {
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

  /**
   * 울리고 있는 소리를 멈춘다.
   *
   * 파형 한가운데서 그냥 끊으면 그 자리가 수직 절벽이 되어 딸깍 소리가 난다.
   * 아주 짧게(10ms) 줄인 뒤 멈춘다 — 사람 귀에는 즉시 멈춘 것과 같고, 절벽은
   * 사라진다. 이어서 다시 재생할 때는 `fadeIn` 이 게인을 되돌린다.
   */
  private stopSources() {
    if (this.playing.length === 0) return;
    const context = this.context;
    const gain = this.fadeGain;
    const at = context && gain ? context.currentTime + STOP_FADE_SEC : null;
    if (context && gain && at !== null) {
      gain.gain.cancelScheduledValues(context.currentTime);
      gain.gain.setValueAtTime(gain.gain.value, context.currentTime);
      gain.gain.linearRampToValueAtTime(0.0001, at);
    }
    for (const node of this.playing) {
      node.onended = null;
      try {
        // 멈출 시각을 주면 그때까지는 소리가 이어진다. 위의 램프가 그 사이에
        // 소리를 0 으로 내린다.
        if (at !== null) node.stop(at);
        else node.stop();
      } catch {
        /* 아직 시작 전이면 예외가 난다 */
      }
      // 램프가 끝나기 전에 끊으면 소용이 없으니, 노드는 스스로 끝난 뒤에
      // 정리되게 둔다.
      setTimeout(() => node.disconnect(), STOP_FADE_SEC * 1000 + 50);
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

  /**
   * 소리를 올린다. `at` 은 **소리가 실제로 나기 시작하는 시각**이다.
   *
   * 예약은 `context.currentTime` 이 아니라 조금 뒤(`startAt` 의 `when`)에
   * 걸린다. 그런데도 램프를 지금부터 올리면, 첫 소리가 나는 순간에는 게인이
   * 이미 40% 를 넘어 있다 — 무음에서 -8dB 로 수직으로 뛰는 셈이라 Seek 마다
   * 앞머리가 '툭' 한다. `at` 까지는 눌러 두고 거기서부터 올린다.
   */
  private fadeIn(at?: number) {
    const gain = this.fadeGain;
    const context = this.context;
    if (!gain || !context) return;
    const now = context.currentTime;
    const from = Math.max(at ?? now, now);
    gain.gain.cancelScheduledValues(now);
    gain.gain.setValueAtTime(0.0001, now);
    // 램프는 바로 앞 이벤트에서 출발한다. 이 점을 찍어 두어야 `from` 까지
    // 눌러 두었다가 거기서부터 올라간다.
    if (from > now) gain.gain.setValueAtTime(0.0001, from);
    gain.gain.linearRampToValueAtTime(1, from + FADE_SEC);
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
      frameTails: new Map(),
      pending: new Map(),
      tailTried: new Set(),
      failures: 0,
      retryAfter: 0,
    };
  }

  /** 지금 필요한 구간을 링버퍼에 채운다. */
  private async fill() {
    const source = this.source;
    if (!source) return;
    // 방금 실패했으면 잠깐 쉰다. 이 문을 두지 않으면 실패가 곧바로 다음
    // 요청을 부르고, 그 실패가 또 부른다.
    if (Date.now() < source.retryAfter) return;
    const from = Math.max(0, this.indexAt(this.currentPosition()));
    const untilSec = this.currentPosition() + SCHEDULE_AHEAD_SEC * 2;

    // Seek 로 건너뛴 자리에는 앞 청크의 꼬리가 없다. 이어 듣는 중이면 앞
    // 청크를 이미 받아 뒀으니 이 조건에 걸리지 않고, 건너뛴 자리에서만
    // **앞 청크를 함께 요청해 프리롤을 얻는다.** 둘을 나란히 받으므로 기다림이
    // 두 배가 되지는 않는다. 받아 둔 김에 풀어서 들고 있으면 조금 되감을 때도
    // 바로 들린다 — 꼬리만 떼고 버리기에는 아까운 데이터다.
    if (
      from > 0 &&
      !source.frameTails.has(from - 1) &&
      !source.inflight.has(from - 1) &&
      !source.tailTried.has(from - 1) &&
      // 재생할 청크가 이미 풀려 있으면 프리롤은 쓸 데가 없다.
      !source.decoded.has(from) &&
      this.ring.size + source.pending.size < RING_CAPACITY
    ) {
      source.tailTried.add(from - 1);
      source.inflight.add(from - 1);
      void this.receive(source, from - 1);
    }

    for (let index = from; index < source.chunks.length; index += 1) {
      if (source.chunks[index].startSec > untilSec) break;
      if (source.decoded.has(index) || source.inflight.has(index)) continue;
      // 링버퍼에서 꺼내 `pending` 으로 옮겨 둔 것도 아직 메모리에 있는
      // 청크다. 둘을 합쳐 세야 받아 두는 양이 원래 의도대로 묶인다.
      if (this.ring.size + source.pending.size >= RING_CAPACITY) break;
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
      // 하나라도 받았으면 회선이 돌아온 것이다. 물러서기와 안내를 되돌린다.
      source.failures = 0;
      source.retryAfter = 0;
      if (this.store.get().error) this.store.set({ error: null });
      const frames = this.keepFrames(source, index, bytes);
      if (!this.ring.push({ index, frames })) {
        // 링버퍼가 가득 찼다. 이 청크는 잊고 나중에 다시 요청한다.
        source.inflight.delete(index);
        return;
      }
      void this.drain(source);
    } catch (error) {
      source.inflight.delete(index);
      // 이 청크를 프리롤로 기다리던 다음 청크가 있을 수 있다. 영영 오지
      // 않게 됐으니 다시 깨워 프리롤 없이라도 풀게 한다.
      void this.drain(source);
      if (isAbort(error)) return;
      if (this.source !== source) return;

      // 한 청크 실패로 곡 전체를 죽이지 않는다 — 예전에는 버퍼링 중이면
      // 곧바로 `fail()` 을 불렀는데, 그러면 status 가 'error' 가 되어 티커가
      // 멈추고 `pump` 도 `drain` 의 복구 조건도 다시는 성립하지 않았다.
      // **서버가 3초 만에 돌아와도 그 곡은 영영 재개되지 않았다.**
      source.failures += 1;
      const wait = Math.min(RETRY_MAX_MS, RETRY_BASE_MS * source.failures);
      source.retryAfter = Date.now() + wait;
      // 오래 이어지면 사정을 알린다. 다만 status 는 그대로 둔다 — 되살아날
      // 길을 막지 않기 위해서다. 회선이 돌아오면 아래 타이머가 이어받는다.
      if (source.failures === RETRY_NOTICE) {
        this.store.set({
          error: error instanceof Error ? error.message : '음원을 받지 못했어요.',
        });
      }
      setTimeout(() => {
        if (this.source === source) void this.fill();
      }, wait + 10);
    }
  }

  /**
   * 링버퍼에 쌓인 청크를 디코딩해 재생 가능한 상태로 옮긴다. 한 번에 하나만 돈다.
   *
   * 도는 동안 새 청크가 들어오면 **그 사실을 적어 두고 같은 실행에서 이어
   * 처리한다.** 예전에는 `draining` 이 켜져 있으면 그냥 돌아갔는데, 마침
   * 루프가 막 끝난 참이면 그 청크를 아무도 집지 않아 버퍼링 상태로 굳었다.
   * 버퍼링 중에는 타이머도 돌지 않아 스스로 빠져나오지 못했고, 곡이 끝나지
   * 않으니 다음 곡으로도 넘어가지 않았다.
   *
   * **도착한 순서가 아니라 프리롤이 준비된 순서로 푼다.** `fill` 은 청크
   * 두셋을 동시에 받는데, 응답이 보낸 순서대로 돌아온다는 보장이 없다 —
   * 모바일 회선에서는 뒤 청크가 앞 청크를 앞지르는 일이 잦다. 그러면 앞
   * 청크의 꼬리가 아직 없어 그 이음새만 프리롤 없이 풀리고, 6초마다가
   * 아니라 **어쩌다 한 번씩 '퍽' 하는 소리**가 된다. 앞 청크가 아직 오는
   * 중이면 여기서 기다렸다가 꼬리가 생긴 뒤에 푼다.
   */
  private async drain(source: TrackSource) {
    if (this.draining) {
      this.drainPending = true;
      return;
    }
    this.draining = true;
    try {
      this.prepare();
      const context = this.context;
      if (!context) return;
      do {
        this.drainPending = false;
        for (;;) {
          // 링버퍼는 곧바로 비운다. 여기서 기다리게 두면 수신 쪽이 막힌다.
          for (;;) {
            const item = this.ring.shift();
            if (!item) break;
            if (source.chunks[item.index]) {
              source.pending.set(item.index, {
                frames: item.frames,
                since: Date.now(),
              });
            } else {
              source.inflight.delete(item.index);
            }
          }
          const index = this.readyToDecode(source);
          if (index === null) break;
          const { frames } = source.pending.get(index)!;
          source.pending.delete(index);
          const slot = source.chunks[index];
          try {
            const buffer = await decodeOpusFrames(context, frames, {
              channels: source.channels,
              frameDurationMs: 20,
              expectedSamples: Math.round(
                (slot.duration_ms / 1000) * OPUS_SAMPLE_RATE,
              ),
              preRoll: source.frameTails.get(index - 1),
            });
            source.decoded.set(index, buffer);
          } catch (error) {
            if (this.source === source) {
              this.fail(
                error instanceof Error
                  ? error.message
                  : '음원을 디코딩하지 못했어요.',
              );
            }
          } finally {
            source.inflight.delete(index);
          }
        }
      } while (this.drainPending);
    } finally {
      this.draining = false;
      this.drainPending = false;
    }
    // 프리롤을 기다리느라 남겨 둔 것이 있으면 한도가 지난 뒤 한 번 더 본다.
    // 앞 청크의 응답이 끝내 매달려 있어도 소리가 멈추지 않는다.
    this.recheckPending(source);
    // 버퍼링 때문에 멈춰 있었다면 여기서 이어서 시작한다.
    if (this.source === source && this.store.get().status === 'buffering') {
      this.store.set({ bufferedSec: this.bufferedUntil() });
      if (source.decoded.has(Math.max(0, this.indexAt(this.anchorPosition)))) {
        void this.play();
      }
    }
    void this.fill();
  }

  /**
   * 지금 풀어도 되는 청크 중 가장 앞의 것. 하나도 없으면 null.
   *
   * 앞 청크가 아직 **오는 중이면** 기다린다 — 곧 꼬리가 생기고, 그래야
   * 프리롤이 붙는다. 꼬리는 받는 즉시 적어 두므로(`keepFrames`) 앞 청크가
   * 도착만 했으면 아직 풀기 전이어도 프리롤로 쓸 수 있다.
   *
   * 기다려도 소용없는 자리에서는 그냥 푼다. 첫 청크(앞이 없다), 앞 청크를
   * 받아 봤지만 실패한 자리(`tailTried`), 그리고 `PRE_ROLL_WAIT_SEC` 를
   * 넘겨 기다린 자리다. 그 이음새만 워밍업 잡음이 남는다.
   */
  private readyToDecode(source: TrackSource): number | null {
    const now = Date.now();
    let first: number | null = null;
    for (const [index, item] of source.pending) {
      if (first !== null && index >= first) continue;
      const waiting =
        index > 0 &&
        !source.frameTails.has(index - 1) &&
        source.inflight.has(index - 1) &&
        now - item.since < PRE_ROLL_WAIT_SEC * 1000;
      if (!waiting) first = index;
    }
    return first;
  }

  /**
   * 프리롤을 기다리며 남겨 둔 청크를 한도가 지난 뒤 다시 본다.
   *
   * `drain` 은 새 청크가 들어올 때만 도는데, 기다리는 대상이 바로 그 청크다.
   * 끝내 오지 않으면 아무도 깨우지 않으므로 여기서 시계를 걸어 둔다.
   */
  private recheckPending(source: TrackSource) {
    if (this.pendingTimer !== null) return;
    let wait = Infinity;
    for (const item of source.pending.values()) {
      wait = Math.min(wait, PRE_ROLL_WAIT_SEC * 1000 - (Date.now() - item.since));
    }
    if (!Number.isFinite(wait)) return;
    this.pendingTimer = setTimeout(() => {
      this.pendingTimer = null;
      if (this.source === source) void this.drain(source);
    }, Math.max(0, wait) + 10);
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
          const frames = this.keepFrames(source, slot.index, bytes);
          source.decoded.set(
            slot.index,
            await decodeOpusFrames(context, frames, {
              channels: source.channels,
              frameDurationMs: 20,
              expectedSamples: Math.round(
                (slot.duration_ms / 1000) * OPUS_SAMPLE_RATE,
              ),
              preRoll: source.frameTails.get(slot.index - 1),
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
