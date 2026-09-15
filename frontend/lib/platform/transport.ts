/**
 * 앱용 전송 계층.
 *
 * `lib/api/transport.ts` 의 `Transport` 를 그대로 만족시키므로 `ApiClient` 는
 * 무엇이 요청을 보내는지 모른다. 다른 점은 안쪽뿐이다 — `fetch` 대신 Rust
 * 커맨드를 부르고, 실제 HTTP 는 네이티브에서 돈다.
 *
 * 여기서 달라지는 것들.
 *
 * - **주소**: `ApiClient` 는 `/api/v1/...` 만 만든다. 앱에는 같은 출처가 없으니
 *   Rust 가 API 원본 주소를 붙인다 (`app/src/config.rs`).
 * - **쿠키**: `withCredentials` 를 보지 않는다. 리프레시 토큰 쿠키는 Rust 의
 *   쿠키 항아리가 들고 SQLite 에 적는다. 웹뷰에는 애초에 오지 않는다.
 * - **본문**: 바이너리와 첨부는 base64 로 넘긴다. 배열로 넘기면 IPC 직렬화가
 *   파일 크기에 비례해 느려진다.
 */

import {
  TransportError,
  type ResponseType,
  type Transport,
  type TransportRequest,
  type TransportResponse,
} from '../api/transport';
import { invoke } from './bridge';

type WirePart = {
  name: string;
  fileName?: string;
  contentType?: string;
  dataBase64: string;
};

type WireBody =
  | { kind: 'text'; text: string }
  | { kind: 'binary'; dataBase64: string }
  | { kind: 'multipart'; parts: WirePart[] };

type WireRequest = {
  method: string;
  url: string;
  headers: Record<string, string>;
  body: WireBody | null;
  timeoutMs?: number;
  requestId?: string;
};

/** 응답 앞머리에 붙어 오는 정보. 키는 Rust 가 소문자로 맞춰 준다. */
type ResponseMeta = { status: number; headers: Record<string, string> };

const encodeBase64 = (bytes: Uint8Array): string => {
  // 한 번에 넘기면 인자 개수 한도에 걸린다. 프로필 사진 정도는 조각내면 된다.
  const chunk = 0x8000;
  let binary = '';
  for (let index = 0; index < bytes.length; index += chunk) {
    binary += String.fromCharCode(...bytes.subarray(index, index + chunk));
  }
  return btoa(binary);
};

const toBytes = (value: ArrayBuffer | Uint8Array): Uint8Array =>
  value instanceof Uint8Array ? value : new Uint8Array(value);

async function toWireBody(
  body: TransportRequest['body'],
): Promise<WireBody | null> {
  if (body === undefined || body === null) return null;
  if (typeof body === 'string') return { kind: 'text', text: body };
  if (typeof FormData !== 'undefined' && body instanceof FormData) {
    const parts: WirePart[] = [];
    for (const [name, value] of body.entries()) {
      if (typeof value === 'string') {
        parts.push({
          name,
          dataBase64: encodeBase64(new TextEncoder().encode(value)),
        });
        continue;
      }
      parts.push({
        name,
        fileName: value.name || 'file',
        contentType: value.type || 'application/octet-stream',
        dataBase64: encodeBase64(toBytes(await value.arrayBuffer())),
      });
    }
    return { kind: 'multipart', parts };
  }
  // 남은 것은 바이너리뿐이다. `typeof FormData` 검사를 거치느라 타입이 좁혀지지
  // 않아 여기서 한 번 밝혀 준다.
  return {
    kind: 'binary',
    dataBase64: encodeBase64(toBytes(body as ArrayBuffer | Uint8Array)),
  };
}

