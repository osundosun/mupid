/**
 * 자동 업데이트의 화면 쪽.
 *
 * 두 갈래가 있고, 판단은 전부 Rust 가 한다(`app/src/update.rs` ·
 * `app/src/update_net.rs`). 여기서 하는 일은 **부르고, 결과를 알리는 것**뿐이다 —
 * 버전 비교 규칙을 화면에 한 번 더 쓰면 두 곳이 언젠가 어긋난다.
 *
 * | 갈래 | 언제 | 사용자가 하는 일 |
 * | --- | --- | --- |
 * | 웹 | 조용히 내려받아 **디스크에만** 활성화 | 없음 (다음 콜드 스타트에 적용) |
 * | 네이티브 | APK 를 내려받아 설치 화면을 띄운다 | 설치 |
 *
 * 웹에서는 아무 일도 하지 않는다. 앱 셸 안에서만 뜻이 있는 기능이다.
 *
 * ## 재생을 끊지 않는다
 *
 * 웹 갈래는 내려받아 **활성화까지만** 하고 화면을 다시 읽지 않는다. 이미
 * 메모리에 올라온 자산은 그대로라 듣던 곡이 이어진다. 실제 교체는 다음
 * 콜드 스타트에 일어난다 — 이 저장소가 이미 겪은 사고다(`vite.config.ts` 의
 * `pinRscCompatibilityId` 주석: 화면 이동마다 전체 새로고침 → 곡·재생 위치
 * 초기화).
 *
 * ## 실패는 조용하다
 *
 * 네트워크가 없거나 서버가 죽었으면 **아무 일도 일어나지 않아야 한다.**
 * 업데이트 확인 실패가 화면에 오류로 뜨면 "앱이 자꾸 에러를 띄운다" 가 되고
 * 그건 삭제로 이어진다. `cache.ts` 의 "캐시는 네트워크를 대신하지 않는다" 와
 * 같은 태도로 다음 기회로 넘긴다.
 */

import { invoke, isTauri } from './bridge';

/** Rust 가 알려주는 지금 상태. `app/src/update_net.rs` 의 `UpdateState` 와 같아야 한다. */
export type UpdateState = {
  /** 지금 자산으로 쓰이는 웹 번들의 나이 (epoch ms). */
  localWebBuild: number;
  /** 임베드된 번들의 나이. 디스크 번들이 없으면 이것이 쓰인다. */
  embeddedWebBuild: number;
  /** 이 APK 의 빌드 시각 (epoch ms). */
  localNativeBuild: number;
  /** 이 APK 의 `versionCode`. */
  localVersionCode: number;
  /** 서버가 알려준 최신 웹 번들. 확인에 실패했으면 `null`. */
  serverWebBuild: number | null;
  /** 서버가 알려준 최신 APK 의 `versionCode`. */
  serverVersionCode: number | null;
  /** 새 웹 번들이 있는가. */
  webAvailable: boolean;
  /** 새 APK 가 있는가. */
  nativeAvailable: boolean;
  /** 서버가 준 값이 두 번 실패해 거부 목록에 들어 있는가. */
  webRejected: boolean;
  /**
   * **이 기기에서 APK 설치를 시도할 수 있는가.**
   *
   * 안드로이드에서만 참이다. 데스크톱 개발 빌드에서 안내를 띄우면 "눌러도
   * 아무 일도 안 일어나는 창" 이 된다 — 창 너비로 플랫폼을 추측하지 않고
   * 이 값을 본다.
   */
  canInstallNative: boolean;
  /** 지금 안내를 띄워도 되는가 (24시간에 한 번). 기록은 Rust 가 DB 에 남긴다. */
  nativePromptDue: boolean;
};

/** 아직 아무것도 모르는 상태. 첫 확인 전이나 앱이 아닐 때 쓴다. */
export const UNKNOWN: UpdateState = {
  localWebBuild: 0,
  embeddedWebBuild: 0,
  localNativeBuild: 0,
  localVersionCode: 0,
  serverWebBuild: null,
  serverVersionCode: null,
  webAvailable: false,
  nativeAvailable: false,
  webRejected: false,
  canInstallNative: false,
  nativePromptDue: false,
};

/**
 * 기동 뒤 한 번 확인하고, 새 웹 번들이 있으면 조용히 활성화한다.
 *
 * **화면이 뜨는 것을 막지 않는다.** 이 함수는 첫 화면이 뜬 뒤에 불러야 하고,
 * 부른 쪽은 결과를 기다릴 필요가 없다. 내려받는 동안에도 화면은 옛 번들로
 * 계속 돈다.
 *
 * 실패해도 던지지 않는다 — `null` 을 준다.
 */
