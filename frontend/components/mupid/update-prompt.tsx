'use client';

/**
 * 앱 업데이트 안내 — **모바일 전용, 그리고 로그인과 무관하게 뜬다.**
 *
 * 새 APK 가 있을 때만 뜨고, 설치 화면으로 넘기는 일만 한다. 설치 자체는
 * 안드로이드가 한다(사용자가 "이 출처의 앱 설치" 를 허용해야 한다).
 *
 * ## 왜 모바일 전용인가
 *
 * APK 설치는 **안드로이드에만 있는 일**이다. 데스크톱 개발 빌드에서 같은
 * 창을 띄우면 "눌러도 아무 일도 일어나지 않는 창" 이 되고, 그건 사용자에게
 * 고장으로 보인다. 그래서 두 조건을 모두 본다.
 *
 * - 폭이 좁은가 (`useIsMobile`) — 앱은 폰에서 쓰는 물건이라 창도 폰 크기에
 *   맞춘다. 데스크톱 브라우저로 같은 화면을 열었을 때 뜨지 않는다.
 * - **설치가 가능한 플랫폼인가** (`canInstallNative`) — Rust 가
 *   `cfg!(target_os = "android")` 로 알려 준다. 창 너비로 플랫폼을 추측하면
 *   태블릿이나 가로 화면에서 어긋난다.
 *
 * 조건 판단은 `shouldPromptUpdate` 한 곳에 있다(`lib/platform/update.ts`) —
 * 렌더 안에 섞어 두면 창을 띄워 보지 않고는 규칙을 확인할 수 없다.
 *
 * ## 왜 로그인 화면에도 있어야 하나
 *
 * 이 컴포넌트는 **`app/layout.tsx`(root)에 있다.** 예전에는 인증이 필요한
 * `(music)` 그룹 안에만 있었고, 그래서 **로그인 전에는 업데이트를 알 수
 * 없었다.** 확인 훅이 거기 같이 있었기 때문이다.
 *
 * 실기기에서 확인했다 — 랜딩 화면에 머무는 동안 엣지 로그에 앱의
 * `native.json` 요청이 **0건**이었고, 로그인하니 그때부터 요청이 시작됐다.
 * 업데이트는 로그인과 무관한 일이다: 서버가 새 판을 올렸으면 **아직
 * 로그인하지 않은 사용자도** 그것을 알아야 한다.
 *
 * ## 재촉하지 않는다
 *
 * 앱을 켤 때마다 설치 창이 뜨면 삭제로 이어진다. 그래서
 *
 * - 24시간에 한 번만 띄운다 (`nativePromptDue` — 기록은 Rust 가 DB 에 남긴다.
 *   화면 메모리에 두면 앱을 껐다 켤 때마다 되살아난다).
 * - **"나중에" 를 넣는다.** 닫을 방법이 없는 창은 없는 것만 못하다.
 * - 띄운 사실을 **즉시 기록**한다(`noteUpdatePrompted`). 설치를 안 누르고
 *   닫아도 마찬가지다 — 봤으면 다시 띄울 이유가 없다.
 *
 * ## 재생을 끊지 않는다
 *
 * 안내는 화면 위에 겹쳐 뜨고 배경 재생은 그대로 돈다. 업데이트는 곡을 멈출
 * 이유가 아니다.
 *
 * ## 느리면 진행 화면을 띄운다 (대비책)
 *
 * 정상은 전체가 1초 안쪽이라 사용자가 아무것도 못 본다. 그런데 느린 회선에서는
 * 수십 초가 걸릴 수 있고, 그때 화면이 멈춘 것처럼 보인다.
 *
 * 그래서 **임계값을 넘겼을 때만** 오버레이를 그린다(`withSlowNotice`).
 * 먼저 띄우고 빨리 끝나면 숨기는 방식이 아니라, **타이머가 먼저 끝나야**
 * 그린다 — 빨리 끝나면 DOM 에 아예 안 나타나므로 깜빡이지 않는다.
 *
 * 조작은 **막는다**(백드롭). 이 시점은 콜드 스타트 직후라 사용자가 하던 일이
 * 없고, 반쯤 교체된 상태로 만지는 것보다 잠깐 막는 편이 낫다.
 *
 * ## 확인과 상태 읽기를 **이 컴포넌트가** 한다
 *
 * 예전에는 확인을 `mupid-app.tsx` 의 기동 훅이 했다. 그 훅은 인증 뒤에만
 * 있어서, 이 컴포넌트를 root 로 옮기면 **확인은 안 되고 창만 뜨는** (또는
 * 그 반대의) 어긋난 상태가 된다. 그래서 확인을 여기로 합쳤다.
 *
 * 기동 훅이 하던 웹 갈래(번들 활성화)는 `checkForUpdates` 안에 있으므로
 * 그대로 함께 돈다 — 두 곳에 두면 매니페스트를 중복으로 받는다.
 * 진행 화면도 같은 호출에 얹는다(`onSlow`/`onDone`) — 따로 부르면 확인이
 * 두 번이 되어 매니페스트를 중복으로 받는다.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Spinner } from '@/components/ui/spinner';
import { useIsMobile } from '@/hooks/use-mobile';
import { useDismissableOverlay } from '@/hooks/use-dismissable-overlay';
import {
  checkForUpdates,
  installNativeUpdate,
  noteUpdatePrompted,
  shouldPromptUpdate,
  type UpdateState,
} from '@/lib/platform/update';

/**
 * 번들을 내려받아 교체하는 중임을 알리는 화면 — **느릴 때만 뜬다.**
 *
 * ## 왜 필요한가
 *
 * 정상은 1초 안쪽이라 아무것도 안 띄운다. 그런데 느린 회선에서는 수십 초가
 * 걸리고, 그동안 화면이 멈춘 것처럼 보인다. 이건 대비책이지 평소 흐름이
 * 아니다 — 임계값(`UPDATE_PROGRESS_DELAY_MS`)을 넘겼을 때만 그린다.
 *
 * ## 레이아웃
 *
 * 인디케이터가 **가로 가운데** 위, 라벨이 아래에 **가운데 정렬**. 둘 다
 * `align-items: center` 로 세로축에서 가운데를 맞추고, 컨테이너가 화면
 * 전체(`inset: 0`)를 덮는다. 스타일은 `mupid.css` 의 `.update-progress*` 에
 * 있다 — 여기 인라인으로 두면 미디어 쿼리·reduced-motion 을 못 쓴다.
 *
 * ## 조작을 막는다
 *
 * 백드롭이 포인터를 먹는다. 반쯤 교체된 화면을 만지는 것보다 잠깐 막는 편이
 * 낫고, 이 시점은 콜드 스타트 직후라 사용자가 하던 일이 없다.
 *
 * ## 진행률을 보여 주지 않는다
 *
 * 얼마나 남았는지 알 수 없다 — 아카이브 크기는 알지만 전개 속도는 기기마다
 * 다르다. **모르는 숫자를 지어내면** 멈춘 것처럼 보인다. 불확정 인디케이터가
 * 정직하다.
 *
 * ## 스크린리더
 *
 * `role="status"` + `aria-live="polite"` 로 알린다. 시각적으로만 알리면
 * 화면을 못 보는 사용자에게는 앱이 그냥 멈춘 것으로 보인다.
 */
