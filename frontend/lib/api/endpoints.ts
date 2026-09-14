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

export type PlaylistSummary = {
  playlist_id: number;
  title: string;
  music_count: number;
  created_at: string;
  /** 목록 조회에만 있다. 생성 응답에는 없다. */
  updated_at?: string;
};

/** 플레이리스트에 담긴 곡 하나. */
export type PlaylistTrackItem = {
  /** 1부터. 플레이리스트 안에서의 재생 순서다. */
  track_order: number;
  music_id: number;
  title: string;
  artist_name: string;
  album_title: string;
  /** 재생 길이(ms). 아직 인코딩되지 않은 곡은 null 이다. */
  duration_ms: number | null;
  /** 누가 골랐는지. AI 추천으로 담긴 곡과 구분한다. */
  selector: 'USER' | 'AI';
  added_at: string;
};

/** 플레이리스트 하나 + 수록곡 전부. */
export type PlaylistDetail = PlaylistSummary & {
  updated_at: string;
  /**
   * 트랙 순서 오름차순. 삭제된 곡은 빠지므로 길이가 `music_count` 와
   * 다를 수 있다 — 화면에 보이는 곡 수는 이 배열의 길이를 쓴다.
   */
  tracks: PlaylistTrackItem[];
};

/** 곡 추가 결과. `duplicated` 가 true 면 아무것도 추가되지 않았다. */
export type PlaylistTrackAdded = {
  playlist_id: number;
  music_id: number;
  /** 수록곡의 트랙 순서. 새로 담았으면 마지막 순서 + 1. */
  track_order: number;
  /** 추가 후 수록곡 수. 중복이면 변하지 않는다. */
  music_count: number;
  duplicated: boolean;
  created_at: string;
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

/** AI 대화에 실어 보내는 이전 한 마디. 역할 이름은 서버 enum 이라 대문자다. */
export type AiChatTurn = { role: 'USER' | 'AI'; content: string };

export type AiChatBody = {
  /** 이번에 보낼 말. 500자까지. */
  message: string;
  history?: AiChatTurn[];
  /** 지금 듣고 있는 곡. 서버가 이 번호로 곡 문맥을 만든다. */
  music_id?: number;
};

/**
 * 추천 툴이 돌려준 곡 하나.
 *
 * 필드는 추천 서버(`ai_server`)의 `/internal` 계약과 같다. **`music_id` 가
 * 카탈로그에 없을 수 있다** — 지금 서버 쪽 추천이 목업이면 그렇다. 화면은
 * 카탈로그에서 찾은 곡만 보여 준다 (`lib/ai-chat.ts`).
 */
export type AiRecommendedTrack = {
  rank_order: number;
  music_id: number;
  title: string;
  artist: string;
  album: string;
  image_url: string | null;
  music_section_id: number | null;
  /** INTRO · VERSE · PRE_CHORUS · CHORUS · BRIDGE · BREAK · INST · SOLO · OUTRO */
  section_label: string | null;
  start_time_ms: number;
  end_time_ms: number;
  /** 0~1. 화면에서는 일치율(%)로 쓴다. */
  score: number;
  reason_code: string;
  reason: string;
};

/** 추천을 하지 않았으면 `tracks` 는 빈 배열이다 (null 이 아니다). */
export type AiChatResult = { reply: string; tracks: AiRecommendedTrack[] };

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
          signal,
        }),
      get: (musicId: number, signal?: AbortSignal) =>
        client.request<MusicSummary>({
          path: `/api/v1/music/${musicId}`,
          signal,
        }),
      /** 가사. 시간 오름차순. 가사가 없으면 `lines` 가 빈 배열이다. */
      lyrics: (musicId: number, signal?: AbortSignal) =>
        client.request<MusicLyrics>({
          path: `/api/v1/music/${musicId}/lyric`,
          signal,
        }),
      /** 구간·보컬·악기. 분석 전이면 `analyzed` 가 false 다. */
      analysis: (musicId: number, signal?: AbortSignal) =>
        client.request<MusicAnalysis>({
          path: `/api/v1/music/${musicId}/analysis`,
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
          signal,
        }),
      get: (artistId: number, signal?: AbortSignal) =>
        client.request<ArtistSummary>({
          path: `/api/v1/artist/${artistId}`,
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
          signal,
        }),
      /** 수록곡이 함께 온다. */
      get: (albumId: number, signal?: AbortSignal) =>
        client.request<AlbumDetail>({
          path: `/api/v1/album/${albumId}`,
          signal,
        }),
    },

    playlist: {
      /**
       * 내 플레이리스트 목록. 최근에 만든 것부터 온다.
       *
       * 수록곡은 들어 있지 않다 — 곡 수(`music_count`)만 온다. 화면에 곡을 채우려면
       * 플레이리스트별 수록곡 조회 API 가 따로 필요하다.
       */
      list: (signal?: AbortSignal) =>
        client
          .request<{ playlists: PlaylistSummary[] }>({
            path: '/api/v1/playlist',
            signal,
          })
          .then((value) => value?.playlists ?? []),
      /** 플레이리스트 하나와 수록곡 전부. 곡은 트랙 순서대로 온다. */
      detail: (playlistId: number, signal?: AbortSignal) =>
        client.request<PlaylistDetail>({
          path: `/api/v1/playlist/${playlistId}`,
          signal,
        }),
      /**
       * 플레이리스트를 지운다. 서버는 소프트 삭제라 되살릴 수 있지만
       * 목록·상세에서는 즉시 사라진다. 이미 지운 것을 또 지우면 404 다.
       */
      remove: (playlistId: number) =>
        client.request<void>({
          method: 'DELETE',
          path: `/api/v1/playlist/${playlistId}`,
          responseType: 'none',
        }),
      /** 빈 플레이리스트를 만든다. 수록곡은 따로 추가한다. */
      create: (title: string) =>
        client.request<PlaylistSummary>({
          method: 'POST',
          path: '/api/v1/playlist',
          json: { title },
        }),
      /**
       * 플레이리스트 맨 뒤에 곡 한 곡을 추가한다.
       *
       * 이미 담긴 곡이어도 성공(200)이다. 중복은 실패가 아니라 "이미 원하는 상태"라
       * 서버가 오류 대신 `duplicated: true` 로 알려 준다. 이때는 아무것도 저장되지
       * 않았으므로 `track_order` · `created_at` 은 기존 수록곡의 값이고
       * `music_count` 도 그대로다.
       */
      addTrack: (playlistId: number, musicId: number) =>
        client.request<PlaylistTrackAdded>({
          method: 'POST',
          path: `/api/v1/playlist/${playlistId}/track`,
          json: { music_id: musicId },
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

    ai: {
      /**
       * AI 대화 한 번. 곡 추천이 필요하다고 모델이 판단하면 `tracks` 가 함께 온다.
       *
       * **서버가 대화를 저장하지 않는다.** 이전 대화는 `history` 로 매번 함께 보낸다.
       *
       * 기본 타임아웃(15초)으로는 모자란다. 추론 모델이라 답까지 수십 초가 걸리고,
       * 툴을 부르면 게이트웨이를 두 번 탄다. 서버 쪽 상한이 60초라 그보다 조금 길게 준다.
       */
      chat: (body: AiChatBody, signal?: AbortSignal) =>
        client.request<AiChatResult>({
          method: 'POST',
          path: '/api/v1/ai/chat',
          json: body,
          signal,
          timeoutMs: 70000,
        }),
    },
  };
}

export type Endpoints = ReturnType<typeof createEndpoints>;
