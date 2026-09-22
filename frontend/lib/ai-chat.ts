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
import type { AiChatTurn, AiRecommendedTrack, AiToolCallRecord } from './api/endpoints';
import type { ChatMessage, ChatToolCall, Track } from './mupid-data';

/** 서버로 함께 보내는 이전 대화 수. 서버도 `ai.max-history` 로 한 번 더 자른다. */
const HISTORY_LIMIT = 12;

/** 서버가 받는 한 마디 길이. 넘치면 서버가 400 을 낸다. */
const CONTENT_LIMIT = 2000;

const MESSAGE_LIMIT = 500;

export type ChatAnswer = {
  text: string;
  tracks: Track[];
  sessionId: number | null;
  /**
   * 이 턴에 일어난 툴 호출 기록 (S15P21A204-409). 그냥 대화만 했으면 빈 배열이다.
   *
   * **서버가 준 것을 그대로 옮긴다.** 예전에는 이 자리가 없어서 화면이 "3곡 골라뒀어요"
   * 라는 말만 보여 줬고, 사용자가 그것을 확인할 근거가 없었다. 실패도 함께 온다 —
   * 추천 서버가 죽었을 때 화면이 침묵하지 않게 하려는 것이다.
   */
  toolCalls: ChatToolCall[];
};

