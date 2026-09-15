/**
 * Opus 프레임 → PCM(AudioBuffer) 디코더.
 *
 * 청크에 들어 있는 것은 컨테이너가 아니라 **RTP 에서 벗겨낸 raw Opus 프레임**
 * 이라 `decodeAudioData` 로는 풀 수 없다(그쪽은 Ogg/WebM/MP4 같은 컨테이너만
 * 받는다). WebCodecs `AudioDecoder` 가 raw Opus 패킷을 그대로 받는다.
 *
 * 청크마다 디코더를 비운다. 청크 하나하나가 ffmpeg 세그먼트로 따로 인코딩된
 * 것이라 인코더 상태가 이어지지 않고, Seek 때문에 순서를 건너뛰어 디코딩해야
 * 하기 때문이다.
 *
 * 출력은 항상 스테레오다. 모노 음원은 좌·우에 같은 소리를 넣는다 — libopus 가
 * 2채널 설정으로 디코딩하면서 복제하고, 혹시 디코더가 1채널을 돌려주더라도
 * `readChannel` 이 같은 플레인을 양쪽에 복사한다.
 */

// WebCodecs 타입은 TS 기본 lib.dom 에 없다. 의존성을 늘리지 않으려고
// 쓰는 부분만 최소로 선언한다.
type AudioDataFormat = 'f32' | 'f32-planar' | 's16' | 's16-planar';
interface AudioDataLike {
  readonly numberOfFrames: number;
  readonly numberOfChannels: number;
  readonly sampleRate: number;
  readonly format: AudioDataFormat | null;
  allocationSize(options: { planeIndex: number; format?: AudioDataFormat }): number;
  copyTo(
    destination: ArrayBufferView,
    options: { planeIndex: number; format?: AudioDataFormat },
  ): void;
  close(): void;
}
interface AudioDecoderLike {
  readonly state: 'unconfigured' | 'configured' | 'closed';
  configure(config: {
    codec: string;
    sampleRate: number;
    numberOfChannels: number;
  }): void;
  decode(chunk: unknown): void;
  flush(): Promise<void>;
  close(): void;
}
type AudioDecoderCtor = new (init: {
  output: (data: AudioDataLike) => void;
  error: (error: DOMException) => void;
}) => AudioDecoderLike;
type EncodedAudioChunkCtor = new (init: {
  type: 'key' | 'delta';
  timestamp: number;
  duration?: number;
  data: BufferSource;
}) => unknown;

const globalWithCodecs = globalThis as unknown as {
  AudioDecoder?: AudioDecoderCtor;
  EncodedAudioChunk?: EncodedAudioChunkCtor;
};

export const OPUS_SAMPLE_RATE = 48000;

export function isOpusDecodingSupported(): boolean {
  return (
    typeof globalWithCodecs.AudioDecoder === 'function' &&
    typeof globalWithCodecs.EncodedAudioChunk === 'function'
  );
}

/**
 * Opus 프레임의 TOC 바이트에서 채널 수를 읽는다 (RFC 6716 §3.1).
 *
 * TOC = config(5비트) | s(1비트) | c(2비트). `s` 비트(0x04)가 0이면 모노,
 * 1이면 스테레오다. 서버 메타에 채널 수가 없어도 비트스트림이 직접 알려준다.
 *
 * raw Opus 프레임은 채널 매핑 패밀리 0(모노·스테레오)만 담을 수 있다.
 * 그 이상(5.1 등)은 OpusHead 가 있어야 풀리므로 여기서 다룰 수 없다.
 */
export function channelsFromToc(frame: Uint8Array): 1 | 2 {
  if (frame.length === 0) return 2;
  return (frame[0] & 0x04) === 0 ? 1 : 2;
}

export class DecodeError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message);
    this.name = 'DecodeError';
    if (options?.cause) (this as { cause?: unknown }).cause = options.cause;
  }
}

export type DecodeOptions = {
  /** 만들어 낼 AudioBuffer 의 채널 수. 보통 2(스테레오)다. */
  channels: number;
  /** 프레임 하나의 길이(ms). 청킹 설정 기본값은 20ms 다. */
  frameDurationMs: number;
  /** 이 청크가 차지해야 하는 정확한 샘플 수. 넘치면 앞에서 잘라낸다. */
  expectedSamples: number;
};

/**
 * Opus 프레임 묶음을 하나의 AudioBuffer 로 만든다.
 *
 * 길이를 `expectedSamples` 에 정확히 맞춘다. Opus 인코더는 세그먼트마다 앞에
 * 프리스킵(보통 6.5ms)을 넣는데, 그대로 두면 청크마다 몇 ms 씩 밀려 이어붙인
 * 자리에서 소리가 튄다. 넘치는 만큼을 앞에서 버리고, 모자라면 뒤를 0으로
 * 채운다.
 */
