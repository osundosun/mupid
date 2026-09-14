/**
 * 앱의 저장소 어댑터.
 *
 * 웹에서는 `createDemoService` 가 `localStorage` 를 쓴다. 앱에서는 그 자리를
 * SQLite 가 대신한다 — 웹뷰 저장소는 OS 가 앱 데이터를 정리할 때 함께 사라지고
 * 백업에도 담기지 않는다. 저장한 취향과 컬렉션이 그렇게 없어지면 안 된다.
 *
 * 계약(`lib/services/contracts.ts`)은 그대로다. SQL 은 전부 Rust 안에 있고
 * (`app/src/store/`), 여기서는 화면이 쓰는 모양으로 옮기기만 한다.
 *
 * **곡은 번호로만 저장한다.** 곡 정보는 서버 카탈로그의 것이고, 복사해 두면
 * 제목이 바뀐 곡이 이 기기에서만 옛 이름으로 남는다. 읽을 때 카탈로그에서
 * 찾지 못한 번호는 조용히 버린다 — 없어진 곡을 목록에 그리면 눌러도 아무 일도
 * 일어나지 않는 줄이 생긴다.
 */

import type { Playlist, SavedMoment, Track } from '../mupid-data';
import type { Bootstrap, MupidService, Preferences } from '../services/contracts';
import { invoke } from './bridge';

/** 아직 이름을 정하지 않은 사람에게 보여 줄 값. 데모 저장소와 같다. */
const DEFAULT_NICKNAME = '규원';

type StoredMoment = {
  id: number;
  trackId: number;
  start: number;
  end: number;
  section: string;
};

type StoredPlaylist = { id: number; name: string; trackIds: number[] };

type StoredPreferences = {
  onboardingSubmittedAt: string | null;
  nickname: string;
  moods: string[];
  genres: string[];
  liked: number[];
  ignored: number[];
  history: number[];
  lastPlayedId: number | null;
  moments: StoredMoment[];
  playlists: StoredPlaylist[];
};

const unavailable = async (): Promise<never> => {
  // 인증은 이 계층이 하지 않는다. `lib/api/session` 이 서버와 직접 이야기한다.
  throw new Error('로그인은 서버에서 처리해요.');
};

export function createSqliteService(): MupidService {
  return {
    // 역할은 데모 저장소와 같다 — 기기에만 남는 저장. `'api'` 는 서버가
    // 취향까지 들고 있게 될 때를 위해 비워 둔다.
    mode: 'demo',

    async bootstrap(catalog: Track[] = []): Promise<Bootstrap> {
      const byId = new Map(catalog.map((track) => [track.id, track]));
      let stored: StoredPreferences;
      try {
        stored = await invoke<StoredPreferences>('store_load_preferences', {});
      } catch (error) {
        // 저장소가 깨져도 앱은 열려야 한다. 이번 실행에서 고른 것만 못 남는다.
        console.warn('[mupid] 저장한 설정을 읽지 못했어요.', error);
        return { tracks: [...catalog], preferences: emptyPreferences(), session: null };
      }

      const keep = (ids: number[]) => ids.filter((id) => byId.has(id));
      const moments: SavedMoment[] = stored.moments.flatMap((moment) => {
        const track = byId.get(moment.trackId);
        // 곡보다 긴 구간은 저장 당시의 길이가 바뀐 것이다. 그리면 파형이 깨진다.
        if (!track || moment.end <= moment.start || moment.end > track.duration) {
          return [];
        }
        return [{
          id: moment.id,
          track,
          start: moment.start,
          end: moment.end,
          section: moment.section,
        }];
      });
      const playlists: Playlist[] = stored.playlists.map((playlist) => ({
        id: playlist.id,
        name: playlist.name,
        tracks: playlist.trackIds.flatMap((id) => {
          const track = byId.get(id);
          return track ? [track] : [];
        }),
      }));

      const preferences: Preferences = {
        onboardingSubmittedAt: stored.onboardingSubmittedAt,
        nickname: stored.nickname.trim() || DEFAULT_NICKNAME,
        moods: stored.moods,
        genres: [...new Set(stored.genres)],
        liked: keep(stored.liked),
        ignored: keep(stored.ignored),
        history: keep(stored.history),
        moments,
        playlists,
        lastPlayedId:
          stored.lastPlayedId !== null && byId.has(stored.lastPlayedId)
            ? stored.lastPlayedId
            : null,
      };

      // 로그인 상태는 기기 저장으로 되살리지 않는다. 표시만 되살아나는 것은
      // 인증이 아니다 — `lib/api/session` 이 서버에 물어 복원한다.
      return { tracks: [...catalog], preferences, session: null };
    },

    async savePreferences(value) {
      const stored: StoredPreferences = {
        onboardingSubmittedAt: value.onboardingSubmittedAt,
        nickname: value.nickname,
        moods: value.moods,
        genres: value.genres,
        liked: value.liked,
        ignored: value.ignored,
        history: value.history,
        lastPlayedId: value.lastPlayedId ?? null,
        moments: value.moments.map((moment) => ({
          id: moment.id,
          trackId: moment.track.id,
          start: moment.start,
          end: moment.end,
          section: moment.section,
        })),
        playlists: value.playlists.map((playlist) => ({
          id: playlist.id,
          name: playlist.name,
          trackIds: playlist.tracks.map((track) => track.id),
        })),
      };
      await invoke('store_save_preferences', { value: stored });
    },

    login: unavailable,
    signup: unavailable,
    requestEmailVerification: unavailable,

    async logout() {
      // 여기서는 아무것도 지우지 않는다. 로그아웃은 `lib/api/session.ts` 가
      // 맡는다 — 쿠키와 캐시를 버리고 계정 키를 기본값으로 되돌린다.
      //
      // 저장해 둔 취향·컬렉션은 남긴다. 계정마다 `user_key` 로 나눠 두었으니
      // 다음 사람에게 보이지 않고, 같은 사람이 다시 들어오면 그대로 돌아온다.
    },

    async readPhoto() {
      const value = await invoke<string>('store_read_photo', {});
      return value.startsWith('data:image/jpeg;base64,') ? value : '';
    },

    async savePhoto(value) {
      await invoke('store_save_photo', { data: value });
    },

    async removePhoto() {
      await invoke('store_remove_photo', {});
    },
  };
}

function emptyPreferences(): Preferences {
  return {
    onboardingSubmittedAt: null,
    nickname: DEFAULT_NICKNAME,
    moods: [],
    genres: [],
    liked: [],
    ignored: [],
    history: [],
    moments: [],
    playlists: [],
    lastPlayedId: null,
  };
}

/**
 * 지금 저장이 어느 계정 몫인지 알린다.
 *
 * 로그인하면 사용자 번호로, 로그아웃하면 기기 기본값으로 돌아간다. 이 키가
 * 없으면 한 기기를 두 사람이 쓸 때 뒷사람이 앞사람의 컬렉션을 보게 된다.
 */
export const setStoredAccount = (userId: number | null): Promise<void> =>
  invoke('session_set_user_key', { value: userId === null ? '' : String(userId) });
