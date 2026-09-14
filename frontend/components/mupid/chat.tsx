'use client';
import { MupidMascot } from './mascot';
import { ArrowUp, LoaderCircle, Sparkles, X } from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
import { chatSuggestions } from '@/lib/mupid-data';
import { AlbumArt, TrackRow } from './primitives';

export function FloatingChat({ model: m }: { model: MupidModel }) {
  const { chatButtonRef, chatInputRef, messageEndRef } = m;
  if (!m.chatOpen)
    return (
      <button
        className="chat-launcher"
        aria-label="Mupid AI 음악 탐색 열기"
        ref={chatButtonRef}
        onClick={m.openChat}
      >
        <span className="chat-launcher-label">AI</span>
      </button>
    );
  return (
    <aside className="chat-panel" aria-label="Mupid AI 음악 탐색">
      <header className="chat-header">
        <span className="chat-symbol">
          <Sparkles />
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
      <div className="chat-source">
        <AlbumArt track={m.active} />
        <div>
          <span>지금 함께 듣는 곡</span>
          <strong>
            {m.active.title} · {m.active.artist}
          </strong>
        </div>
        <span className="live-dot" />
      </div>
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
                  {message.tracks.map((track) => (
                    <TrackRow key={track.id} track={track} model={m} queueable />
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