function ApplyingOverlay() {
  return (
    <div className="update-progress" role="status" aria-live="polite">
      <Spinner className="update-progress-spinner" aria-label="업데이트 진행 중" />
      <p className="update-progress-label">앱을 업데이트하고 있어요</p>
    </div>
  );
}

export function UpdatePrompt() {
  const isMobile = useIsMobile();
  const [state, setState] = useState<UpdateState | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  // **느릴 때만** 참이 된다. 빨리 끝나면 한 번도 참이 되지 않으므로 화면이
  // 깜빡이지 않는다.
  const [applying, setApplying] = useState(false);
  // 띄운 사실은 **한 번만** 기록한다. 다시 그려질 때마다 부르면 창이 열려
  // 있는 동안 계속 DB 를 두드린다.
  const noted = useRef(false);

  const showProgress = useCallback(() => setApplying(true), []);
  const hideProgress = useCallback(() => setApplying(false), []);

  useEffect(() => {
    let live = true;
    // 기동 뒤 한 번 확인한다. **첫 화면을 막지 않는다** — 결과를 기다리지 않고
    // 던져 두기만 한다. 새 웹 번들이 있으면 디스크에만 활성화되고 화면은 옛
    // 번들로 계속 돈다(다음 콜드 스타트에 교체된다). 그래야 듣던 곡이 끊기지
    // 않는다. 실패해도 아무 일도 일어나지 않는다.
    //
    // 반환값에는 `nativeAvailable` 이 들어 있다 — 매니페스트 두 개는 한 번의
    // 확인에서 함께 읽히므로, 상태를 따로 읽지 않아도 된다.
    void checkForUpdates({ onSlow: showProgress, onDone: hideProgress }).then(
      (next) => {
        if (live) setState(next);
      },
    );
    return () => {
      live = false;
    };
  }, [showProgress, hideProgress]);

  const visible = shouldPromptUpdate({ isMobile, state });

  /*
   * 뒤로가기로 닫히게 한다 (S15P21A204-410). 이 창은 **비제어**라 열림 상태가
   * 스택에 있어야 뒤로가기가 닫을 수 있다 — 다른 오버레이와 같은 규칙을 따르게
   * 한 것이다. 닫으면 `state` 를 비우는데, 그건 지금 "나중에" 를 누르는 것과
   * 같은 결과다(다시 띄우지 않는다). 설치 중에는 닫지 않는다 — 설치 화면으로
   * 넘어가는 중에 창만 사라지면 진행 상황을 볼 자리가 없어진다.
   */
  useDismissableOverlay(visible && !busy, () => setState(null));

  useEffect(() => {
    if (!visible || noted.current) return;
    noted.current = true;
    void noteUpdatePrompted();
  }, [visible]);

  // 진행 화면이 떠 있는 동안에는 안내 창을 겹쳐 띄우지 않는다. 둘 다 뜨면
  // 사용자가 어느 것을 눌러야 할지 모른다 — 교체가 끝나면 창이 뜬다.
  if (!visible || applying) return applying ? <ApplyingOverlay /> : null;

  const install = async () => {
    setBusy(true);
    setError('');
    try {
      await installNativeUpdate();
      // 설치 화면이 뜨면 이 창은 할 일이 끝났다. 사용자가 설치를 마치면 앱이
      // 다시 시작되므로 상태도 새로 읽힌다.
      setState(null);
    } catch (reason) {
      // **실패를 삼키지 않는다.** 사용자가 직접 누른 일이고, 아무 일도
      // 일어나지 않으면 왜인지 알 방법이 없다. Rust 가 "이 출처의 앱 설치를
      // 허용해 주세요" 같은 문구를 그대로 준다.
      setError(
        typeof reason === 'string' && reason.trim()
          ? reason
          : '설치를 시작하지 못했어요. 잠시 뒤에 다시 시도해 주세요.',
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <AlertDialog open onOpenChange={(open) => !open && setState(null)}>
      <AlertDialogContent className="sky-dialog update-dialog">
        <AlertDialogHeader>
          <AlertDialogTitle>새 버전이 있어요</AlertDialogTitle>
          <AlertDialogDescription>
            뮤피드가 더 나은 모습으로 돌아왔어요.
            <br />
            설치하면 바로 쓸 수 있어요.
          </AlertDialogDescription>
        </AlertDialogHeader>

        {error ? (
          <p className="update-dialog-error" role="alert">
            {error}
          </p>
        ) : null}

        <AlertDialogFooter>
          <AlertDialogCancel disabled={busy}>나중에</AlertDialogCancel>
          <AlertDialogAction
            disabled={busy}
            onClick={(event) => {
              // 창이 먼저 닫히면 진행 상황을 보여 줄 자리가 사라진다.
              event.preventDefault();
              void install();
            }}
          >
            {busy ? '준비하는 중…' : '지금 설치'}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
