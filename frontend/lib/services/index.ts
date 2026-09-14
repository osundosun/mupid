import { tracks, initialMoments, initialPlaylists, deriveChatRecommendations } from '../mupid-data';
import { streamTracks } from '../stream-catalog';
import type { MupidService, Preferences } from './contracts';

const preferencesKey = 'mupid.sky.preferences.v1';
// 저장된 좋아요·기록이 서버 곡을 가리킬 수도 있다. 검증은 합친 목록으로 한다.
const allTracks = [...tracks, ...streamTracks];
const photoKey = 'mupid-profile-photo';
const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object';
const defaults: Preferences = { onboardingSubmittedAt: null, nickname: '규원', moods: ['몽환적인', '감성적인'],
  liked: [1, 3, 7], ignored: [], history: [1, 3, 2], moments: initialMoments, playlists: initialPlaylists,
  lastPlayedId: null };
const unavailable = async (): Promise<never> => {
  // 미연결 인증은 성공으로 위장하지 않는다. 입력 비밀번호도 로컬 저장소에 보관하지 않는다.
  throw new Error('서버가 아직 연결되지 않았어요. 입력한 정보는 저장·전송되지 않았습니다.');
};
export function createDemoService(storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>): MupidService {
  // 저장소를 주입받아 테스트와 브라우저 실행을 분리한다. 현재 데이터는 기기 간 동기화되지 않는다.
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
          if (Array.isArray(saved.genres)) preferences.genres = [...new Set(saved.genres.filter((v: unknown): v is string => typeof v === 'string' && tracks.some(t => t.genre === v)))];
          if (typeof saved.lastPlayedId === 'number' && tracks.some(t => t.id === saved.lastPlayedId)) preferences.lastPlayedId = saved.lastPlayedId;
          for (const key of ['liked', 'ignored', 'history'] as const) {
            if (Array.isArray(saved[key])) preferences[key] = saved[key].filter((id: unknown) => allTracks.some(t => t.id === id));
          }
          if (Array.isArray(saved.playlists)) preferences.playlists = saved.playlists.flatMap((p: unknown) => {
            if (!record(p) || typeof p.id !== 'number' || !Number.isFinite(p.id) || typeof p.name !== 'string' || !Array.isArray(p.tracks)) return [];
            return [{ id: p.id, name: p.name, tracks: p.tracks.flatMap((t: unknown) => {
              const found = record(t) ? allTracks.find(v => v.id === t.id) : undefined;
              return found ? [found] : [];
            }) }];
          });
          if (Array.isArray(saved.moments)) preferences.moments = saved.moments.flatMap((m: unknown) => {
            if (!record(m) || !record(m.track)) return [];
            const id = m.track.id, track = allTracks.find(t => t.id === id);
            if (!track || typeof m.id !== 'number' || !Number.isFinite(m.id) || typeof m.start !== 'number' || typeof m.end !== 'number' || !Number.isFinite(m.start) || !Number.isFinite(m.end) || m.start < 0 || m.end <= m.start || m.end > track.duration) return [];
            return [{ id: m.id, track, start: m.start, end: m.end, section: typeof m.section === 'string' ? m.section : '' }];
          });
        }
      } catch { /* Recover using demo defaults; never clear the user's stored value here. */ }
      // 공개 데모 표시만 복원한다. 실제 회원 토큰이나 비밀번호는 저장하지 않는다.
      let session: import('./contracts').AccountSession | null = null;
      try { if (storage.getItem('mupid.demo-session.v1') === 'active') session = {kind: 'demo', userId: -1, nickname: preferences.nickname, onboardingSubmittedAt: preferences.onboardingSubmittedAt}; } catch {}
      // 데모 곡 + 서버에 실제로 올라간 곡. 후자는 청크 스트리밍으로 재생된다.
      // 곡 목록 API 가 생기면 streamTracks 를 서버 응답으로 갈아끼운다.
      return {
        tracks: [...structuredClone(tracks), ...structuredClone(streamTracks)],
        preferences,
        session,
      };
    },
    async savePreferences(value) { storage.setItem(preferencesKey, JSON.stringify(value)); },
    // 공개 체험 전용 계정이다. 서버 회원이나 실제 인증 토큰으로 취급하지 않는다.
    async login({ email, password }) {
      if (email.trim().toLowerCase() !== 'demo@naver.com' || password !== '1234') {
        throw new Error('데모 이메일 또는 비밀번호를 확인해 주세요.');
      }
      const { preferences } = await this.bootstrap();
      storage.setItem('mupid.demo-session.v1', 'active');
      return { kind: 'demo', userId: -1, nickname: '데모', onboardingSubmittedAt: preferences.onboardingSubmittedAt };
    },
    async logout() { storage.removeItem('mupid.demo-session.v1'); },
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
