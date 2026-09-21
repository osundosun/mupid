'use client';

import { ChevronDown, Wrench } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';

import type { ChatToolCall } from '@/lib/mupid-data';

/*
 * 툴 호출 기록 (S15P21A204-409).
 *
 * ## 왜 화면에 그리는가
 *
 * 사용자 지시: "도구를 호출했으면 어떤 도구를 호출했는지 화면에 기록으로 남겨주세요".
 * 그전까지 화면에는 LLM 의 자연어 답변만 보였다 — 사용자가 "3곡 골라뒀어요" 를 믿으려면
 * 근거가 화면에 있어야 하는데, 어떤 도구를 어떤 조건으로 불렀고 몇 건이 돌아왔는지
 * (또는 **실패했는지**) 볼 곳이 없었다.
 *
 * ## 자리 — 답변 아래, 곡 목록 위
 *
 * 답변 바로 밑이라 "이 말이 어떤 도구를 거쳐 나왔는가" 로 읽히고, 곡 카드보다 위여서
 * "이 곡이 어디서 왔나" 를 묻는 순간에 시야 안에 있다. 카드 아래 두면 곡을 다 훑은
 * 뒤에야 보인다.
 *
 * ## 한 번에 하나만 펼친다 (아코디언)
 *
 * **실측으로 두 번 고쳤다.**
 *
 *   1) 처음엔 실패를 접지 않고 늘 펼쳐 뒀다. 그런데 추천 서버가 죽으면 모델이
 *      `max-tool-rounds` 를 다 쓰고 **같은 요청을 세 번** 부른다. 조건 블록이 3번
 *      그려져 약 470px — 좁은 화면에서 그 턴의 답변 자체가 밀려났다.
 *   2) 그래서 "직전과 인자가 같으면 조건을 한 번만" 이라는 규칙을 넣었는데, **live 에서
 *      무력했다.** 모델은 같은 요청을 그대로 다시 부르지 않고 **말을 살짝 바꿔** 부른다:
 *         1번째 query "비 오는 날 듣기 좋은 곡, 차분한 피아노 중심"
 *         2번째 query "비 오는 날 듣기 좋은, 차분한 피아노 중심"   ← "곡" 하나 차이
 *      문자열이 다르니 중복 판정이 서지 않아 다시 123px 씩 쌓였다.
 *
 * 사전(辭典)으로 "같은 뜻인가" 를 재는 것은 취약하다. 대신 **높이를 구조로 묶는다:**
 * 요약 줄은 호출마다 늘 보이고(번호·결과·**실패 사유**), 조건 상세는 **한 번에 하나만**
 * 펼친다. 그러면 호출이 몇 번이든 높이는 `호출 수 × 한 줄 + 상세 한 블록` 으로 묶인다.
 *
 * **실패가 조용해지지 않는다.** 사유(`실패 — 곡 추천 서버가 응답하지 않습니다.`)는 접힌
 * 요약 줄에 **그대로** 있다. 펼쳐야 보이는 것은 조건이고, 상황을 아는 데 필요한 것은
 * 사유다. 이 저장소가 반복해 겪은 "조용한 실패" 로 돌아가지 않는다.
 *
 * ## 문구
 *
 * 개발자용 로그가 아니라 **사용자 화면**이다. `recommend_music` 같은 내부 이름을 그대로
 * 노출하지 않는다. 옮기는 판단은 서버가 한다(`AiChatServiceImpl.labelOf`) — 화면마다
 * 다른 문구가 생기는 것을 막고 툴이 늘어도 한 곳만 고치면 되게 하려는 것이다.
 * 내부 이름은 시험용 훅(`data-tool`)에만 남긴다.
 */

/** 인자 값 하나를 사람이 읽는 한 줄로. */
const readArg = (value: unknown): string => {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string') return value;
  // 조건(`filters`)처럼 배열·객체가 온다. JSON 그대로가 가장 정확하고, 무엇을 걸었는지
  // 를 숨기지 않는다 — "왜 이 곡이 나왔나" 의 근거라 요약해서 뭉개면 쓸모가 준다.
  if (Array.isArray(value)) return value.map(readArg).filter(Boolean).join(' · ');
  if (typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>)
      .map(([key, one]) => `${key} ${readArg(one)}`.trim())
      .filter(Boolean)
      .join(', ');
  }
  return String(value);
};

/**
 * 인자를 `[키, 값]` 줄로 펼친다. 빈 칸은 서버가 이미 걷어냈지만 한 번 더 막는다 —
 * 모델은 조건이 없을 때도 빈 껍데기를 곧잘 만든다.
 *
 * 문자열을 `키  값` 으로 이어 붙였다가 다시 쪼개지 않는다. 키에 공백이 들어오면
 * 그 파싱이 조용히 어긋나고, 값과 키가 뒤바뀐 줄이 화면에 남는다.
 */
const argumentLines = (args: Record<string, unknown>): [string, string][] =>
  Object.entries(args)
    .map(([key, value]) => [key, readArg(value)] as [string, string])
    .filter(([, value]) => value !== '');

/** 사용자에게 보여줄 결과 요약. **실패 사유는 여기 들어간다** — 접힌 줄에 남는다. */
const resultText = (call: ChatToolCall): string => {
  if (!call.succeeded) {
    return call.failureReason ? `실패 — ${call.failureReason}` : '실패';
  }
  if (call.trackCount === 0) return '조건에 맞는 곡 없음';
  return `곡 ${call.trackCount}건`;
};