/** `[4바이트 길이][메타데이터 JSON][본문]` 을 푼다. */
function unframe(buffer: ArrayBuffer): { meta: ResponseMeta; body: ArrayBuffer } {
  if (buffer.byteLength < 4) {
    throw new TransportError('앱이 응답을 제대로 돌려주지 못했어요.');
  }
  const metaLength = new DataView(buffer).getUint32(0, true);
  if (buffer.byteLength < 4 + metaLength) {
    throw new TransportError('앱이 응답을 제대로 돌려주지 못했어요.');
  }
  const meta = JSON.parse(
    new TextDecoder().decode(new Uint8Array(buffer, 4, metaLength)),
  ) as ResponseMeta;
  return { meta, body: buffer.slice(4 + metaLength) };
}

const decodeText = (body: ArrayBuffer) => new TextDecoder().decode(body);

const parseJson = (body: ArrayBuffer): unknown => {
  const text = decodeText(body);
  if (!text) return undefined;
  try {
    return JSON.parse(text);
  } catch {
    // 서버가 아니라 앞단의 게이트웨이가 만든 HTML 오류 페이지일 수 있다.
    return undefined;
  }
};

function readBody(
  status: number,
  responseType: ResponseType,
  body: ArrayBuffer,
): unknown {
  if (status === 204) return undefined;
  // 오류 응답은 선언한 형식과 무관하게 JSON 으로 읽는다. `Transport` 규약이다 —
  // 화면이 보여 줄 메시지가 거기에만 들어 있다.
  const ok = status >= 200 && status < 300;
  if (!ok) return parseJson(body);
  if (responseType === 'none') return undefined;
  if (responseType === 'arraybuffer') return body;
  if (responseType === 'text') return decodeText(body);
  return parseJson(body);
}

let counter = 0;

export function createTauriTransport(): Transport {
  return {
    async send(request): Promise<TransportResponse> {
      if (request.signal?.aborted) {
        throw new DOMException('요청이 취소됐어요.', 'AbortError');
      }

      // 취소할 수 있어야 하는 요청에만 식별자를 붙인다. Rust 는 이 값이 없으면
      // 취소 통로를 만들지 않는다.
      const requestId = request.signal ? `req-${++counter}` : undefined;
      const wire: WireRequest = {
        method: request.method,
        url: request.url,
        headers: request.headers,
        body: await toWireBody(request.body),
        timeoutMs: request.timeoutMs,
        requestId,
      };

      let abort: (() => void) | undefined;
      if (requestId && request.signal) {
        abort = () => void invoke('http_abort', { requestId }).catch(() => {});
        request.signal.addEventListener('abort', abort, { once: true });
        // 본문을 만드는 동안(`await`) 끊겼을 수 있다. 이미 끊긴 신호에는
        // `abort` 가 다시 오지 않으므로, 여기서 보지 않으면 요청은 나가고
        // 아무도 그것을 멈추지 못한다.
        if (request.signal.aborted) {
          request.signal.removeEventListener('abort', abort);
          throw new DOMException('요청이 취소됐어요.', 'AbortError');
        }
      }

      let buffer: ArrayBuffer;
      try {
        buffer = await invoke<ArrayBuffer>('http_send', { request: wire });
      } catch (error) {
        // 끊은 쪽이 우리라면 취소로 알린다. `fetch` 구현과 같은 모양이어야
        // 상위 계층(`ApiClient`·플레이어)이 재시도할지 말지를 똑같이 판단한다.
        if (request.signal?.aborted) {
          throw new DOMException('요청이 취소됐어요.', 'AbortError');
        }
        throw new TransportError(
          typeof error === 'string' ? error : '네트워크 요청에 실패했어요.',
          error,
        );
      } finally {
        if (abort && request.signal) {
          request.signal.removeEventListener('abort', abort);
        }
      }

      const { meta, body } = unframe(buffer);
      return {
        status: meta.status,
        headers: meta.headers,
        data: readBody(meta.status, request.responseType, body),
      };
    },
  };
}

/** 기기에 남은 로그인 쿠키를 지운다. 로그아웃할 때 부른다. */
export const clearNativeCookies = (): Promise<void> =>
  invoke('http_clear_cookies');
