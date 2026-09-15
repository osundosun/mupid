/**
 * 청크 파일(.rtp) 파서.
 *
 * `music_preprocessing` 이 만드는 자체 컨테이너다. RTP 패킷을 길이 접두사로 이어
 * 붙였고, 각 패킷은 12바이트 RTP 헤더 + Opus 프레임 하나다.
 *
 *   0   4  매직 'RTPK'
 *   4   2  포맷 버전 (uint16 BE)
 *   6   4  총 패킷 수 (uint32 BE)
 *   10  ..  [uint16 BE 길이 | RTP 패킷] × N
 *
 * 근거: music_preprocessing/src/music_preprocessing/chunker.py, rtp.py
 */

const MAGIC = 0x5254504b; // 'RTPK'
const HEADER_SIZE = 10;
const RTP_HEADER_SIZE = 12;

export const SUPPORTED_VERSION = 1;

export class ChunkFormatError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ChunkFormatError';
  }
}

export type ParsedChunk = {
  version: number;
  /** RTP 헤더를 벗겨낸 Opus 프레임들. 순서가 재생 순서다. */
  frames: Uint8Array[];
};

/**
 * 청크 바이트에서 Opus 프레임을 꺼낸다.
 *
 * RTP 헤더의 시퀀스·타임스탬프는 쓰지 않는다. 파일 안에서 순서가 이미
 * 보장되고, 재생 위치는 청크 메타데이터(duration_ms)로 계산하기 때문이다.
 */
export function parseChunk(buffer: ArrayBuffer): ParsedChunk {
  const view = new DataView(buffer);
  if (buffer.byteLength < HEADER_SIZE) {
    throw new ChunkFormatError('청크 파일이 너무 짧습니다.');
  }
  if (view.getUint32(0, false) !== MAGIC) {
    throw new ChunkFormatError('청크 파일 형식이 아닙니다 (매직 불일치).');
  }
  const version = view.getUint16(4, false);
  if (version !== SUPPORTED_VERSION) {
    throw new ChunkFormatError(
      `지원하지 않는 청크 포맷 버전입니다: ${version}`,
    );
  }
  const count = view.getUint32(6, false);

  const frames: Uint8Array[] = [];
  let offset = HEADER_SIZE;
  for (let i = 0; i < count; i += 1) {
    if (offset + 2 > buffer.byteLength) {
      throw new ChunkFormatError('청크가 중간에 끊겼습니다 (길이 필드).');
    }
    const length = view.getUint16(offset, false);
    offset += 2;
    if (offset + length > buffer.byteLength) {
      throw new ChunkFormatError('청크가 중간에 끊겼습니다 (패킷 본문).');
    }
    // 헤더만 있고 페이로드가 없는 패킷은 건너뛴다. 디코더에 빈 프레임을
    // 넣으면 에러로 죽는다.
    if (length > RTP_HEADER_SIZE) {
      frames.push(
        new Uint8Array(buffer, offset + RTP_HEADER_SIZE, length - RTP_HEADER_SIZE),
      );
    }
    offset += length;
  }
  return { version, frames };
}
