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
 */

import { api, sessionStore } from './api/session';
import { mupidService } from './services';

/** 사진을 올린 적이 없는 사람의 `image_url` 이 가리키는 키. */
const DEFAULT_KEY = '/default';

const signedIn = () => {
  const session = sessionStore.get();
  return session.status === 'authenticated' && session.user ? session.user : null;
};

/** object URL 은 직접 놓아 주어야 한다. 화면이 바뀔 때마다 새로 만들기 때문이다. */
export const releasePhoto = (url: string) => {
  if (url.startsWith('blob:')) URL.revokeObjectURL(url);
};

export const profilePhoto = {
  /** 화면에 바로 넣을 수 있는 URL. 사진이 없으면 빈 문자열이다. */
  async read(signal?: AbortSignal): Promise<string> {
    const user = signedIn();
    if (!user) return mupidService.readPhoto();

    const info = await api.user.me(signal);
    const url = info?.image_url ?? '';
    // 기본 이미지를 가리키면 "사진 없음" 이다. 화면은 마스코트를 보여준다.
    if (!url || url.endsWith(DEFAULT_KEY)) return '';

    const bytes = await api.storage.image(url, signal);
    return URL.createObjectURL(new Blob([bytes], { type: 'image/*' }));
  },

  /** 저장하고, 화면에 바로 넣을 수 있는 URL 을 돌려준다. */
  async save(blob: Blob, dataUrl: string): Promise<string> {
    const user = signedIn();
    if (!user) {
      await mupidService.savePhoto(dataUrl);
      return dataUrl;
    }
    await api.storage.uploadProfileImage(user.user_id, blob);
    return URL.createObjectURL(blob);
  },

  async remove(): Promise<void> {
    const user = signedIn();
    if (!user) return mupidService.removePhoto();
    await api.storage.deleteProfileImage(user.user_id);
  },

  /** 지금 어디에 저장되는가. 화면이 안내 문구에 쓴다. */
  get storedOnServer() {
    return signedIn() !== null;
  },
};
