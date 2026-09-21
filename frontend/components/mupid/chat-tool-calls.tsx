'use client';

import { ChevronDown, Wrench } from 'lucide-react';
import { useState } from 'react';

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
 * ## 접었다 편다
 *
 * 기본은 **한 줄**이다. 기록은 근거이고, 근거는 필요할 때 펼치면 된다 — 늘 펼쳐 두면
 * 잡담 한 턴에도 조건 JSON 이 줄줄이 붙어 대화가 읽히지 않는다.
 *
 * 다만 **실패는 접지 않는다.** 실패한 호출은 사유까지 그대로 보여준다. 접어 두면
 * "추천이 왜 안 왔는지" 를 알려는 사람이 한 번 더 눌러야 하고, 무엇보다 이 저장소는
 * **조용한 실패**를 반복해서 겪었다 — 접는 순간 그것이 다시 조용해진다.
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

/** 사용자에게 보여줄 결과 요약. */
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
 * "직전 호출과 같은 조건인가" 를 가리는 서명.
 *
 * 실측 근거: 추천 서버가 죽으면 모델이 `max-tool-rounds` 를 다 쓰고 **같은 인자로 세 번**
 * 부른다. 그때 조건 줄을 매번 그리면 패널이 똑같은 글로 뒤덮였다 — 실패 3회에 약 470px,
 * 좁은 화면에서는 그 턴의 답변 자체가 밀려난다.
 *
 * **기록은 접지 않는다.** 호출 수 자체가 정보라 3건을 3줄로 그대로 남긴다(번호가 붙는다).
 * 다만 **인자는 똑같으므로 한 번만** 보여준다. 같은 글을 세 번 읽는 것은 정보가 아니라
 * 소음이다.
 */
const signature = (call: ChatToolCall): string => JSON.stringify(call.arguments ?? {});

export function ChatToolCalls({ calls }: { calls: ChatToolCall[] }) {
  const [open, setOpen] = useState(false);

  /**
   * 접었다 펴기. **펼친 내용이 영역 밖으로 나가면 그만큼 내린다.**
   *
   * 실측: 펼치면 기록 높이가 21px → 84px 로 늘고 `maxScroll` 이 213 → 275 가 되는데
   * `scrollTop` 은 213 에 머물렀다. 아래 62px 이 화면 밖으로 밀려 `limit` 줄이 안 보였다.
   * 근거를 보려고 편 것인데 접힌 것보다 덜 보이면 뜻이 없다.
   *
   * `scrollIntoView` 도 `behavior:'smooth'` 도 쓰지 않는다 — 대화 패널에서 그 둘이 어떻게
   * 어긋나는지는 `chat.tsx` 주석에 있다(409 의 본질이다). 필요한 만큼만 `scrollTop` 을 민다.
   */
  const toggle = (node: HTMLElement | null) => {
    if (open) {
      // 접을 때는 자리를 건드리지 않는다. 접히면 내용이 줄어 알아서 들어온다.
      setOpen(false);
      return;
    }
    setOpen(true);
    requestAnimationFrame(() => {
      const box = node?.closest('.chat-messages');
      if (!(box instanceof HTMLElement) || !node) return;
      const gap = node.getBoundingClientRect().bottom - box.getBoundingClientRect().bottom;
      if (gap > 0) box.scrollTop += gap;
    });
  };

  return (
    <div className="chat-tools">
      {calls.map((call, index) => {
        const lines = argumentLines(call.arguments);
        const summary = summaryArg(call.arguments);
        // 같은 툴이 두 번 불릴 수 있다(`ai.max-tool-rounds`). 순서를 번호로 남긴다 —
        // 몇 번 불렀는지가 정보이므로 합치지 않는다.
        const ordinals = calls.length > 1 ? `${index + 1}번째 ` : '';
        // 직전 호출과 인자가 같으면 조건 줄을 다시 그리지 않는다(`signature` 주석).
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
              aria-expanded={call.succeeded ? open : false}
              disabled={!call.succeeded}
              onClick={(event) => toggle(event.currentTarget.parentElement)}
              /* 접히는 것은 성공한 호출뿐이다. 실패는 사유까지 늘 보인다(`위 주석`). */
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
                {/* 같은 인자로 다시 불렀다는 사실 자체는 남긴다 — 그것도 정보다. */}
                {isRepeat && ' · 같은 조건'}
              </span>
              {call.succeeded && (
                <ChevronDown
                  className={`chat-tool-caret${open ? ' open' : ''}`}
                  aria-hidden="true"
                />
              )}
            </button>
            {/* 실패는 접지 않는다 — 조용한 실패로 돌아가지 않기 위해서다.
                같은 조건으로 다시 부른 줄은 인자를 한 번만 보여준다(`signature` 주석). */}
            {!isRepeat && (!call.succeeded || open) && (
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
