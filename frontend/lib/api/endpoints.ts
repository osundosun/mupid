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

export type MusicComment = {
  comment_id: number;
  music_id: number;
  timestamp_ms: number;
  content: string;
  user_id: number;
  nickname: string;
  /** 서버가 판정한다. 화면이 user_id 를 비교하지 않는다. */
  mine: boolean;
  created_at: string;
  updated_at: string;
};

export type MusicCommentPage = {
  items: MusicComment[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
};

export type ArtistSummary = {
  artist_id: number;
  name: string;
  image_url: string | null;
};

export type AlbumSummary = {
  album_id: number;
  title: string;
  image_url: string | null;
  release_date: string;
  artist: ArtistSummary;
};

export type ArtistPage = {
  items: ArtistSummary[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
};

export type AlbumPage = {
  items: AlbumSummary[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
};

export type AlbumDetail = { album: AlbumSummary; tracks: MusicSummary[] };

export type LyricLine = { lyric_id: number; time_ms: number; text: string };

export type MusicLyrics = { music_id: number; lines: LyricLine[] };

export type MusicSection = {
  section_id: number;
  index: number;
  /** INTRO · VERSE · PRE_CHORUS · CHORUS · BRIDGE · BREAK · INST · SOLO · OUTRO */
  label: string;
  start_ms: number;
  end_ms: number;
  moods: string[];
  instruments: string[];
};

export type MusicAnalysis = {
  music_id: number;
  /** 분석 결과가 있는가. 없으면 아래는 전부 비어 있다. */
  analyzed: boolean;
  has_vocal: boolean | null;
  vocal_gender: 'M' | 'F' | 'ETC' | null;
  sections: MusicSection[];
};

export type ReactionType = 'LIKE' | 'DISLIKE';

/** 내가 반응한 곡 번호. 하트 상태를 그리려면 전부 필요해서 페이징이 없다. */
export type Reactions = { like: number[]; dislike: number[] };

export type CodeOption = { code_id: number; name: string };

export type OnboardingOptions = {
  moods: CodeOption[];
  genres: CodeOption[];
};

export type OnboardingStatus = {
  submitted: boolean;
  submitted_at: string | null;
  nickname: string;
  moods: CodeOption[];
  genres: CodeOption[];
};

export type OnboardingBody = {
  nickname: string;
  mood_code_ids: number[];
  genre_code_ids: number[];
};

export type MusicSummary = {
  music_id: number;
  title: string;
  release_date: string;
  /** 완료된 스트림이 없으면 null. */
  duration_ms: number | null;
  /** 재생 가능한 스트림이 있는가. */
  playable: boolean;
  play_count: number;
  like_count: number;
  artist: { artist_id: number; name: string; image_url: string | null };
  album: {
    album_id: number;
    title: string;
    image_url: string | null;
    release_date: string;
  };
  genres: string[];
  moods: string[];
};

export type MusicPage = {
  items: MusicSummary[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
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
      me: (signal?: AbortSignal) => client.request<UserInfo>({ path: '/api/v1/user', signal }),
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

    storage: {
      /**
       * 프로필 사진 업로드.
       *
       * 저장 키는 **본인 유저 번호**여야 한다. 남의 번호를 쓰면 403 이다 —
       * 키가 경로 변수라 그대로 두면 남의 사진 파일을 덮어쓸 수 있다.
       *
       * 멀티파트 필드 이름은 `imageFile` 로 camelCase 다. SNAKE_CASE 설정은
       * JSON 본문에만 걸린다.
       */
      uploadProfileImage: (userId: number, file: Blob, filename = 'profile.jpg') => {
        const form = new FormData();
        form.append('imageFile', file, filename);
        return client.request<{ file_id: number }>({
          method: 'POST',
          path: `/api/v1/storage/image/PROFILE/${userId}`,
          form,
        });
      },
      deleteProfileImage: (userId: number) =>
        client.request<void>({
          method: 'DELETE',
          path: `/api/v1/storage/image/PROFILE/${userId}`,
          responseType: 'none',
        }),
      /**
       * 이미지 바이트.
       *
       * 프로필 사진은 공개 경로가 아니라 `<img src>` 로 바로 못 건다. 토큰을
       * 달아 받아 온 뒤 object URL 로 바꿔 쓴다.
       */
      image: (url: string, signal?: AbortSignal) =>
        client.request<ArrayBuffer>({
          path: url,
          responseType: 'arraybuffer',
          signal,
        }),
    },

    comment: {
      /** 한 곡의 댓글. 시점 오름차순. */
      list: (musicId: number, options: { page?: number; size?: number } = {}, signal?: AbortSignal) =>
        client.request<MusicCommentPage>({
          path: `/api/v1/music/${musicId}/comment`,
          query: { page: options.page, size: options.size },
          signal,
        }),
      add: (musicId: number, body: { timestamp_ms: number; content: string }) =>
        client.request<MusicComment>({
          method: 'POST',
          path: `/api/v1/music/${musicId}/comment`,
          json: body,
        }),
      /** 내용만 바꾼다. 시점은 바꿀 수 없다. */
      update: (commentId: number, content: string) =>
        client.request<MusicComment>({
          method: 'PATCH',
          path: `/api/v1/comment/${commentId}`,
          json: { content },
        }),
      remove: (commentId: number) =>
        client.request<void>({
          method: 'DELETE',
          path: `/api/v1/comment/${commentId}`,
          responseType: 'none',
        }),
    },

    history: {
      /**
       * 최근 들은 곡. 같은 곡은 한 번만, 최신순.
       *
       * 적재 API 는 없다. 청취 이벤트(`POST /api/v1/events`)의 `PLAY_START` 를
       * 서버가 이 표에도 남긴다.
       */
      list: (options: { page?: number; size?: number } = {}, signal?: AbortSignal) =>
        client.request<MusicPage>({
          path: '/api/v1/user/history',
          query: { page: options.page, size: options.size },
          signal,
        }),
    },

    reaction: {
      /** 좋아요·관심없음 설정. 같은 값을 다시 보내도 결과가 같다. */
      set: (musicId: number, type: ReactionType) =>
        client.request<void>({
          method: 'PUT',
          path: `/api/v1/music/${musicId}/reaction`,
          json: { reaction_type: type },
          responseType: 'none',
        }),
      /** 해제. 원래 없었어도 오류가 아니다. */
      clear: (musicId: number) =>
        client.request<void>({
          method: 'DELETE',
          path: `/api/v1/music/${musicId}/reaction`,
          responseType: 'none',
        }),
      /** 반응한 곡 번호 전부. */
      list: (signal?: AbortSignal) =>
        client.request<Reactions>({ path: '/api/v1/user/reaction', signal }),
      /** 반응한 곡을 곡 정보까지. 최근에 누른 순. */
      music: (
        options: { type?: ReactionType; page?: number; size?: number } = {},
        signal?: AbortSignal,
      ) =>
        client.request<MusicPage>({
          path: '/api/v1/user/reaction/music',
          query: { type: options.type, page: options.page, size: options.size },
          signal,
        }),
    },

    onboarding: {
      /** 고를 수 있는 분위기·장르. 마스터 데이터라 로그인 전에도 부를 수 있다. */
      options: (signal?: AbortSignal) =>
        client.request<OnboardingOptions>({
          path: '/api/v1/onboarding/option',
          anonymous: true,
          signal,
        }),
      /** 제출 여부와 제출한 내용. */
      status: (signal?: AbortSignal) =>
        client.request<OnboardingStatus>({
          path: '/api/v1/onboarding',
          signal,
        }),
      /** 제출. 이미 제출했으면 서버가 갈아 끼운다. */
      submit: (body: OnboardingBody) =>
        client.request<void>({
          method: 'POST',
          path: '/api/v1/onboarding',
          json: body,
          responseType: 'none',
        }),
    },

    music: {
      /**
       * 곡 목록·검색. 로그인 없이도 부를 수 있다.
       *
       * @param q 제목·아티스트명·앨범명 부분 일치
       */
      search: (
        options: { q?: string; page?: number; size?: number } = {},
        signal?: AbortSignal,
      ) =>
        client.request<MusicPage>({
          path: '/api/v1/music',
          query: { q: options.q, page: options.page, size: options.size },
          anonymous: true,
          signal,
        }),
      get: (musicId: number, signal?: AbortSignal) =>
        client.request<MusicSummary>({
          path: `/api/v1/music/${musicId}`,
          anonymous: true,
          signal,
        }),
      /** 가사. 시간 오름차순. 가사가 없으면 `lines` 가 빈 배열이다. */
      lyrics: (musicId: number, signal?: AbortSignal) =>
        client.request<MusicLyrics>({
          path: `/api/v1/music/${musicId}/lyric`,
          anonymous: true,
          signal,
        }),
      /** 구간·보컬·악기. 분석 전이면 `analyzed` 가 false 다. */
      analysis: (musicId: number, signal?: AbortSignal) =>
        client.request<MusicAnalysis>({
          path: `/api/v1/music/${musicId}/analysis`,
          anonymous: true,
          signal,
        }),
    },

    artist: {
      /** 이름 오름차순. `q` 는 이름 부분 일치. */
      search: (
        options: { q?: string; page?: number; size?: number } = {},
        signal?: AbortSignal,
      ) =>
        client.request<ArtistPage>({
          path: '/api/v1/artist',
          query: { q: options.q, page: options.page, size: options.size },
          anonymous: true,
          signal,
        }),
      get: (artistId: number, signal?: AbortSignal) =>
        client.request<ArtistSummary>({
          path: `/api/v1/artist/${artistId}`,
          anonymous: true,
          signal,
        }),
    },

    album: {
      /** 발매일 내림차순. `artistId` 를 주면 그 아티스트의 앨범만. */
      search: (
        options: { q?: string; artistId?: number; page?: number; size?: number } = {},
        signal?: AbortSignal,
      ) =>
        client.request<AlbumPage>({
          path: '/api/v1/album',
          // 쿼리 이름은 서버 규약대로 snake_case 다.
          query: {
            q: options.q,
            artist_id: options.artistId,
            page: options.page,
            size: options.size,
          },
          anonymous: true,
          signal,
        }),
      /** 수록곡이 함께 온다. */
      get: (albumId: number, signal?: AbortSignal) =>
        client.request<AlbumDetail>({
          path: `/api/v1/album/${albumId}`,
          anonymous: true,
          signal,
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
