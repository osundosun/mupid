'use client';
import { MupidMascot } from './mascot';
import { ArrowUp, LoaderCircle, Sparkle, SquarePen, X } from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
import { useKeyboardInset } from '@/hooks/use-keyboard-inset';
import { chatSuggestions, formatTime } from '@/lib/mupid-data';
import { AlbumArt, TrackRow } from './primitives';
import { ChatToolCalls } from './chat-tool-calls';
import { ChatTracksNote } from './chat-tracks-note';
import { useEffect, useState, type CSSProperties } from 'react';

export function FloatingChat({ model: m }: { model: MupidModel }) {
  const { chatButtonRef, chatInputRef, chatBoxRef, messageEndRef } = m;
  /*
   * 스크롤 대상은 **대화 영역(`.chat-messages`) 자신**이다 (S15P21A204-409).
   *
   * 끝을 가리키는 `messageEndRef` 에 기대지 않는다. 영역의 `scrollTop` 을 끝으로 밀면
   * 곡 카드까지 함께 보이기 때문이다 — 실측 546(`block:'end'`) vs 564(끝까지, 최대).
   * 참조 자체는 남겨 둔다. 다른 화면이 아직 끝을 가리킬 때 쓴다.
   */
  /*
   * 폰에서 입력칸에 커서를 두면 화상 키보드가 화면 아래 절반을 덮는다. 패널은
   * 재생 독 위에 떠 있어 그대로 키보드 뒤로 들어가고, 치는 글자가 보이지 않았다.
   *
   * 폰에서는 패널이 화면 전체를 채우고(`chat-fullscreen.css`), 키보드가 가린 높이
   * (`--keyboard-inset`)만큼 아래를 비운다. **입력 중(`composing`)이라고 틀을 바꾸지
   * 않는다** — 전에는 바꿨고, 그래서 X 를 누르는 순간 입력칸의 포커스가 빠지며 패널이
   * 내려앉아 첫 탭이 뒤쪽 화면에 떨어졌다(S15P21A204-438).
   */
  const keyboardInset = useKeyboardInset();
  const [inputFocused, setInputFocused] = useState(false);
  const composing = m.chatOpen && (inputFocused || keyboardInset > 0);

  /*
   * 새 내용이 생기면 **대화 영역 자체를** 아래 끝으로 내린다 (S15P21A204-409).
   *
   * 이 자리에는 `if (composing) messageEndRef.current?.scrollIntoView(...)` 가 있었다.
   * 두 가지가 동시에 어긋나 곡 카드가 화면 밖에 남았다 — 실측값은 커밋 메시지에 있다.
   *
   *  1) **의존성이 틀렸다.** `composing` 은 "지금 입력 중인가" 이지 "새 말이 왔는가" 가
   *     아니다. 사용자가 입력칸에 커서를 두는 순간 `true` 가 되고, 그 뒤로는 응답이
   *     도착해도 값이 변하지 않는다. 그래서 곡 카드가 붙는 순간에는 효과가 다시 돌지
   *     않았다. 패널에 `composing` 이 붙어 있는 동안에도 스크롤이 안 된 이유가 이것이다.
   *  2) **`behavior: 'smooth'` 가 멈춰 있었다.** `use-mupid.ts` 가 `smooth` 로 부르는데,
   *     이 환경에서 `scrollIntoView({behavior:'smooth'})` 는 즉시 0 을 돌려주고 3.5초를
   *     기다려도 `scrollTop` 이 0 에 머물렀다(`auto` 는 546 으로 즉시 움직였다).
   *     **"응답이 왔는데 화면이 안 움직인다" 가 이 티켓의 본질이다.**
   *
   * 그래서 `composing` 에 기대지 않고 **무엇이 늘어났는지**를 의존성으로 삼는다:
   * 말풍선 개수(`m.messages.length`)·`m.chatLoading`·`composing`. `tracks` 는 말풍선이
   * 늘어난 뒤 같은 말풍선에 나중에 붙을 수 있어, 개수만 보면 놓치는 경우가 남는다.
   * 마지막에 붙은 말풍선의 곡 목록 길이까지 함께 본다.
   *
   * `scrollIntoView` 대신 **컨테이너의 `scrollTop` 을 직접 끝으로 민다.** 곡 카드와
   * `messageEndRef` 둘 다 영역 안에 든다는 것이 실측으로 확인됐다(546 vs 564 — 끝까지
   * 내리면 카드가 온전히 보인다). `smooth` 를 쓰지 않으므로 도착 지점이 곧바로 확정되고,
   * 애니메이션이 예약한 스크롤이 다음 렌더에 밀려나는 일도 없다.
   *
   * 키보드가 올라와 패널 높이가 바뀌는 경우도 같은 효과가 받는다(`composing` 이 의존성).
   */
  const lastMessage = m.messages[m.messages.length - 1];
  const trackCount = lastMessage?.tracks?.length ?? 0;
  useEffect(() => {
    const box = chatBoxRef.current;
    if (!box) return;
    // 끝까지 내린다. `smooth` 를 쓰지 않는 이유는 위 주석에 있다.
    box.scrollTop = box.scrollHeight;
  }, [m.chatOpen, m.messages.length, trackCount, m.chatLoading, composing, chatBoxRef]);
  if (!m.chatOpen)
    return (
      <button
        className="chat-launcher"
        aria-label="Mupid AI 음악 탐색 열기"
        ref={chatButtonRef}
        onClick={m.openChat}
      >
        <Sparkle aria-hidden="true" />
      </button>
    );
  return (
    <aside
      className={`chat-panel ${composing ? 'composing' : ''}`}
      style={{ '--keyboard-inset': `${keyboardInset}px` } as CSSProperties}
      aria-label="Mupid AI 음악 탐색"
    >
      <header className="chat-header">
        <span className="chat-symbol">
          <Sparkle />
        </span>
        <div>
          <h2>
            Mupid AI
          </h2>
          <p>저장한 순간에서, 다음 음악으로</p>
        </div>
        {/* 지금까지의 말을 지우고 새 대화를 연다. 서버 세션도 함께 끊는다
            (`startNewChat`) — 안 끊으면 지운 대화에 다음 턴이 이어 붙는다. */}
        <button
          className="icon-button"
          aria-label="새 대화 시작"
          title="새 대화"
          onClick={m.startNewChat}
          disabled={m.chatLoading}
        >
          <SquarePen />
        </button>
        <button
          className="icon-button"
          aria-label="AI 음악 탐색 닫기"
          onClick={m.closeChat}
        >
          <X />
        </button>
      </header>
      {/* 아직 고른 곡이 없으면 이 줄은 비운다. 대화 자체는 그대로 쓸 수 있다. */}
      {m.active && <div className="chat-source">
        <AlbumArt track={m.active} />
        <div>
          <span>지금 함께 듣는 곡</span>
          <strong>
            {m.active.title} · {m.active.artist}
          </strong>
        </div>
        <span className="live-dot" />
      </div>}
      <div
        className="chat-messages"
        ref={chatBoxRef}
        aria-live="polite"
        aria-relevant="additions text"
        aria-busy={m.chatLoading}
      >
        {m.messages.map((message) => (
          <div className={`chat-message ${message.role}`} key={message.id}>
            {message.role === 'assistant' && (
              <MupidMascot size={38} className="chat-avatar" />
            )}
            <div className="message-body">
              <p className="chat-handwriting">{message.text}</p>
              {/*
                툴 호출 기록 (S15P21A204-409).

                **사용자 요구**: "도구를 호출했으면 어떤 도구를 호출했는지 화면에 기록으로
                남겨주세요". 지금까지는 LLM 의 자연어 답변만 보여서, 사용자가 "3곡
                골라뒀어요" 를 믿을 근거가 화면에 없었다. 실패도 여기에 남는다 — 예전에는
                추천 서버 장애가 조용히 삼켜져 "추천이 안 온다" 로만 보였다.

                자리 잡기: 답변(`p`) **아래, 곡 목록 위**. 왜 그 순서인가 —
                  · 답변 바로 밑이라 "이 말이 어떤 도구를 거쳐 나왔는가" 로 읽힌다.
                  · 곡 목록보다 **위**여야 한다. 카드 아래 두면 곡을 다 훑고 나서야 보이고,
                    정작 "이 곡이 어디서 왔나" 를 묻는 순간에는 시야 밖이다.
                  · 말풍선(`.message-body`) 안이다. 밖에 두면 어느 답변의 기록인지 모른다.

                **문구는 개발자용이 아니라 사용자용이다.** `recommend_music` 같은 내부
                이름을 그대로 노출하지 않고 서버가 옮겨 준 `label`(`음악 추천`)을 쓴다.
                내부 이름은 화면에 그리지 않는다 — 시험용 훅(`data-tool`)에만 남긴다.

                **툴 호출이 없던 턴에는 아무것도 그리지 않는다.** 그냥 대화만 한 경우다.
              */}
              {message.toolCalls && message.toolCalls.length > 0 && (
                <ChatToolCalls calls={message.toolCalls} />
              )}
              {message.tracks && (
                <div className="chat-tracks">
                  {message.tracks.filter(track => !m.ignored.includes(track.id)).map((track) => (
                    <div className="chat-track" key={track.id}>
                      <TrackRow
                        track={track}
                        model={m}
                        queueable
                        // 곡이 아니라 **이 구간**을 권한 것이다. 0초부터 틀면
                        // 목록에 적힌 구간과 들리는 것이 달라진다.
                        onPlay={() => m.play(track, true)}
                      />
                      {track.recommended && (
                        <p className="chat-track-reason">
                          {/* 채팅 목록은 `.moment-meta` 를 숨긴다. 구간을 권한 것이
                              보이지 않으면 재생이 곡 중간에서 시작하는 이유를 알 수
                              없어, 여기에 구간과 시작 시각을 적는다. */}
                          <strong>
                            {track.recommended.sectionLabel} ·{' '}
                            {formatTime(track.recommended.start)}
                          </strong>
                          <span>{track.recommended.reason}</span>
                          {track.match > 0 && <em>{track.match}% 맞음</em>}
                        </p>
                      )}
                    </div>
                  ))}
                </div>
              )}
              {/*
                추천 건수와 실제로 그려진 곡 수의 차이 (S15P21A204-421).

                위의 `filter` 와 `resolve()`(`lib/ai-chat.ts`)는 **말없이** 곡을 지운다 —
                재생할 수 없는 곡, 그리고 답변이 온 뒤 '관심 없음' 으로 표시한 곡이다.
                그래서 답변은 "3곡 골라뒀어요", 기록 줄은 `곡 3건` 인데 카드는 2장인
                화면이 나왔다. 여기서 그 차이를 사용자 문구로 남긴다.

                **곡 목록 밖이다.** 추천이 전부 사라지면 `message.tracks` 가 `undefined`
                라 위 블록이 통째로 빠지는데, 그때가 사용자가 가장 헷갈리는 경우다.
                더할 말이 없는 턴에는 스스로 `null` 을 돌려준다.
              */}
              <ChatTracksNote
                calls={message.toolCalls}
                tracks={message.tracks}
                ignored={m.ignored}
              />
            </div>
          </div>
        ))}
        {m.chatLoading && (
          <div className="thinking chat-handwriting">
            <LoaderCircle className="spin" />
            어울리는 음악을 찾고 있어요
          </div>
        )}
        <div ref={messageEndRef} />
      </div>
      {m.messages.length < 3 && (
        <div className="chat-suggestions">
          {chatSuggestions.slice(0, 3).map((text) => (
            <button
              key={text}
              className="chat-handwriting"
              onClick={() => m.sendMessage(text)}
              disabled={m.chatLoading}
            >
              {text}
            </button>
          ))}
        </div>
      )}
      <footer className="chat-footer">
        <form
          className="chat-composer"
          onSubmit={(e) => {
            e.preventDefault();
            m.sendMessage();
          }}
        >
          <input
            ref={chatInputRef}
            className="chat-handwriting"
            aria-label="원하는 음악 분위기"
            placeholder="조금 더 잔잔한 곡을 추천해 줘"
            value={m.chatInput}
            onChange={(e) => m.setChatInput(e.target.value)}
            onFocus={() => setInputFocused(true)}
            onBlur={() => setInputFocused(false)}
            maxLength={500}
          />
          <button
            type="submit"
            className="icon-button play-main"
            aria-label="메시지 보내기"
            disabled={!m.chatInput.trim() || m.chatLoading}
          >
            <ArrowUp />
          </button>
        </form>
      </footer>
    </aside>
  );
}