/** 한 줄 요약에 쓸 첫 번째 뜻 있는 인자. 보통 원한 느낌(`query`)이다. */
const summaryArg = (args: Record<string, unknown>): string => {
  if (typeof args.query === 'string' && args.query.trim()) return args.query.trim();
  return argumentLines(args)[0]?.[1] ?? '';
};

/**
 * 인자가 **글자 그대로** 같은가.
 *
 * 아코디언으로 높이를 묶었으므로 이 값은 이제 **표시용 힌트**일 뿐이다 — 모델이 말을
 * 살짝 바꿔 부르면(위 주석) 서지 않는다. 그래도 같은 조건으로 다시 부른 턴에는
 * `· 같은 조건` 을 붙여 "왜 같은 도구가 두 줄인가" 를 설명한다.
 */
const signature = (call: ChatToolCall): string => JSON.stringify(call.arguments ?? {});

export function ChatToolCalls({ calls }: { calls: ChatToolCall[] }) {
  // 한 번에 하나만 펼친다. 여러 개를 펼치면 높이 상한이 사라져 원래 문제로 돌아간다.
  const [openIndex, setOpenIndex] = useState<number | null>(null);
  /** 기록 묶음 자신. 펼칠 때 스크롤 영역을 찾는 데 쓴다. */
  const toolsRef = useRef<HTMLDivElement>(null);

  /**
   * 펼친 내용이 영역 밖으로 나가면 그만큼 내린다.
   *
   * **`requestAnimationFrame` 으로는 안 됐다.** 실측: 클릭 직후 rAF 콜백에서 재봤더니
   * React 가 아직 DOM 을 커밋하기 전이라 **펼치기 전 높이**가 나왔고(`hasDetail` 이 false),
   * 그래서 넘친 양이 0 으로 계산돼 스크롤이 일어나지 않았다. 펼친 뒤에도 상세 아래 37px 이
   * 화면 밖에 남았다(`rowBox` bottom 603 vs 컨테이너 566, `maxScroll` 179 인데 `scrollTop` 61).
   *
   * `useEffect` 는 **커밋 뒤에** 돌아 배치가 확정된 값을 잰다. 여기서 재야 맞다.
   *
   * `scrollIntoView` 도 `behavior:'smooth'` 도 쓰지 않는다 — 대화 패널에서 그 둘이 어떻게
   * 어긋나는지는 `chat.tsx` 주석에 있다(409 의 본질이다). 필요한 만큼만 `scrollTop` 을 민다.
   */
  useEffect(() => {
    if (openIndex === null) return;
    const tools = toolsRef.current;
    const box = tools?.closest('.chat-messages');
    if (!(box instanceof HTMLElement) || !tools) return;
    const row = tools.querySelectorAll('.chat-tool')[openIndex];
    if (!row) return;
    const gap = row.getBoundingClientRect().bottom - box.getBoundingClientRect().bottom;
    // 넘친 만큼만 민다. 접을 때는 내용이 줄어 알아서 들어오므로 건드리지 않는다.
    if (gap > 0) box.scrollTop += gap;
  }, [openIndex]);

  return (
    <div className="chat-tools" ref={toolsRef}>
      {calls.map((call, index) => {
        const lines = argumentLines(call.arguments);
        const summary = summaryArg(call.arguments);
        const open = openIndex === index;
        // 같은 툴이 두 번 불릴 수 있다(`ai.max-tool-rounds`). 순서를 번호로 남긴다 —
        // 몇 번 불렀는지가 정보이므로 합치지 않는다.
        const ordinals = calls.length > 1 ? `${index + 1}번째 ` : '';
        const isRepeat = index > 0 && signature(calls[index - 1]) === signature(call);
        return (
          <div
            className={`chat-tool${call.succeeded ? '' : ' failed'}`}
            key={`${call.name}-${index}`}
            data-tool={call.name}
            data-tool-ok={call.succeeded ? 'true' : 'false'}
            data-tool-repeat={isRepeat ? 'true' : 'false'}
          >
            <button
              type="button"
              className="chat-tool-summary"
              aria-expanded={open}
              onClick={() => setOpenIndex(open ? null : index)}
              /* 접힌 줄에도 결과가 다 있다. 실패면 사유까지 — 이것이 조용한 실패를 막는다. */
              aria-label={
                call.succeeded
                  ? `${ordinals}${call.label} 기록 ${open ? '접기' : '펼치기'}`
                  : `${ordinals}${call.label} 실패: ${call.failureReason ?? ''}`
              }
            >
              <Wrench aria-hidden="true" />
              <span className="chat-tool-name">
                {ordinals}
                {call.label}
              </span>
              <span className="chat-tool-result">
                {resultText(call)}
                {/* 같은 조건으로 다시 불렀다는 사실 자체는 남긴다 — 그것도 정보다. */}
                {isRepeat && ' · 같은 조건'}
              </span>
              <ChevronDown
                className={`chat-tool-caret${open ? ' open' : ''}`}
                aria-hidden="true"
              />
            </button>
            {open && (
              <div className="chat-tool-detail">
                {summary && <p className="chat-tool-query">‘{summary}’</p>}
                {lines.length > 0 && (
                  <dl className="chat-tool-args">
                    {lines.map(([key, value]) => (
                      <div className="chat-tool-arg" key={key}>
                        <dt>{key}</dt>
                        <dd>{value}</dd>
                      </div>
                    ))}
                  </dl>
                )}
                {!call.succeeded && call.failureReason && (
                  <p className="chat-tool-failure">{call.failureReason}</p>
                )}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