export async function checkForUpdates(): Promise<UpdateState | null> {
  if (!isTauri()) return null;
  try {
    // 순서가 있다. 상태를 먼저 읽어 **새 것이 있을 때만** 내려받는다.
    // `update_web` 이 스스로 판단하지만, 여기서 한 번 거르면 할 일이 없을 때
    // 커맨드를 부르지 않아도 되고 진단이 쉬워진다.
    const before = await invoke<UpdateState>('update_status');
    if (before.webAvailable && !before.webRejected) {
      // 내려받아 활성화한다. 화면은 건드리지 않는다.
      await invoke<number | null>('update_web');
    }
    return await invoke<UpdateState>('update_status');
  } catch (error) {
    // 네트워크가 없거나 서버가 죽었을 수 있다. 사용자가 알 필요는 없다.
    console.warn('[update]', error);
    return null;
  }
}

/**
 * 사용자가 "업데이트 확인" 을 직접 눌렀을 때.
 *
 * 거부 목록을 비우고 다시 시도한다 — 서버가 고친 번들을 올렸을 수 있고,
 * 자동으로는 그 값을 영영 다시 시도하지 않기 때문이다(`update.rs` 의
 * `rejected`). 실패는 여기서도 던지지 않는다.
 */
export async function checkForUpdatesNow(): Promise<UpdateState | null> {
  if (!isTauri()) return null;
  try {
    return await invoke<UpdateState>('update_check_now');
  } catch (error) {
    console.warn('[update]', error);
    return null;
  }
}

/**
 * 네이티브 업데이트 — APK 를 내려받아 **설치 화면을 띄운다**.
 *
 * 설치까지 앱이 하지 않는다. 안드로이드가 막는 일이고, 사용자가 "이 출처의
 * 앱 설치" 를 허용해야 한다. 허용되지 않았으면 Rust 가 설정 화면으로 보내고
 * 거절한다 — 그 문구를 그대로 보여 주는 것이 여기 일이다.
 *
 * **웹 갈래와 달리 실패를 삼키지 않는다.** 사용자가 직접 누른 일이고,
 * 아무 일도 일어나지 않으면 왜인지 알 방법이 없다.
 */
export async function installNativeUpdate(): Promise<void> {
  if (!isTauri()) return;
  await invoke<void>('update_native');
}

/**
 * 지금 안내를 띄울 만한가 — **순수 함수.**
 *
 * 창을 띄워 보지 않고 규칙을 확인할 수 있게 렌더 밖에 둔다. `isMobile` 은
 * 화면(창 너비)이 정하고, `canInstallNative` 는 Rust 가 정한다 — **둘 다**
 * 필요하다. 폭만 보면 태블릿·가로 화면에서 설치할 수 없는 창이 뜨고,
 * 플랫폼만 보면 데스크톱 브라우저로 같은 화면을 열었을 때 뜬다.
 *
 * 새 APK 가 없으면 알릴 것이 없고, 오늘 이미 보여 줬으면 조용히 있는다.
 */
export function shouldPromptUpdate(input: {
  isMobile: boolean;
  state: UpdateState | null;
}): boolean {
  const { state } = input;
  if (!state || !input.isMobile) return false;
  if (!state.canInstallNative) return false;
  if (!state.nativeAvailable) return false;
  return state.nativePromptDue;
}

/**
 * 상태만 읽는다 — **내려받지 않는다.**
 *
 * `checkForUpdates` 와 다른 점이 이것이다. 안내 화면은 "새 APK 가 있나" 만
 * 알면 되는데, 기동 훅이 이미 확인과 내려받기를 끝냈다. 여기서 또 부르면
 * 같은 순간에 두 번 내려받아 느린 회선에서 서로를 방해한다.
 *
 * 실패하면 `null` — 그러면 창이 뜨지 않는다.
 */
export async function readUpdateState(): Promise<UpdateState | null> {
  if (!isTauri()) return null;
  try {
    return await invoke<UpdateState>('update_status');
  } catch (error) {
    console.warn('[update]', error);
    return null;
  }
}

/**
 * 안내를 띄웠다고 기록한다 — 24시간 창을 닫는 자리.
 *
 * **빠뜨리면 앱을 켤 때마다 같은 창이 다시 뜨고, 그건 삭제로 이어진다.**
 * 설치를 누르지 않고 "나중에" 로 닫아도 기록한다 — 봤으면 충분하다.
 *
 * 실패해도 조용히 넘긴다. 기록을 못 남긴 것 때문에 창을 막을 이유는 없다
 * (다음 기동에 한 번 더 뜨는 정도의 대가다).
 */
export async function noteUpdatePrompted(): Promise<void> {
  if (!isTauri()) return;
  try {
    await invoke<void>('update_note_prompted');
  } catch (error) {
    console.warn('[update]', error);
  }
}

/**
 * 지금 안내를 띄울 만한가.
 *
 * 재촉하지 않기 위한 규칙은 Rust 가 들고 있다(`update_net.rs` 의
 * `should_prompt` — 24시간에 한 번). 화면은 그 판단을 묻지 않고 **새 APK 가
 * 있다는 사실만** 본다. 앱을 켤 때마다 창이 뜨면 삭제로 이어진다.
 */
export function shouldSuggestNativeUpdate(state: UpdateState | null): boolean {
  return state?.nativeAvailable === true;
}
