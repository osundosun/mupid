/**
 * 프로필 사진.
 *
 * 로그인했으면 계정에, 아니면 브라우저에만 저장한다. 화면은 어느 쪽인지 몰라도
 * 되도록 여기서 갈라 쓴다.
 *
 * ## 왜 `<img src={image_url}>` 로 끝나지 않는가
 *
 * 프로필 사진은 개인 정보라 공개 경로가 아니다(`SecurityConfig`). `<img>` 는
 * `Authorization` 헤더를 달 수 없으므로 토큰을 붙여 바이트를 받아 온 뒤
 * object URL 로 바꿔 쓴다. 앨범·아티스트 이미지는 공개라 URL 을 그대로 쓴다.
 *
 * ## 왜 스토어를 여기 두는가
 *
 * 사진을 보여 주는 자리가 둘이다 — 상단바의 동그란 아이콘과 마이페이지의 편집
 * 자리. 각자 읽게 두었더니 상단바는 로그인한 사람에게도 브라우저 저장만 보고
 * 있어서 **계정에 올린 사진이 끝내 안 보였다.** 한 곳에서 읽어 함께 본다.
 * 마이페이지에서 사진을 바꾸면 상단바도 그 자리에서 같이 바뀐다.
 */

import { api, sessionStore } from './api/session';
import { mupidService } from './services';
import { createStore } from './store';

/** 사진을 올린 적이 없는 사람의 `image_url` 이 가리키는 키. */
const DEFAULT_KEY = '/default';

const signedIn = () => {
  const session = sessionStore.get();
  return session.status === 'authenticated' && session.user ? session.user : null;
};

/** object URL 은 직접 놓아 주어야 한다. */
export const releasePhoto = (url: string) => {
  if (url.startsWith('blob:')) URL.revokeObjectURL(url);
};

export type ProfilePhotoState = {
  /** 화면에 바로 넣을 수 있는 URL. 사진이 없으면 빈 문자열이다. */
  url: string;
  /** 아직 한 번도 읽지 않았는가. 처음 그릴 때 깜빡임을 막는 데 쓴다. */
  loaded: boolean;
};

export const profilePhotoStore = createStore<ProfilePhotoState>({
  url: '',
  loaded: false,
});

/** 늦게 도착한 응답이 새 사진을 덮지 않게 한다. */
let readToken = 0;

const put = (url: string) => {
  const previous = profilePhotoStore.get().url;
  if (previous !== url) releasePhoto(previous);
  profilePhotoStore.set({ url, loaded: true });
};

/** 지금 저장된 사진을 읽어 온다. 화면에 바로 넣을 수 있는 URL 이다. */
const fetchPhoto = async (signal?: AbortSignal): Promise<string> => {
  const user = signedIn();
  if (!user) return mupidService.readPhoto();

  const info = await api.user.me(signal);
  const url = info?.image_url ?? '';
  // 기본 이미지를 가리키면 "사진 없음" 이다. 화면은 마스코트를 보여준다.
  if (!url || url.endsWith(DEFAULT_KEY)) return '';

  const bytes = await api.storage.image(url, signal);
  return URL.createObjectURL(new Blob([bytes], { type: 'image/*' }));
};

export const profilePhoto = {
  /** 저장된 사진을 다시 읽어 스토어에 담는다. 실패하면 예외가 난다. */
  async refresh(signal?: AbortSignal): Promise<void> {
    const mine = ++readToken;
    try {
      const url = await fetchPhoto(signal);
      // 그 사이 저장·삭제·계정 변경이 있었으면 지금 읽은 쪽이 낡았다.
      if (mine !== readToken) {
        releasePhoto(url);
        return;
      }
      put(url);
    } catch (error) {
      if (mine === readToken) profilePhotoStore.set({ loaded: true });
      throw error;
    }
  },

  /** 저장하고 화면에 바로 반영한다. */
  async save(blob: Blob, dataUrl: string): Promise<void> {
    const user = signedIn();
    if (!user) {
      await mupidService.savePhoto(dataUrl);
      readToken += 1;
      put(dataUrl);
      return;
    }
    await api.storage.uploadProfileImage(user.user_id, blob);
    readToken += 1;
    put(URL.createObjectURL(blob));
  },

  async remove(): Promise<void> {
    const user = signedIn();
    if (!user) await mupidService.removePhoto();
    else await api.storage.deleteProfileImage(user.user_id);
    readToken += 1;
    put('');
  },

  /** 로그아웃할 때 화면에서 지운다. 다음 사람에게 앞사람 사진이 보이면 안 된다. */
  clear(): void {
    readToken += 1;
    const previous = profilePhotoStore.get().url;
    releasePhoto(previous);
    profilePhotoStore.set({ url: '', loaded: false });
  },

  /** 지금 어디에 저장되는가. 화면이 안내 문구에 쓴다. */
  get storedOnServer() {
    return signedIn() !== null;
  },
};
