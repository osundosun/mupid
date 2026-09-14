/**
 * 전송 계층 추상화.
 *
 * 나중에 fetch → axios 등으로 갈아끼울 수 있도록, 상위 계층(ApiClient)은 이
 * 인터페이스만 알고 fetch 의 Request/Response 타입은 전혀 보지 않는다.
 * 새 구현을 붙이려면 `Transport` 하나만 만족시키면 된다.
 */

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

/** 응답 본문을 어떤 모양으로 받을지. 청크 오디오는 arraybuffer 로 받는다. */
export type ResponseType = 'json' | 'text' | 'arraybuffer' | 'none';

export type TransportRequest = {
  method: HttpMethod;
  /** 절대 URL. baseUrl 결합은 ApiClient 가 끝낸 뒤 넘긴다. */
  url: string;
  headers: Record<string, string>;
  /** 이미 직렬화된 본문. 문자열(JSON)이거나 바이너리다. */
  body?: string | ArrayBuffer | Uint8Array | null;
  responseType: ResponseType;
  /** 쿠키(리프레시 토큰)를 함께 보낼지. 기본은 same-origin. */
  withCredentials?: boolean;
  signal?: AbortSignal;
  timeoutMs?: number;
};

export type TransportResponse = {
  status: number;
  /** 키는 소문자로 정규화한다. 구현체마다 대소문자가 다르다. */
  headers: Record<string, string>;
  /** responseType 에 맞춰 파싱된 값. 'none' 이면 undefined. */
  data: unknown;
};

export interface Transport {
  /**
   * 요청을 보내고 응답을 돌려준다.
   *
   * **오류 응답(2xx 아님)의 본문은 `responseType` 과 무관하게 JSON 으로 파싱해
   * `data` 에 담아야 한다.** 서버 오류는 항상 JSON 이고, 화면이 보여줄 메시지가
   * 거기 들어 있다. 다른 구현으로 갈아끼울 때도 이 규칙을 지켜야 한다.
   */
  send(request: TransportRequest): Promise<TransportResponse>;
}

/** 네트워크 자체가 실패한 경우. 상태 코드가 없다. */
export class TransportError extends Error {
  readonly cause?: unknown;
  constructor(message: string, cause?: unknown) {
    super(message);
    this.name = 'TransportError';
    this.cause = cause;
  }
}

const parseJson = async (response: Response): Promise<unknown> => {
  const text = await response.text();
  if (!text) return undefined;
  try {
    return JSON.parse(text);
  } catch {
    // 서버가 아니라 프록시/게이트웨이가 만든 HTML 오류 페이지일 수 있다.
    // 본문을 통째로 메시지에 싣지 않고 상위에서 상태 코드로 판단하게 둔다.
    return undefined;
  }
};

const readBody = async (
  response: Response,
  responseType: ResponseType,
): Promise<unknown> => {
  if (response.status === 204) return undefined;
  // 오류 응답은 responseType 과 무관하게 JSON 으로 읽는다. 'none' 이나
  // 'arraybuffer' 로 선언했다고 서버가 준 오류 메시지까지 버리면, 화면에
  // "요청에 실패했어요" 같은 빈 문구밖에 못 보여준다.
  if (!response.ok) return parseJson(response);
  if (responseType === 'none') return undefined;
  if (responseType === 'arraybuffer') return response.arrayBuffer();
  if (responseType === 'text') return response.text();
  return parseJson(response);
};

export function createFetchTransport(): Transport {
  return {
    async send(request) {
      const controller = new AbortController();
      const abort = () => controller.abort();
      request.signal?.addEventListener('abort', abort, { once: true });
      const timer = request.timeoutMs
        ? setTimeout(abort, request.timeoutMs)
        : null;

      let response: Response;
      try {
        response = await fetch(request.url, {
          method: request.method,
          headers: request.headers,
          body: request.body as BodyInit | null | undefined,
          credentials: request.withCredentials ? 'include' : 'same-origin',
          signal: controller.signal,
        });
      } catch (error) {
        if (request.signal?.aborted) throw error;
        throw new TransportError('네트워크 요청에 실패했어요.', error);
      } finally {
        if (timer) clearTimeout(timer);
        request.signal?.removeEventListener('abort', abort);
      }

      const headers: Record<string, string> = {};
      response.headers.forEach((value, key) => {
        headers[key.toLowerCase()] = value;
      });

      return {
        status: response.status,
        headers,
        data: await readBody(response, request.responseType),
      };
    },
  };
}
