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
  /**
   * 이미 직렬화된 본문. 문자열(JSON)이거나 바이너리다.
   *
   * `FormData` 도 받는다 — 멀티파트는 경계 문자열이 필요해서 직접 만들 수 없고,
   * 브라우저가 `Content-Type` 까지 붙여 준다. 그래서 이때는 헤더를 비워 둔다.
   */
  body?: string | ArrayBuffer | Uint8Array | FormData | null;
  responseType: ResponseType;
  /** 쿠키(리프레시 토큰)를 함께 보낼지. 기본은 same-origin. */
  withCredentials?: boolean;
  /**
   * 화면이 사라져도 요청을 끝까지 보낸다.
   *
   * 탭을 닫거나 새로고침하는 순간에 보내는 요청은 보통 취소된다. 이 값을 켜면
   * 브라우저가 문서와 무관하게 전송을 마친다 — 떠나기 직전에 저장을 밀어내는
   * 경로(재생 목록)가 이걸 쓴다.
   *
   * 대신 본문이 64KB 로 제한되고 **응답을 받아 볼 수 없다**(문서가 이미 없다).
   * 보내고 잊는 요청에만 쓴다.
   */
  keepalive?: boolean;
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

      /*
       * **본문을 다 읽을 때까지 취소와 시한을 걸어 둔다.**
       *
       * `fetch` 는 헤더가 오면 곧바로 돌아오고 본문은 그 뒤에 흐른다. 예전에는
       * 이 정리를 `fetch` 만 감싼 `finally` 에서 해서, `response.arrayBuffer()`
       * 구간은 **아무도 끊을 수 없었다.** 청크 오디오는 100KB 남짓이라 거의
       * 전부가 그 구간이다.
       *
       * 그래서 곡을 바꿔 `abort()` 를 불러도 듣던 곡의 청크는 끝까지 내려받혔고,
       * 그 응답이 새 곡이 재생되는 중에 도착했다 — 화면은 새 곡인데 소리는
       * 듣던 곡이 나오던 일의 출발점이다 (S15P21A204-434). 매달린 본문에
       * `timeoutMs` 가 걸리지 않던 것도 같은 자리였다.
       */
      try {
        let response: Response;
        try {
          response = await fetch(request.url, {
            method: request.method,
            headers: request.headers,
            body: request.body as BodyInit | null | undefined,
            credentials: request.withCredentials ? 'include' : 'same-origin',
            keepalive: request.keepalive,
            signal: controller.signal,
          });
        } catch (error) {
          if (request.signal?.aborted) throw error;
          throw new TransportError('네트워크 요청에 실패했어요.', error);
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
      } finally {
        if (timer) clearTimeout(timer);
        request.signal?.removeEventListener('abort', abort);
      }
    },
  };
}
