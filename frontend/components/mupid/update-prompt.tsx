'use client';

/**
 * 앱 업데이트 안내 — **모바일 전용**.
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
 * ## 상태를 다시 받아 오지 않는다
 *
 * 확인은 `mupid-app.tsx` 의 기동 훅이 이미 했다(`checkForUpdates`). 여기서
 * 또 부르면 같은 순간에 두 번 내려받아 느린 회선에서 서로를 방해한다.
 */

import { useEffect, useRef, useState } from 'react';
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
import { useIsMobile } from '@/hooks/use-mobile';
import {
  installNativeUpdate,
  noteUpdatePrompted,
  readUpdateState,
  shouldPromptUpdate,
  type UpdateState,
} from '@/lib/platform/update';

export function UpdatePrompt() {
  const isMobile = useIsMobile();
  const [state, setState] = useState<UpdateState | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  // 띄운 사실은 **한 번만** 기록한다. 다시 그려질 때마다 부르면 창이 열려
  // 있는 동안 계속 DB 를 두드린다.
  const noted = useRef(false);

  useEffect(() => {
    let live = true;
    void readUpdateState().then((next) => {
      if (live) setState(next);
    });
    return () => {
      live = false;
    };
  }, []);

  const visible = shouldPromptUpdate({ isMobile, state });

  useEffect(() => {
    if (!visible || noted.current) return;
    noted.current = true;
    void noteUpdatePrompted();
  }, [visible]);

  if (!visible) return null;

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
