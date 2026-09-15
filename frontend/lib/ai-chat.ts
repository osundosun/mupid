/**
 * Mupid AI 대화.
 *
 * 채팅 패널(`components/mupid/chat.tsx`)이 보내는 말을 서버로 넘기고, 답변과 추천
 * 곡을 화면 형식으로 옮긴다. 예전에는 `deriveChatRecommendations` 가 브라우저에서
 * 곡을 골랐지만, 지금은 서버(`POST /api/v1/ai/chat`)가 LLM 과 추천 툴을 부른다.
 *
 * **브라우저는 Spring API 만 부른다.** 게이트웨이 키도 추천 서버 주소도 화면에
 * 두지 않는다 (`docs/server-integration.md`).
 */

import { api } from './api/session';
import { ApiError } from './api/client';
import type { AiChatTurn, AiRecommendedTrack } from './api/endpoints';
import type { ChatMessage, Track } from './mupid-data';

/** 서버로 함께 보내는 이전 대화 수. 서버도 `ai.max-history` 로 한 번 더 자른다. */
const HISTORY_LIMIT = 12;

/** 서버가 받는 한 마디 길이. 넘치면 서버가 400 을 낸다. */
const CONTENT_LIMIT = 2000;

const MESSAGE_LIMIT = 500;

export type ChatAnswer = { text: string; tracks: Track[] };

export type ChatRequest = {
  prompt: string;
  /** 지금 듣고 있는 곡. 없으면 곡 문맥 없이 답한다. */
  current: Track | null;
  /** 화면이 들고 있는 곡 전부. 추천 결과를 여기에서 찾는다. */
  catalog: Track[];
  /** 지금까지 오간 말. 이번에 보낼 말은 포함하지 않는다. */
  history: ChatMessage[];
  signal?: AbortSignal;
};

const toTurns = (messages: ChatMessage[]): AiChatTurn[] =>
  messages.slice(-HISTORY_LIMIT).map((message) => ({
    role: message.role === 'assistant' ? 'AI' : 'USER',
    content: message.text.slice(0, CONTENT_LIMIT),
  }));

/**
 * 추천 결과를 화면의 곡에 얹는다.
 *
 * **카탈로그에 없는 곡은 버린다.** 서버가 준 제목·아티스트만으로 줄을 그리면
 * 눌러도 재생되지 않는 곡이 목록에 남는다. 서버 쪽 추천이 목업인 동안에는
 * 대부분 버려지고 답변만 남는다 — 빈 목록이지 오류가 아니다.
 */
const resolve = (
  recommended: AiRecommendedTrack[],
  catalog: Track[],
): Track[] =>
  recommended.flatMap((item) => {
    const found = catalog.find((track) => track.musicId === item.music_id);
    if (!found) return [];
    const start = item.start_time_ms / 1000;
    return [
      {
        ...found,
        // 곡 자체가 아니라 "이 구간" 을 권한 것이다. 구간 시작을 순간으로 잡는다.
        moment:
          Number.isFinite(start) && start > 0 && start < found.duration
            ? start
            : found.moment,
        match: Math.round(Math.min(Math.max(item.score, 0), 1) * 100),
        reason: item.reason,
      },
    ];
  });

/**
 * 한 번의 문답. 실패하면 던진다 — 화면이 안내 문구를 고른다.
 */
export async function requestChat({
  prompt,
  current,
  catalog,
  history,
  signal,
}: ChatRequest): Promise<ChatAnswer> {
  const result = await api.ai.chat(
    {
      message: prompt.slice(0, MESSAGE_LIMIT),
      history: toTurns(history),
      // undefined 는 JSON 에서 빠진다. 서버는 없는 값으로 읽는다.
      music_id: current?.musicId,
    },
    signal,
  );
  return {
    text: result?.reply ?? '',
    tracks: resolve(result?.tracks ?? [], catalog),
  };
}

/**
 * 실패를 사용자 문구로 바꾼다.
 *
 * 서버가 준 문구(`AI 대화 기능이 설정되지 않았습니다.` 등)를 그대로 쓴다.
 * "다시 시도해 주세요" 로 뭉뚱그리면 키가 없어서 절대 되지 않는 상황에도
 * 사용자가 계속 다시 누른다.
 */
export function chatErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.message) return error.message;
  return '답변을 받지 못했어요. 잠시 후 다시 요청해 주세요.';
}
