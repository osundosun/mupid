/**
 * Mupid API 클라이언트.
 *
 * 서버 응답 규약:
 *   성공 { message, data }            — BaseResponse
 *   실패 { code, message, error_details } — ErrorResponse
 *   401/403 은 Spring Security 가 직접 쓰므로 `errorDetails`(camelCase) 다.
 *   모든 오류에 `X-Mupid-Error` 헤더가 함께 온다. 프록시가 본문을 지워도
 *   코드는 살아남는다.
 *
 * 액세스 토큰은 메모리에만 둔다. 새로고침하면 사라지고, HttpOnly 쿠키의
 * 리프레시 토큰으로 다시 받는다 (`GET /api/v1/auth`).
 */

import {
  createFetchTransport,
  TransportError,
  type HttpMethod,
  type ResponseType,
  type Transport,
} from './transport';

export class ApiError extends Error {
  readonly status: number;
  /** MUPID_* 오류 코드. 알 수 없으면 빈 문자열. */
  readonly code: string;
  readonly details: Record<string, string> | null;
  constructor(
    status: number,
    code: string,
    message: string,
    details: Record<string, string> | null = null,
  ) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details;
  }
  /** 액세스 토큰이 없거나 만료됐다. */
  get isUnauthorized() {
    return this.status === 401;
  }
}

export type ApiRequestOptions = {
  method?: HttpMethod;
  /** '/api/v1/...' 형태의 경로. baseUrl 과 합친다. */
  path: string;
  query?: Record<string, string | number | undefined>;
  /** JSON 으로 직렬화할 객체. 바이너리는 `binary` 를 쓴다. */
  json?: unknown;
  binary?: ArrayBuffer | Uint8Array;
  /**
   * 멀티파트 본문. `Content-Type` 은 붙이지 않는다 — 경계 문자열을 브라우저가
   * 정하므로 직접 넣으면 서버가 본문을 못 읽는다.
   */
  form?: FormData;
  contentType?: string;
  responseType?: ResponseType;
  /** 액세스 토큰을 붙이지 않는다 (로그인·재발급 등). */
  anonymous?: boolean;
  /** 쿠키를 함께 보낸다 (재발급·로그아웃). */
  withCredentials?: boolean;
  signal?: AbortSignal;
  timeoutMs?: number;
};

export type ApiClientOptions = {
  baseUrl?: string;
  transport?: Transport;
  /** 토큰 재발급까지 실패했을 때 부른다. 보통 로그아웃 처리. */
  onSessionExpired?: () => void;
  /** 새 액세스 토큰을 받을 때마다 부른다. */
  onAccessToken?: (token: string | null) => void;
  timeoutMs?: number;
};

type QueueEntry = {
  run: () => void;
  fail: (error: unknown) => void;
};

const isRecord = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === 'object';

/** 서버 오류 응답에서 코드·메시지·필드 오류를 끄집어낸다. */
function toApiError(
  status: number,
  headers: Record<string, string>,
  body: unknown,
): ApiError {
  const header = headers['x-mupid-error'] || '';
  if (!isRecord(body)) {
    return new ApiError(
      status,
      header,
      header ? '요청을 처리하지 못했어요.' : `요청에 실패했어요. (${status})`,
    );
  }
  // 401/403 만 errorDetails, 나머지는 error_details 로 온다.
  const raw = body.error_details ?? body.errorDetails;
  const details: Record<string, string> | null = isRecord(raw)
    ? Object.fromEntries(
        Object.entries(raw).map(([key, value]) => [key, String(value)]),
      )
    : null;
  return new ApiError(
    status,
    typeof body.code === 'string' ? body.code : header,
    typeof body.message === 'string'
      ? body.message
      : `요청에 실패했어요. (${status})`,
    details,
  );
}

export class ApiClient {
  private readonly transport: Transport;
  private readonly baseUrl: string;
  private readonly timeoutMs: number;
  private readonly onSessionExpired?: () => void;
  private readonly onAccessToken?: (token: string | null) => void;

  private accessToken: string | null = null;
  /** 재발급이 진행 중이면 그 약속. 여러 요청이 동시에 401 이어도 한 번만 돈다. */
  private refreshing: Promise<void> | null = null;
  /** 재발급 동안 들어온 요청들. 끝나면 들어온 순서대로 내보낸다. */
  private waiting: QueueEntry[] = [];
  /** 재발급이 한 번 실패하면 다시 시도하지 않는다 (로그인 전 상태). */
  private sessionEnded = false;

  constructor(options: ApiClientOptions = {}) {
    this.transport = options.transport ?? createFetchTransport();
    this.baseUrl = (options.baseUrl ?? '').replace(/\/$/, '');
    this.timeoutMs = options.timeoutMs ?? 15000;
    this.onSessionExpired = options.onSessionExpired;
    this.onAccessToken = options.onAccessToken;
  }

  getAccessToken() {
    return this.accessToken;
  }

  setAccessToken(token: string | null) {
    this.accessToken = token;
    if (token) this.sessionEnded = false;
    this.onAccessToken?.(token);
  }