export type ChatRequest = {
  prompt: string;
  /** 지금 듣고 있는 곡. 없으면 곡 문맥 없이 답한다. */
  current: Track | null;
  /** 화면이 들고 있는 곡 전부. 추천 결과를 여기에서 찾는다. */
  catalog: Track[];
  /** 지금까지 오간 말. 이번에 보낼 말은 포함하지 않는다. */
  history: ChatMessage[];
  /**
   * 이어 갈 대화 세션. 직전 응답의 `sessionId` 를 그대로 넘긴다.
   *
   * **`null` 이면 서버가 새 세션을 연다** — 화면에 세션이 없거나(첫 턴) 사용자가
   * 대화를 지운 경우다. 여기서 값을 만들어 내지 않는다. 번호를 발급하는 것은 서버다.
   */
  sessionId?: number | null;
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
 *
 * **버리는 것은 맞지만, 버렸다는 사실을 숨기지는 않는다** (S15P21A204-421). 여기서
 * 조용히 줄어든 개수 때문에 화면이 "3곡 골라뒀어요" 라고 말하면서 카드는 2장만
 * 그리는 일이 있었다. 차이를 사용자 문구로 옮기는 것은 `missingTracksNotice` 다.
 */
/** 서버 enum → 화면 이름. 모르는 값은 그대로 보여준다 (코드는 늘 수 있다). */
const SECTION_LABELS: Record<string, string> = {
  INTRO: '인트로',
  VERSE: '벌스',
  PRE_CHORUS: '프리코러스',
  CHORUS: '코러스',
  BRIDGE: '브리지',
  BREAK: '브레이크',
  INST: '간주',
  SOLO: '솔로',
  OUTRO: '아웃트로',
};

const resolve = (
  recommended: AiRecommendedTrack[],
  catalog: Track[],
  recommendationId: number | null,
): Track[] =>
  recommended.flatMap((item) => {
    const found = catalog.find((track) => track.musicId === item.music_id);
    if (!found) return [];
    const start = item.start_time_ms / 1000;
    const end = item.end_time_ms / 1000;
    const usable = Number.isFinite(start) && start > 0 && start < found.duration;
    return [
      {
        ...found,
        // 곡 자체가 아니라 "이 구간" 을 권한 것이다. 구간 시작을 순간으로 잡는다.
        moment: usable ? start : found.moment,
        match: Math.round(Math.min(Math.max(item.score, 0), 1) * 100),
        reason: item.reason,
        recommended: {
          sectionLabel:
            SECTION_LABELS[item.section_label ?? ''] ?? item.section_label ?? '',
          start: usable ? start : found.moment,
          // 끝이 시작보다 앞서면 믿지 않는다. 구간 끝을 모르면 곡 끝까지 튼다.
          end: Number.isFinite(end) && end > start ? end : found.duration,
          reasonCode: item.reason_code,
          reason: item.reason,
          recommendationId: recommendationId ?? undefined,
          rank: item.rank_order,
        },
      },
    ];
  });

/**
 * 서버가 **추천했다고 말한** 곡 수. 툴 호출 기록(`trackCount`)에서 읽는다.
 *
 * **합치지 않고 가장 큰 한 건을 쓴다.** 모델은 한 턴에 같은 도구를 여러 번 부른다
 * (`ai.max-tool-rounds`) — 추천 서버가 흔들리면 말을 조금 바꿔 다시 부르는 것이
 * 실측으로 확인돼 있다(`chat-tool-calls.tsx` 주석). 그 3번을 더하면 9곡이 되고,
 * 화면은 "9곡 중 7곡이 빠졌어요" 라는 **새로운 거짓말**을 하게 된다. 한 번의 호출이
 * 돌려준 최대 건수는 사용자가 기록 줄에서 실제로 읽는 숫자(`곡 3건`)와도 맞는다.
 *
 * 실패한 호출은 세지 않는다. 사유는 기록 줄이 이미 보여 주고 있어, 여기서 한 번 더
 * 경고를 얹으면 같은 말이 두 줄이 된다.
 */
const recommendedCount = (calls: ChatToolCall[] | undefined): number =>
  (calls ?? []).reduce(
    (most, call) => (call.succeeded ? Math.max(most, call.trackCount) : most),
    0,
  );

/**
 * 추천 건수와 화면에 그려진 곡 수가 다르면, 그 차이를 사용자 문구로 옮긴다
 * (S15P21A204-421).
 *
 * ## 왜 필요한가
 *
 * 곡이 말없이 사라지는 자리가 둘이다.
 *   1) `resolve()` — 추천된 곡이 화면 카탈로그에 없으면 버린다. 눌러도 재생되지 않는
 *      줄을 만들지 않으려는 것이라 **버리는 판단 자체는 옳다.**
 *   2) 채팅의 곡 목록 — 답변이 온 뒤 사용자가 '관심 없음' 으로 표시한 곡을 가린다.
 *
 * 둘 다 결과만 보면 똑같다: 답변은 "3곡 골라뒀어요" 인데 카드는 2장이다. 한 곡이
 * 어디로 갔는지 화면 어디에도 없었다. 이 저장소가 반복해 겪은 조용한 실패다.
 *
 * ## 무엇을 하지 않는가
 *
 * 서버를 다시 부르지 않는다. 근거는 이미 화면에 들어와 있는 툴 호출 기록뿐이다.
 * 재생할 수 없는 곡을 목록에 되살리지도 않는다 — 빠진 **이유**만 말한다.
 *
 * ## 아무 줄도 더하지 않는 경우
 *
 * 추천이 원래 0건인 턴, 툴 호출이 아예 없던 순수 대화 턴, 그리고 빠진 곡이 없는 턴은
 * `null` 이다. 멀쩡한 답변 밑에 군더더기 한 줄을 붙이지 않는다.
 *
 * 문구는 **사용자용**이다. 내부 이름을 쓰지 않는다(`chat-tool-calls.tsx` 의 문구 원칙).
 * 카탈로그에 없어서 버린 곡과 사용자가 오기 전에 숨겨 둔 곡은 화면에서 구별되지
 * 않으므로("숨긴 곡" 은 요청 단계에서 카탈로그에서 빠진다), 둘 다 참인 말로 적는다 —
 * "지금 재생할 수 있는 목록에 없어" 다.
 */
export function missingTracksNotice(
  toolCalls: ChatToolCall[] | undefined,
  tracks: Track[] | undefined,
  ignored: number[],
): string | null {
  const recommended = recommendedCount(toolCalls);
  // 추천이 없던 턴에는 견줄 대상이 없다.
  if (recommended === 0) return null;
  const resolved = tracks?.length ?? 0;
  const shown = (tracks ?? []).filter((track) => !ignored.includes(track.id)).length;
  // 서버가 센 것보다 많이 그렸다면(같은 곡이 두 번 추천되는 등) 할 말이 없다. 0 으로 묶는다.
  const unplayable = Math.max(0, recommended - resolved);
  const hidden = Math.max(0, resolved - shown);
  if (unplayable === 0 && hidden === 0) return null;
  const parts: string[] = [];
  if (unplayable > 0) parts.push(`${unplayable}곡은 지금 재생할 수 있는 목록에 없어 빼뒀`);
  if (hidden > 0) parts.push(`${hidden}곡은 관심 없음으로 표시해 가려뒀`);
  return `추천받은 ${recommended}곡 중 ${parts.join('고, ')}어요.`;
}

/**
 * 서버의 툴 호출 기록을 화면 모양으로 옮긴다 (S15P21A204-409).
 *
 * **버리지 않는다.** `sessionId` 를 버리던 전례(378 커밋 fcefb33: `requestChat` 이
 * `result.session_id` 를 버렸다)가 있다 — 그때는 세션 번호가 화면에 없어 대화가 매 턴
 * 갈라졌다. 여기서도 같은 실수를 하면 "왜 이 곡이 나왔나" 의 근거가 다시 사라진다.
 *
 * 다만 **빈 기록은 만들지 않는다.** 툴 호출이 없던 턴에 화면이 "도구 호출" 줄을 그리면
 * 거짓말이 된다. 서버가 빈 배열을 주면 빈 배열 그대로 둔다.
 *
 * `label` 이 빈 문자열이면 내부 이름으로 되돌아간다. 모르는 툴이 와도 줄 자체는 남긴다 —
 * 없는 것보다는 낫고, 무엇을 불렀는지는 알 수 있어야 한다.
 */
const toToolCalls = (records: AiToolCallRecord[] | undefined): ChatToolCall[] =>
  (records ?? []).map((record) => ({
    name: record.name,
    label: record.label?.trim() || record.name,
    arguments: record.arguments ?? {},
    succeeded: record.succeeded !== false,
    trackCount: record.track_count ?? 0,
    failureReason: record.failure_reason ?? null,
  }));

/**
 * 한 번의 문답. 실패하면 던진다 — 화면이 안내 문구를 고른다.
 */
export async function requestChat({
  prompt,
  current,
  catalog,
  history,
  sessionId,
  signal,
}: ChatRequest): Promise<ChatAnswer> {
  const result = await api.ai.chat(
    {
      message: prompt.slice(0, MESSAGE_LIMIT),
      history: toTurns(history),
      // undefined 는 JSON 에서 빠진다. 서버는 없는 값으로 읽는다.
      music_id: current?.musicId,
      // 첫 턴이거나 대화를 막 지운 뒤면 null 이다. 그때도 키를 넣지 않는다 —
      // `null` 을 보내면 서버 검증에 걸릴 수 있고, 뜻은 "없음" 하나뿐이다.
      session_id: sessionId ?? undefined,
    },
    signal,
  );
  return {
    text: result?.reply ?? '',
    tracks: resolve(result?.tracks ?? [], catalog, result?.recommendation_id ?? null),
    // 서버가 발급(또는 재사용)한 세션 번호. 화면이 들고 있다가 다음 턴에 되돌려 보낸다.
    // 저장에 실패했으면 null 이고, 그러면 다음 턴에 새 세션이 열린다 — 답변은 정상이다.
    sessionId: result?.session_id ?? null,
    // 툴 호출 기록. 순서 그대로, 접지 않고 옮긴다.
    toolCalls: toToolCalls(result?.tool_calls),
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
