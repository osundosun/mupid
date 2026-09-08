'use client';
import { AudioWaveform, Heart, Music2, Search, Sparkles } from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';

export function HomeShortcuts({ model: m }: { model: MupidModel }) {
  const shortcuts = [
    {
      label: '내 취향 리포트',
      lines: ['취향', '리포트'],
      icon: Heart,
      action: () => m.go('report'),
    },
    {
      label: '좋아한 구간 모음',
      lines: ['좋아한', '구간'],
      icon: AudioWaveform,
      action: () => {
        m.setSelectedPlaylist(null);
        m.setLibraryTab('moments');
        m.go('library');
      },
    },
    {
      label: '내 플레이리스트',
      lines: ['내', '플리'],
      icon: Music2,
      action: () => {
        m.setSelectedPlaylist(null);
        m.setLibraryTab('playlists');
        m.go('library');
      },
    },
    {
      label: '음악 탐색',
      lines: ['탐색'],
      icon: Search,
      action: () => m.go('search'),
    },
    {
      label: 'AI 추천',
      lines: ['AI', '추천'],
      icon: Sparkles,
      action: m.openChat,
    },
  ];
  return (
    <nav className="home-shortcuts" aria-label="홈 바로가기">
      {shortcuts.map(({ label, lines, icon: Icon, action }) => (
        <button key={label} onClick={action} aria-label={label}>
          <span className="home-shortcut-icon" aria-hidden="true">
            <Icon />
          </span>
          <span className="home-shortcut-label" aria-hidden="true">
            {lines.map((line) => (
              <span key={line}>{line}</span>
            ))}
          </span>
        </button>
      ))}
    </nav>
  );
}