  /** 로그아웃 등으로 세션을 끝낼 때. 대기 중인 요청도 모두 실패시킨다. */
  clearSession() {
    this.setAccessToken(null);
    this.sessionEnded = true;
    this.flushQueue(
      new ApiError(401, 'MUPID_UNAUTHORIZED', '로그인이 필요해요.'),
    );
  }

  async request<T = unknown>(options: ApiRequestOptions): Promise<T> {
    // 재발급이 도는 동안 들어온 요청은 줄을 선다. 토큰 없이 보내면 어차피
    // 401 이 되고, 재발급을 여러 번 돌게 만든다.
    //
    // **익명 요청은 줄 세우지 않는다.** 토큰이 필요 없으니 기다릴 이유가 없고,
    // 기다리게 두면 재발급이 실패할 때 함께 실패한다. 첫 방문자가 곡 목록을
    // 못 받는 것이 이 때문이었다 — 세션 복원이 늘 실패하는 상태라서다.
    if (this.refreshing && !options.anonymous) await this.enqueue();
    try {
      return await this.dispatch<T>(options);
    } catch (error) {
      if (
        !(error instanceof ApiError) ||
        !error.isUnauthorized ||
        options.anonymous ||
        this.sessionEnded
      ) {
        throw error;
      }
      await this.refreshAccessToken();
      return this.dispatch<T>(options);
    }
  }

  // ─────────────────────────────────────────────────────────────
  // 내부
  // ─────────────────────────────────────────────────────────────

  private async dispatch<T>(options: ApiRequestOptions): Promise<T> {
    const headers: Record<string, string> = {};
    let body: string | ArrayBuffer | Uint8Array | FormData | null = null;

    if (options.json !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(options.json);
    } else if (options.form) {
      // Content-Type 을 붙이지 않는다. 브라우저가 경계 문자열과 함께 채운다.
      body = options.form;
    } else if (options.binary) {
      headers['Content-Type'] =
        options.contentType ?? 'application/octet-stream';
      body = options.binary;
    }
    if (!options.anonymous && this.accessToken) {
      headers.Authorization = `Bearer ${this.accessToken}`;
    }

    const response = await this.transport.send({
      method: options.method ?? 'GET',
      url: this.resolve(options.path, options.query),
      headers,
      body,
      responseType: options.responseType ?? 'json',
      withCredentials: options.withCredentials,
      signal: options.signal,
      timeoutMs: options.timeoutMs ?? this.timeoutMs,
    });

    if (response.status < 200 || response.status >= 300) {
      throw toApiError(response.status, response.headers, response.data);
    }
    // 성공 응답은 BaseResponse 로 감싸져 있다. data 만 꺼내 돌려준다.
    if (
      (options.responseType ?? 'json') === 'json' &&
      isRecord(response.data) &&
      'data' in response.data
    ) {
      return response.data.data as T;
    }
    return response.data as T;
  }

  private resolve(
    path: string,
    query?: Record<string, string | number | undefined>,
  ) {
    const url = `${this.baseUrl}${path}`;
    if (!query) return url;
    const search = new URLSearchParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined) search.set(key, String(value));
    }
    const text = search.toString();
    return text ? `${url}?${text}` : url;
  }

  private enqueue(): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      this.waiting.push({ run: resolve, fail: reject });
    });
  }

  /**
   * 액세스 토큰을 다시 받는다. 동시에 여러 요청이 401 이어도 실제 재발급은
   * 한 번만 돈다. 끝나면 밀려 있던 요청을 들어온 순서대로 내보낸다.
   */
  private refreshAccessToken(): Promise<void> {
    if (this.refreshing) return this.refreshing;

    this.refreshing = (async () => {
      try {
        // 리프레시 토큰은 HttpOnly 쿠키다. 브라우저가 알아서 실어 보낸다.
        const data = await this.dispatch<{ access_token: string }>({
          path: '/api/v1/auth',
          anonymous: true,
          withCredentials: true,
        });
        if (!data?.access_token) throw new Error('토큰이 비어 있습니다.');
        this.setAccessToken(data.access_token);
      } catch (error) {
        this.sessionEnded = true;
        this.setAccessToken(null);
        this.onSessionExpired?.();
        throw error instanceof ApiError
          ? error
          : new ApiError(
              401,
              'MUPID_UNAUTHORIZED',
              '세션이 만료됐어요. 다시 로그인해 주세요.',
            );
      }
    })();

    // 성공하든 실패하든 대기열을 비운다. 성공이면 순서대로 재개,
    // 실패면 같은 오류로 한꺼번에 거절한다.
    return this.refreshing.then(
      () => {
        this.refreshing = null;
        this.flushQueue(null);
      },
      (error) => {
        this.refreshing = null;
        this.flushQueue(error);
        throw error;
      },
    );
  }

  private flushQueue(error: unknown) {
    const queued = this.waiting;
    this.waiting = [];
    for (const entry of queued) {
      if (error) entry.fail(error);
      else entry.run();
    }
  }
}

export { TransportError };
