'use client';
import { MupidMascot } from './mascot';
import { ArrowUp, LoaderCircle, Sparkle, X } from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
import { useKeyboardInset } from '@/hooks/use-keyboard-inset';
import { chatSuggestions, formatTime } from '@/lib/mupid-data';
import { AlbumArt, TrackRow } from './primitives';
import { useEffect, useState, type CSSProperties } from 'react';

export function FloatingChat({ model: m }: { model: MupidModel }) {
  const { chatButtonRef, chatInputRef, messageEndRef } = m;
  /*
   * 폰에서 입력칸에 커서를 두면 화상 키보드가 화면 아래 절반을 덮는다. 패널은
   * 재생 독 위에 떠 있어 그대로 키보드 뒤로 들어가고, 치는 글자가 보이지 않았다.
   *
   * 입력 중(`composing`)에는 패널을 화면 위에서 키보드 바로 위까지 펼친다
   * (`mupid.css` 의 `.chat-panel.composing`). 독을 덮지만 키보드가 떠 있는 동안은
   * 어차피 누를 수 없는 자리다. 키보드가 가린 높이는 `--keyboard-inset` 으로 건넨다.
   */
  const keyboardInset = useKeyboardInset();
  const [inputFocused, setInputFocused] = useState(false);
  const composing = m.chatOpen && (inputFocused || keyboardInset > 0);
  useEffect(() => {
    // 패널 높이가 바뀌면 마지막 말풍선이 입력칸 뒤로 숨는다. 다시 끝으로 내린다.
    if (composing) messageEndRef.current?.scrollIntoView({ block: 'end' });
  }, [composing, keyboardInset, messageEndRef]);
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
