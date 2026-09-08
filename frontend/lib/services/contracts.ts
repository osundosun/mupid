import type { Track, SavedMoment, Playlist } from '../mupid-data';
export type AccountSession = { kind: 'demo' | 'authenticated'; nickname: string; userId?: number; onboardingSubmittedAt?: string | null };
// 서버 DTO를 UI에 직접 퍼뜨리지 않고 서비스 어댑터에서 이 타입으로 변환한다.
// onboardingSubmittedAt이 없거나 null이면 로그인 이후 취향 설정이 필요한 것으로 판단한다.

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
  // 실제 API 연결 시 이 인터페이스를 구현한다. 화면 컴포넌트에서 직접 fetch를 추가하지 않는다.
  readonly mode: 'demo' | 'api';
  bootstrap(): Promise<Bootstrap>;
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