export async function decodeOpusFrames(
  context: BaseAudioContext,
  frames: Uint8Array[],
  options: DecodeOptions,
): Promise<AudioBuffer> {
  if (!isOpusDecodingSupported()) {
    throw new DecodeError(
      '이 브라우저는 Opus 스트리밍 재생을 지원하지 않아요. 최신 Chrome·Edge 를 사용해 주세요.',
    );
  }
  const { channels, frameDurationMs, expectedSamples } = options;
  const output = context.createBuffer(
    channels,
    Math.max(1, expectedSamples),
    OPUS_SAMPLE_RATE,
  );
  if (frames.length === 0) return output;

  // 디코더에는 비트스트림의 실제 채널 수를 알려 준다. 서버 메타에 없어서
  // 프레임에서 직접 읽는다. 모노여도 출력은 스테레오이고, 좌·우에 같은
  // 소리가 들어간다(readChannel 이 같은 플레인을 양쪽에 복사한다).
  const sourceChannels = channelsFromToc(frames[0]);

  const parts: AudioDataLike[] = [];
  let decodedSamples = 0;
  let failure: unknown = null;

  const AudioDecoderImpl = globalWithCodecs.AudioDecoder!;
  const EncodedAudioChunkImpl = globalWithCodecs.EncodedAudioChunk!;

  const decoder = new AudioDecoderImpl({
    output: (data) => {
      parts.push(data);
      decodedSamples += data.numberOfFrames;
    },
    error: (error) => {
      failure = error;
    },
  });

  try {
    decoder.configure({
      codec: 'opus',
      sampleRate: OPUS_SAMPLE_RATE,
      numberOfChannels: sourceChannels,
    });
    const frameDurationUs = frameDurationMs * 1000;
    frames.forEach((frame, index) => {
      decoder.decode(
        new EncodedAudioChunkImpl({
          type: 'key', // Opus 는 프레임마다 독립적으로 디코딩된다.
          timestamp: index * frameDurationUs,
          duration: frameDurationUs,
          // 뷰를 그대로 넘기면 근본 ArrayBuffer 전체가 복사될 수 있다.
          data: frame.slice(),
        }),
      );
    });
    await decoder.flush();
  } catch (error) {
    throw new DecodeError('청크를 디코딩하지 못했어요.', { cause: error });
  } finally {
    if (decoder.state !== 'closed') decoder.close();
  }
  if (failure) {
    for (const part of parts) part.close();
    throw new DecodeError('청크를 디코딩하지 못했어요.', { cause: failure });
  }

  // 채널별로 이어 붙인 뒤 한 번에 잘라낸다. 조각마다 나눠 세면 프리스킵
  // 경계가 조각 중간에 걸릴 때 계산이 틀리기 쉽다.
  try {
    const joined: Float32Array[] = [];
    for (let channel = 0; channel < channels; channel += 1) {
      const merged = new Float32Array(decodedSamples);
      let at = 0;
      for (const part of parts) {
        merged.set(readChannel(part, channel), at);
        at += part.numberOfFrames;
      }
      joined.push(merged);
    }
    // Opus 인코더가 세그먼트 앞에 넣는 프리스킵(보통 6.5ms)을 버린다.
    // 그대로 두면 청크를 이어붙인 자리마다 몇 ms 씩 밀려 소리가 튄다.
    const skip = Math.max(0, decodedSamples - expectedSamples);
    const take = Math.min(expectedSamples, decodedSamples - skip);
    for (let channel = 0; channel < channels; channel += 1) {
      output
        .getChannelData(channel)
        .set(joined[channel].subarray(skip, skip + take), 0);
    }
  } finally {
    for (const part of parts) part.close();
  }
  return output;
}

/**
 * AudioData 한 조각에서 채널 하나를 Float32 로 꺼낸다.
 *
 * 모노 음원이면 디코더가 채널 1개만 돌려준다. 그때는 그 하나를 좌·우 양쪽에
 * 그대로 복사한다(`plane` 을 클램프). 한쪽만 소리가 나는 것을 막는다.
 */
function readChannel(data: AudioDataLike, channel: number): Float32Array {
  const plane = Math.min(channel, data.numberOfChannels - 1);
  const planar = data.format?.endsWith('-planar') ?? true;
  if (planar) {
    const target = new Float32Array(data.numberOfFrames);
    data.copyTo(target, { planeIndex: plane, format: 'f32-planar' });
    return target;
  }
  // 인터리브로 오면 한 번에 꺼내 해당 채널만 걸러낸다.
  const stride = data.numberOfChannels;
  const interleaved = new Float32Array(data.numberOfFrames * stride);
  data.copyTo(interleaved, { planeIndex: 0, format: 'f32' });
  const target = new Float32Array(data.numberOfFrames);
  for (let i = 0; i < data.numberOfFrames; i += 1) {
    target[i] = interleaved[i * stride + plane];
  }
  return target;
}
