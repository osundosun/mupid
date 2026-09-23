'use client';

/**
 * 서버에 닿기 전에는 서비스를 열지 않는다 (S15P21A204-444).
 *
 * 음악 스트리밍 서비스라 API 서버가 없으면 로그인도, 곡 목록도, 재생도 안 된다.
 * 예전에는 그대로 화면을 열어서 "음악을 불러오지 못했어요" 같은 오류가 화면마다
 * 따로 떴고, 앱은 기기에 남긴 곡 목록으로 화면을 채워 **목록은 보이는데 재생이
 * 안 되는** 상태가 됐다. 이제는 웹이든 앱이든 처음 켜질 때 한 번
 * `GET /api/actuator/health` 를 보고, `UP` 이 아니면 닫을 수 없는 창을 띄운다.
 *
 * - **자식을 그리지 않는다.** 그려 두고 창만 덮으면 그 아래에서 세션 복원·곡 목록
 *   요청이 돌아 오류 알림이 창 뒤에 쌓인다.
 * - **닫는 길이 없다.** 바깥을 눌러도, Escape 를 눌러도 닫히지 않는다. 할 수 있는
 *   것은 "다시 시도" 뿐이다.
 * - **처음 한 번만 본다.** 쓰는 도중에 서버가 잠깐 끊기는 것은 각 요청이 알린다.
 *   도중에 창이 튀어나오면 듣던 곡이 끊긴 것보다 더 크게 망가진 것처럼 보인다.
 * - 로컬 데모에는 서버가 없다. 묻지 않고 연다.
 */

import { useEffect, useState, type ReactNode } from 'react';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { api } from '@/lib/api/session';
import { localDemoEnabled } from '@/lib/api/local-demo';
import { isServerUp } from '@/lib/server-health';

type GateState = 'checking' | 'up' | 'down';

/** 이보다 빨리 끝나면 "연결하고 있어요" 문구를 띄우지 않는다. 깜빡이지 않게. */
const QUIET_MS = 400;

export function ServerGate({ children }: { children: ReactNode }) {
  const [state, setState] = useState<GateState>('checking');
  const [attempt, setAttempt] = useState(0);
  const [slow, setSlow] = useState(false);

  useEffect(() => {
    if (localDemoEnabled()) {
      setState('up');
      return;
    }
    const abort = new AbortController();
    const timer = window.setTimeout(() => setSlow(true), QUIET_MS);
    setState('checking');
    void isServerUp(api.system.health, abort.signal).then((up) => {
      if (abort.signal.aborted) return;
      window.clearTimeout(timer);
      setState(up ? 'up' : 'down');
    });
    return () => {
      abort.abort();
      window.clearTimeout(timer);
    };
  }, [attempt]);

  if (state === 'up') return <>{children}</>;

  const retrying = state === 'checking';
  return (
    <main className="mupid-shell server-gate" aria-busy={retrying}>
      {retrying && slow && attempt === 0 && <output>서버에 연결하고 있어요…</output>}
      <AlertDialog open={state === 'down' || (retrying && attempt > 0)} onOpenChange={() => {}}>
        <AlertDialogContent className="sky-dialog">
          <AlertDialogHeader>
            <AlertDialogTitle>서버에 연결할 수 없어요</AlertDialogTitle>
            <AlertDialogDescription>
              뮤피드는 서버에 연결돼야 음악을 들을 수 있어요. 인터넷 연결을 확인하거나
              잠시 뒤에 다시 시도해 주세요.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogAction disabled={retrying} onClick={() => setAttempt((value) => value + 1)}>
              {retrying ? '확인하고 있어요…' : '다시 시도'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </main>
  );
}
