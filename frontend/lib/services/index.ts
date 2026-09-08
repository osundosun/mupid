import { tracks, initialMoments, initialPlaylists, deriveChatRecommendations } from '../mupid-data';
import type { MupidService, Preferences } from './contracts';

const preferencesKey = 'mupid.sky.preferences.v1';
const photoKey = 'mupid-profile-photo';
const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object';
const defaults: Preferences = { onboardingSubmittedAt: null, nickname: '규원', moods: ['몽환적인', '감성적인'],
  liked: [1, 3, 7], ignored: [], history: [1, 3, 2], moments: initialMoments, playlists: initialPlaylists };
const unavailable = async (): Promise<never> => {
  throw new Error('서버가 아직 연결되지 않았어요. 입력한 정보는 저장·전송되지 않았습니다.');
};
export function createDemoService(storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>): MupidService {
  return {
    mode: 'demo',
    async bootstrap() {
      const preferences = structuredClone(defaults);
      // Corrupt/blocked device storage must not prevent opening the demo.
      try {
        const saved: unknown = JSON.parse(storage.getItem(preferencesKey) || 'null');
        if (record(saved)) {
          if (typeof saved.onboardingSubmittedAt === 'string' && Number.isFinite(Date.parse(saved.onboardingSubmittedAt))) preferences.onboardingSubmittedAt = saved.onboardingSubmittedAt;
          if (typeof saved.nickname === 'string' && saved.nickname.trim()) preferences.nickname = saved.nickname.slice(0, 20);
          if (Array.isArray(saved.moods)) preferences.moods = saved.moods.filter((v: unknown) => typeof v === 'string');
          for (const key of ['liked', 'ignored', 'history'] as const) {
            if (Array.isArray(saved[key])) preferences[key] = saved[key].filter((id: unknown) => tracks.some(t => t.id === id));
          }
          if (Array.isArray(saved.playlists)) preferences.playlists = saved.playlists.flatMap((p: unknown) => {
            if (!record(p) || typeof p.id !== 'number' || !Number.isFinite(p.id) || typeof p.name !== 'string' || !Array.isArray(p.tracks)) return [];
            return [{ id: p.id, name: p.name, tracks: p.tracks.flatMap((t: unknown) => {
              const found = record(t) ? tracks.find(v => v.id === t.id) : undefined;
              return found ? [found] : [];
            }) }];
          });
          if (Array.isArray(saved.moments)) preferences.moments = saved.moments.flatMap((m: unknown) => {
            if (!record(m) || !record(m.track)) return [];
            const id = m.track.id, track = tracks.find(t => t.id === id);
            if (!track || typeof m.id !== 'number' || !Number.isFinite(m.id) || typeof m.start !== 'number' || typeof m.end !== 'number' || !Number.isFinite(m.start) || !Number.isFinite(m.end) || m.start < 0 || m.end <= m.start || m.end > track.duration) return [];
            return [{ id: m.id, track, start: m.start, end: m.end, section: typeof m.section === 'string' ? m.section : '' }];
          });
        }
      } catch { /* Recover using demo defaults; never clear the user's stored value here. */ }
      return { tracks: structuredClone(tracks), preferences };
    },
    async savePreferences(value) { storage.setItem(preferencesKey, JSON.stringify(value)); },
    login: unavailable,
    async logout() {},
    signup: unavailable,
    requestEmailVerification: unavailable,
    async readPhoto() { const value = storage.getItem(photoKey); return value?.startsWith('data:image/jpeg;base64,') ? value : ''; },
    async savePhoto(value) { storage.setItem(photoKey, value); },
    async removePhoto() { storage.removeItem(photoKey); },
    async recommend(prompt, current, candidates) { return deriveChatRecommendations(prompt, current, candidates); },
  };
}

// Lazy access: no browser globals are read during SSR or module initialization.
const browserStorage = {
  getItem: (key: string) => window.localStorage.getItem(key),
  setItem: (key: string, value: string) => window.localStorage.setItem(key, value),
  removeItem: (key: string) => window.localStorage.removeItem(key),
};
export function createService(mode: 'demo' | 'api', api?: MupidService): MupidService {
  if (mode === 'demo') return createDemoService(browserStorage);
  if (!api || api.mode !== 'api') throw new Error('실제 API 어댑터가 필요합니다. 데모로 자동 전환하지 않습니다.');
  return api;
}
// Replace only after backend contracts/authentication are agreed and tested.
export const mupidService = createService('demo');
