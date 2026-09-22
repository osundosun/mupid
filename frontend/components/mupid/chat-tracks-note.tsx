'use client';

import { missingTracksNotice } from '@/lib/ai-chat';
import type { ChatToolCall, Track } from '@/lib/mupid-data';

/*
 * 빠진 곡을 알리는 한 줄 (S15P21A204-421).
 *
 * ## 왜 있는가
 *
 * 챗봇이 "3곡 골라뒀어요" 라고 답하고 툴 호출 기록에도 `곡 3건` 이 찍히는데 카드는
 * 2장만 그려졌다. 한 곡이 **말없이** 사라졌고, 사용자는 어디로 갔는지 알 방법이 없었다.
 *
 * 지금 곡이 빠지는 자리는 **사용자가 '관심 없음' 으로 표시한 것** 하나뿐이다. 자기가 한
 * 일이라 이유를 들으면 납득한다.
 *
 * 빠지는 자리가 둘 더 있었고 둘 다 없앴다 — 화면 카탈로그(앞쪽 100곡) 밖이라 버리던 것
 * (S15P21A204-424), 그리고 재생할 수 없는 곡(S15P21A204-426). 뒤엣것은 서버가 아예
 * 내려보내지 않고 추천 건수에서도 빼므로, "3곡 중 1곡은 아직 재생할 수 없어" 처럼
 * **사용자가 할 수 있는 일이 없는 의문**이 애초에 생기지 않는다.
 *
 * 세는 규칙과 문구는 `lib/ai-chat.ts` 의 `missingTracksNotice` 에 있다. 이 컴포넌트는
 * 그 결과를 곡 목록 자리에 놓기만 한다 — 화면마다 다른 문구가 생기는 것을 막으려고
 * 판단을 한 곳에 모아 뒀다(툴 호출 기록의 `label` 을 서버가 정하는 것과 같은 이유다).
 *
 * ## 자리 — 곡 카드 **아래**
 *
 * 툴 호출 기록과 반대다. 기록은 "이 말이 어떤 도구를 거쳐 나왔나" 라서 답변 바로 밑에
 * 있어야 하지만, 이 줄은 **목록을 다 본 뒤** 생기는 의문("세 곡이라더니?")에 답한다.
 * 카드보다 위에 두면 아직 묻지 않은 질문에 먼저 답하는 꼴이고, 정작 곡 카드는 한 줄만큼
 * 더 아래로 밀린다.
 *
 * 곡이 **하나도** 남지 않은 턴에도 그려져야 한다. 그때는 `message.tracks` 자체가
 * `undefined` 라(`use-mupid.ts` 가 빈 배열을 넣지 않는다) 곡 목록 블록이 통째로 빠진다.
 * 그래서 이 줄은 곡 목록 **밖**, 말풍선 안에 선다 — 추천이 전부 사라진 경우가 바로
 * 사용자가 가장 헷갈리는 경우다.
 *
 * 더할 말이 없으면 `null` 이다. 멀쩡한 답변 아래 빈 자리를 만들지 않는다.
 */
export function ChatTracksNote({
  calls,
  tracks,
  ignored,
}: {
  calls: ChatToolCall[] | undefined;
  tracks: Track[] | undefined;
  ignored: number[];
}) {
  const notice = missingTracksNotice(calls, tracks, ignored);
  if (!notice) return null;
  return <p className="chat-tracks-note">{notice}</p>;
}
