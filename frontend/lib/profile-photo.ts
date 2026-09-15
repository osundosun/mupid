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
 * ## 왜 `url` 만으로는 모자란가
 *
 * 사진을 올린 적이 없는 사람의 `image_url` 은 `/default` 를 가리키고, 그 키에
 * 파일은 없지만 백엔드가 기본 이미지를 돌려준다. 예전에는 이 URL 을 보고 그냥
 * 빈 문자열을 반환했는데, 그래서 **기본 이미지가 어디에도 보이지 않았다** —
 * 상단바는 사람 아이콘을, 마이페이지는 마스코트를 대신 그렸다.
 *
 * 지금은 기본 이미지도 받아 온다. 다만 "본인이 올린 사진"과는 구분해야 한다.
 * 삭제 버튼 노출과 `사진 등록`/`사진 변경` 문구가 그걸로 갈리기 때문이다.
 * 그 구분이 `isDefault` 다. **화면은 `url` 로 그리고 `isDefault` 로 가른다.**
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
  /** 화면에 바로 넣을 수 있는 URL. 받아오지 못했으면 빈 문자열이다. */
  url: string;
  /** 지금 들고 있는 것이 본인이 올린 사진이 아니라 기본 이미지인가. */
  isDefault: boolean;
  /** 아직 한 번도 읽지 않았는가. 처음 그릴 때 깜빡임을 막는 데 쓴다. */
  loaded: boolean;
};

export const profilePhotoStore = createStore<ProfilePhotoState>({
  url: '',
  isDefault: true,
  loaded: false,
});

/** 늦게 도착한 응답이 새 사진을 덮지 않게 한다. */
let readToken = 0;

const put = (url: string, isDefault: boolean) => {
  const previous = profilePhotoStore.get().url;
  if (previous !== url) releasePhoto(previous);
  profilePhotoStore.set({ url, isDefault, loaded: true });
};

type Photo = { url: string; isDefault: boolean };

/** 지금 저장된 사진을 읽어 온다. 화면에 바로 넣을 수 있는 URL 이다. */
const fetchPhoto = async (signal?: AbortSignal): Promise<Photo> => {
  const user = signedIn();
  // 체험 세션은 토큰이 없다. 기본 이미지는 비공개 경로라 받아올 수 없으므로
  // 브라우저에 저장한 사진만 본다. 그것도 없으면 화면이 대체 그림을 그린다.
  if (!user) {
    const stored = await mupidService.readPhoto();
    return { url: stored, isDefault: !stored };
  }

  const info = await api.user.me(signal);
  const url = info?.image_url ?? '';
  if (!url) return { url: '', isDefault: true };

  // `/default` 키에도 받아올 것이 있다 — 백엔드가 기본 이미지를 돌려준다.
  // 그래서 여기서 끊지 않고 똑같이 받아 오되, 올린 사진과 구분만 해 둔다.
  const isDefault = url.endsWith(DEFAULT_KEY);
  const bytes = await api.storage.image(url, signal);
  return {
    url: URL.createObjectURL(new Blob([bytes], { type: 'image/*' })),
    isDefault,
  };
};

export const profilePhoto = {
  /** 저장된 사진을 다시 읽어 스토어에 담는다. 실패하면 예외가 난다. */
  async refresh(signal?: AbortSignal): Promise<void> {
    const mine = ++readToken;
    try {
      const photo = await fetchPhoto(signal);
      // 그 사이 저장·삭제·계정 변경이 있었으면 지금 읽은 쪽이 낡았다.
      if (mine !== readToken) {
        releasePhoto(photo.url);
        return;
      }
      put(photo.url, photo.isDefault);
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
      put(dataUrl, false);
      return;
    }
    await api.storage.uploadProfileImage(user.user_id, blob);
    readToken += 1;
    put(URL.createObjectURL(blob), false);
  },

  async remove(): Promise<void> {
    const user = signedIn();
    if (!user) {
      await mupidService.removePhoto();
      readToken += 1;
      put('', true);
      return;
    }
    await api.storage.deleteProfileImage(user.user_id);
    readToken += 1;
    put('', true);
    // 지운 자리에는 백엔드가 기본 이미지를 준다. 다시 읽어 그 자리에서 보여
    // 준다. 여기서 실패해도 삭제 자체는 끝났으므로 예외를 올리지 않는다 —
    // 올리면 호출부가 "삭제하지 못했어요" 를 띄운다.
    await profilePhoto.refresh().catch(() => {});
  },

  /** 로그아웃할 때 화면에서 지운다. 다음 사람에게 앞사람 사진이 보이면 안 된다. */
  clear(): void {
    readToken += 1;
    const previous = profilePhotoStore.get().url;
    releasePhoto(previous);
    profilePhotoStore.set({ url: '', isDefault: true, loaded: false });
  },

  /** 지금 어디에 저장되는가. 화면이 안내 문구에 쓴다. */
  get storedOnServer() {
    return signedIn() !== null;
  },
};
