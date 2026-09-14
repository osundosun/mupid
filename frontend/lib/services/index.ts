import { initialMoments, initialPlaylists } from '../mupid-data';
import type { MupidService, Preferences } from './contracts';
import type { Track } from '../mupid-data';

const preferencesKey = 'mupid.sky.preferences.v1';
const photoKey = 'mupid-profile-photo';
/** 예전 체험 로그인 표시. 남아 있으면 지우기만 한다. */
const DEMO_SESSION_KEY = 'mupid.demo-session.v1';
const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object';
// 데모 곡이 없으므로 미리 좋아 둔 곡도, 들어 둔 기록도 없다.
const defaults: Preferences = { onboardingSubmittedAt: null, nickname: '규원', moods: [], genres: [],
  liked: [], ignored: [], history: [], moments: initialMoments, playlists: initialPlaylists,
  lastPlayedId: null };
const unavailable = async (): Promise<never> => {
  // 미연결 인증은 성공으로 위장하지 않는다. 입력 비밀번호도 로컬 저장소에 보관하지 않는다.
  throw new Error('서버가 아직 연결되지 않았어요. 입력한 정보는 저장·전송되지 않았습니다.');
};
export function createDemoService(storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>): MupidService {
  // 저장소를 주입받아 테스트와 브라우저 실행을 분리한다. 현재 데이터는 기기 간 동기화되지 않는다.
  return {
    mode: 'demo',
    async bootstrap(catalog: Track[] = []) {
      const preferences = structuredClone(defaults);
      // 저장된 좋아요·기록이 서버 곡을 가리킬 수도 있다. 검증은 합친 목록으로 한다.
      const allTracks = catalog;
      // Corrupt/blocked device storage must not prevent opening the demo.
      try {
        const saved: unknown = JSON.parse(storage.getItem(preferencesKey) || 'null');
        if (record(saved)) {
          if (typeof saved.onboardingSubmittedAt === 'string' && Number.isFinite(Date.parse(saved.onboardingSubmittedAt))) preferences.onboardingSubmittedAt = saved.onboardingSubmittedAt;
          if (typeof saved.nickname === 'string' && saved.nickname.trim()) preferences.nickname = saved.nickname.slice(0, 20);
          if (Array.isArray(saved.moods)) preferences.moods = saved.moods.filter((v: unknown) => typeof v === 'string');
          if (Array.isArray(saved.genres)) preferences.genres = [...new Set(saved.genres.filter((v: unknown): v is string => typeof v === 'string'))];
          if (typeof saved.lastPlayedId === 'number' && allTracks.some(t => t.id === saved.lastPlayedId)) preferences.lastPlayedId = saved.lastPlayedId;
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
      // 로그인 상태는 브라우저 저장으로 복원하지 않는다. 표시를 되살리는 것만으로
      // 서비스가 열리면 그건 인증이 아니다. 세션은 쿠키의 리프레시 토큰으로
      // `lib/api/session` 이 서버에 물어 복원한다.
      const session: import('./contracts').AccountSession | null = null;
      // 예전 체험 로그인 흔적이 남아 있으면 지운다.
      try { storage.removeItem(DEMO_SESSION_KEY); } catch {}
      // 서버에 등록된 곡만 보여 준다. 데모 곡은 없앴다.
      // 넘겨받은 배열을 그대로 돌려주지 않는다. 호출자가 고치면 다음 호출까지
      // 흔들린다.
      return { tracks: [...catalog], preferences, session };
    },
    async savePreferences(value) { storage.setItem(preferencesKey, JSON.stringify(value)); },
    // 체험 로그인은 없앴다. 로그인하지 않으면 서비스를 쓸 수 없고, 로그인은
    // 서버 인증(`lib/api/session`)만을 거친다.
    login: unavailable,
    async logout() { storage.removeItem(DEMO_SESSION_KEY); },
    signup: unavailable,
    requestEmailVerification: unavailable,
    async readPhoto() { const value = storage.getItem(photoKey); return value?.startsWith('data:image/jpeg;base64,') ? value : ''; },
    async savePhoto(value) { storage.setItem(photoKey, value); },
    async removePhoto() { storage.removeItem(photoKey); },
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

/** 앱 셸(Tauri) 안에서 도는가. 전역은 웹뷰가 뜰 때 Tauri 가 먼저 심는다. */
const inNativeShell = () => typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;
/*
 * 앱에서는 SQLite 가 `localStorage` 자리를 대신한다 (`lib/platform/service.ts`).
 * 구현은 **부를 때** 불러온다 — 정적 import 로 두면 웹 번들에도 `@tauri-apps/api`
 * 가 섞이고, 이 모듈만 떼어 검사하는 시험(`tests/services.test.mjs`)이 상대 경로를
 * 풀지 못한다.
 */
let native: Promise<MupidService> | null = null;
const shell = () => (native ??= import('../platform/service').then(m => m.createSqliteService()));
const nativeService: MupidService = {
  mode: 'demo', // 역할은 데모 저장소와 같다 — 기기에만 남는 저장.
  bootstrap: async catalog => (await shell()).bootstrap(catalog),
  savePreferences: async value => (await shell()).savePreferences(value),
  login: async input => (await shell()).login(input),
  logout: async () => (await shell()).logout(),
  signup: async input => (await shell()).signup(input),
  requestEmailVerification: async email => (await shell()).requestEmailVerification(email),
  readPhoto: async () => (await shell()).readPhoto(),
  savePhoto: async data => (await shell()).savePhoto(data),
  removePhoto: async () => (await shell()).removePhoto(),
};
// Replace only after backend contracts/authentication are agreed and tested.
export const mupidService: MupidService = inNativeShell() ? nativeService : createService('demo');
