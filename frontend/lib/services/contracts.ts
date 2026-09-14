import type { Track, SavedMoment, Playlist } from '../mupid-data';
export type AccountSession = { kind: 'demo' | 'authenticated'; nickname: string; userId?: number };

/** Frontend domain contract, not a promise about the future backend's DTOs. Times are seconds. */
export type Preferences = {
  onboardingSubmittedAt: string | null;
  nickname: string; moods: string[]; liked: number[]; ignored: number[];
  history: number[]; moments: SavedMoment[]; playlists: Playlist[];
};
export type Bootstrap = { tracks: Track[]; preferences: Preferences };
export type SignupInput = {
  email: string; password: string; nickname: string; birthYear: number;
  gender: 'MALE' | 'FEMALE' | 'ETC';
};
export interface MupidService {
  readonly mode: 'demo' | 'api';
  /**
   * 데모 저장소에서 곡·설정을 읽는다.
   *
   * @param catalog 서버에서 받아 온 곡 목록. 네트워크 호출은 화면 쪽에서 하고
   *   여기서는 합치기만 한다 — 이 계층은 브라우저 저장만 건드린다.
   */
  bootstrap(catalog?: Track[]): Promise<Bootstrap>;
  savePreferences(value: Preferences): Promise<void>;
  login(input: { email: string; password: string }): Promise<AccountSession>;
  logout(): Promise<void>;
  signup(input: SignupInput): Promise<void>;
  requestEmailVerification(email: string): Promise<void>;
  readPhoto(): Promise<string>;
  savePhoto(data: string): Promise<void>;
  removePhoto(): Promise<void>;
  recommend(prompt: string, current: Track, candidates: Track[]): Promise<{ text: string; tracks: Track[] }>;
}
