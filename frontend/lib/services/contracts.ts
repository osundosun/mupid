import type { Track, SavedMoment, Playlist } from '../mupid-data';
export type AccountSession = { kind: 'demo' | 'authenticated'; nickname: string; userId?: number; onboardingSubmittedAt?: string | null };
// 서버 DTO를 UI에 직접 퍼뜨리지 않고 서비스 어댑터에서 이 타입으로 변환한다.
// onboardingSubmittedAt이 없거나 null이면 로그인 이후 취향 설정이 필요한 것으로 판단한다.

/** Frontend domain contract, not a promise about the future backend's DTOs. Times are seconds. */
export type Preferences = {
  onboardingSubmittedAt: string | null;
  nickname: string; moods: string[]; genres: string[]; liked: number[]; ignored: number[];
  history: number[]; moments: SavedMoment[]; playlists: Playlist[];
  /** 마지막으로 재생한 곡. null이면 아직 한 곡도 듣지 않은 계정이다. */
  lastPlayedId?: number | null;
};
export type Bootstrap = { tracks: Track[]; preferences: Preferences; session?: AccountSession | null };
export type SignupInput = {
  email: string; password: string; nickname: string; birthYear: number;
  gender: 'MALE' | 'FEMALE' | 'ETC';
};
export interface MupidService {
  // 실제 API 연결 시 이 인터페이스를 구현한다. 화면 컴포넌트에서 직접 fetch를 추가하지 않는다.
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
  // 추천·대화는 이 계약에 없다. 서버가 한다 — `lib/ai-chat.ts` (`POST /api/v1/ai/chat`).
  // 여기에 두면 브라우저 규칙으로 고른 곡과 서버가 고른 곡, 두 갈래가 조용히 갈라진다.
}
