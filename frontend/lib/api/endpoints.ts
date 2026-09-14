/**
 * 서버 엔드포인트 래퍼. 경로와 응답 모양을 한곳에 모은다.
 *
 * 필드 이름은 서버 규약대로 snake_case 다
 * (`spring.jackson.property-naming-strategy: SNAKE_CASE`).
 * 단, 멀티파트 폼 필드만 camelCase 다 — JSON 본문에만 적용되는 설정이라서다.
 */

import type { ApiClient } from './client';

export type LoginResponse = {
  access_token: string;
  user_id: number;
  user_role: 'ROLE_USER' | 'ROLE_SYSTEM' | 'ROLE_ADMIN';
};

export type UserInfo = {
  user_id: number;
  email: string;
  nickname: string;
  image_url: string;
  birth_year: number;
  gender: 'M' | 'F' | 'ETC';
  role: 'ROLE_USER' | 'ROLE_SYSTEM' | 'ROLE_ADMIN';
  status: 'PENDING' | 'ACTIVE' | 'WITHDRAW';
};

export type SignupBody = {
  email: string;
  email_verification_id: number;
  password: string;
  nickname: string;
  birth_year: number;
  /** 서버 enum 은 M / F / ETC 다. */
  gender: 'M' | 'F' | 'ETC';
};

export type StreamInfo = {
  stream_id: number;
  music_id: number;
  bitrate_kbps: number;
  codec: 'AAC' | 'OPUS';
  duration_ms: number;
  chunk_count: number;
  status: 'PENDING' | 'IN_PROGRESS' | 'DONE' | 'FAILED';
};

export type ChunkInfo = {
  index: number;
  duration_ms: number;
  file_hash: string;
};

export function createEndpoints(client: ApiClient) {
  return {
    auth: {
      /** 로그인. 응답 쿠키로 리프레시 토큰이 심긴다. */
      login: (email: string, password: string) =>
        client.request<LoginResponse>({
          method: 'POST',
          path: '/api/v1/auth',
          json: { email, password },
          anonymous: true,
          withCredentials: true,
        }),
      /** 액세스 토큰 재발급. 평소에는 ApiClient 가 알아서 부른다. */
      reissue: () =>
        client.request<{ access_token: string }>({
          path: '/api/v1/auth',
          anonymous: true,
          withCredentials: true,
        }),
      logout: () =>
        client.request<void>({
          method: 'DELETE',
          path: '/api/v1/auth',
          responseType: 'none',
          withCredentials: true,
        }),
    },

    user: {
      me: () => client.request<UserInfo>({ path: '/api/v1/user' }),
      signup: (body: SignupBody) =>
        client.request<void>({
          method: 'POST',
          path: '/api/v1/user',
          json: body,
          responseType: 'none',
          anonymous: true,
        }),
      update: (body: Partial<Pick<SignupBody, 'password' | 'nickname' | 'birth_year' | 'gender'>>) =>
        client.request<void>({
          method: 'PATCH',
          path: '/api/v1/user',
          json: body,
          responseType: 'none',
        }),
      withdraw: () =>
        client.request<void>({
          method: 'DELETE',
          path: '/api/v1/user',
          responseType: 'none',
        }),
    },

    mail: {
      /**
       * 인증 메일 발송.
       *
       * GET 이라 본문을 쓸 수 없다 (fetch 는 GET 에 본문을 싣지 못한다).
       * 서버도 `@ModelAttribute` 로 쿼리 파라미터를 받는다.
       */
      sendVerification: (email: string) =>
        client.request<{ verification_id: number }>({
          method: 'GET',
          path: '/api/v1/mail/verification',
          query: { email },
          anonymous: true,
        }),
      submitCode: (verificationId: number, token: string) =>
        client.request<void>({
          method: 'POST',
          path: '/api/v1/mail/verification',
          json: { verification_id: verificationId, token },
          responseType: 'none',
          anonymous: true,
        }),
      check: (verificationId: number) =>
        client.request<{ is_valid: boolean }>({
          path: `/api/v1/mail/verification/${verificationId}`,
          anonymous: true,
        }),
    },

    stream: {
      /** 곡의 재생 가능한 스트림 목록. DONE 인 것만 내려온다. */
      listByMusic: (musicId: number, signal?: AbortSignal) =>
        client
          .request<{ streams: StreamInfo[] }>({
            path: `/api/v1/music/${musicId}/stream`,
            signal,
          })
          .then((value) => value?.streams ?? []),
      /** 청크 메타데이터. index 오름차순. */
      listChunks: (streamId: number, signal?: AbortSignal) =>
        client
          .request<{ chunks: ChunkInfo[] }>({
            path: `/api/v1/chunk/${streamId}`,
            signal,
          })
          .then((value) => value?.chunks ?? []),
      /**
       * 청크 바이너리.
       *
       * 스프링은 본문을 만들지 않고 nginx 가 오브젝트 스토리지에서 읽어 보낸다
       * (X-Accel-Redirect). 그래서 BaseResponse 로 감싸지지 않은 raw 바이트다.
       */
      downloadChunk: (streamId: number, index: number, signal?: AbortSignal) =>
        client.request<ArrayBuffer>({
          path: `/api/v1/chunk/${streamId}/${index}`,
          responseType: 'arraybuffer',
          signal,
          // 청크 하나가 100KB 남짓이지만 모바일 회선을 감안해 넉넉히 둔다.
          timeoutMs: 30000,
        }),
    },
  };
}

export type Endpoints = ReturnType<typeof createEndpoints>;
