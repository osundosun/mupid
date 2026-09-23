'use client';
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { Moon, Sun } from 'lucide-react';

type Theme = 'light' | 'dark';
const ThemeContext = createContext({ theme: 'light' as Theme, toggle: () => {} });
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setTheme] = useState<Theme>('light');
  useEffect(() => {
    // 기본은 밝은 화면이다. 시스템 설정을 따르지 않고, 사용자가 직접 켠 경우에만 다크 모드.
    const sync = () => {
      let saved: string | null = null;
      try { saved = localStorage.getItem('mupid-theme'); } catch {}
      const next = saved === 'dark' ? 'dark' : 'light';
      setTheme(next);
      document.documentElement.classList.toggle('dark', next === 'dark');
      document.documentElement.style.colorScheme = next;
    };
    sync();
    window.addEventListener('storage', sync);
    return () => window.removeEventListener('storage', sync);
  }, []);
  const toggle = () => {
    const next = theme === 'dark' ? 'light' : 'dark';
    try { localStorage.setItem('mupid-theme', next); } catch {}
    document.documentElement.classList.toggle('dark', next === 'dark');
    document.documentElement.style.colorScheme = next;
    setTheme(next);
  };
  return <ThemeContext.Provider value={{ theme, toggle }}>{children}</ThemeContext.Provider>;
}

export function ThemeToggle() {
  const { theme, toggle } = useContext(ThemeContext);
  return <button type="button" className="theme-toggle" aria-pressed={theme === 'dark'} onClick={toggle}>
    {theme === 'dark' ? <Moon size={18} /> : <Sun size={18} />}<span>다크 모드</span><small>{theme === 'dark' ? '켜짐' : '꺼짐'}</small>
  </button>;
}
