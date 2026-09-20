import { useEffect, useState } from 'react';

/**
 * 화상 키보드가 아래에서 가린 높이(CSS px). 키보드가 없으면 0.
 *
 * `visualViewport` 는 키보드가 올라오면 보이는 영역만큼 줄어든다. 페이지 높이
 * (`innerHeight`)와의 차이가 곧 가린 높이다. 안드로이드 크롬처럼 페이지 자체가
 * 줄어드는 환경(`interactive-widget=resizes-content`)에서는 둘이 같이 줄어 0 이
 * 되는데, 그때는 `100dvh` 가 이미 키보드 위까지라 따로 더할 것이 없다. iOS 와
 * 안드로이드 웹뷰처럼 페이지는 그대로 두고 키보드가 위에 덮이는 환경에서만
 * 양수가 나온다. `offsetTop` 은 iOS 가 입력칸을 보이려고 페이지를 밀어 올린
 * 몫이다 — 그만큼은 위로 사라진 것이니 아래 가림에서 뺀다.
 *
 * 이 앱은 **덮이는 쪽을 골랐다**(`app/layout.tsx` 의 `interactiveWidget:
 * 'overlays-content'`). 그래서 안드로이드 웹뷰에서도 이 값이 일관되게 양수로
 * 잡힌다 — 로그인·회원가입 대화상자가 그만큼 줄어든다
 * (`app/dialog-viewport.css`).
 */
export function useKeyboardInset(): number {
  const [inset, setInset] = useState(0);
  useEffect(() => {
    const viewport = window.visualViewport;
    if (!viewport) return;
    const measure = () => {
      const covered = window.innerHeight - viewport.height - viewport.offsetTop;
      // 주소창이 접히고 펴지는 몫(수십 px)은 키보드가 아니다.
      setInset(covered > 80 ? Math.round(covered) : 0);
    };
    measure();
    viewport.addEventListener('resize', measure);
    viewport.addEventListener('scroll', measure);
    return () => {
      viewport.removeEventListener('resize', measure);
      viewport.removeEventListener('scroll', measure);
    };
  }, []);
  return inset;
}

/**
 * 가려진 높이를 CSS 변수로 `<html>` 에 적는다. 지금 키보드가 가린 높이를 돌려준다.
 *
 * 훅(`useKeyboardInset`)은 **한 컴포넌트 안에서만** 쓸 수 있다. 그런데 이 값을
 * 필요로 하는 자리는 하나가 아니다 — 로그인·회원가입 대화상자와 채팅 패널이
 * 각각 다른 나무에 있고, 대화상자는 포털로 `body` 아래에 따로 붙는다. 그래서
 * 각자 재는 대신 한 곳에서 잰 값을 뿌리(`<html>`)에 적고, CSS 가
 * `var(--keyboard-inset, 0px)` 로 읽는다.
 *
 * 이 일은 **앱이 뜰 때 한 번** 붙으면 된다 — `MupidApp` 이 부르고, 웹에서는
 * `visualViewport` 만 보고 조용히 0 으로 남는다.
 */
export function watchKeyboardInset(): void {
  if (typeof window === 'undefined') return;
  const viewport = window.visualViewport;
  if (!viewport) return;

  const apply = () => {
    const covered = window.innerHeight - viewport.height - viewport.offsetTop;
    const inset = covered > 80 ? Math.round(covered) : 0;
    /*
     * `html` 과 `body` 양쪽에 적는다. 대화상자는 포털이라 `body` 아래에 붙는데,
     * `body` 에 변수가 없으면 상속으로 받지 못해 늘 0 으로 읽는다.
     */
    const value = `${inset}px`;
    document.documentElement.style.setProperty('--keyboard-inset', value);
    document.body?.style.setProperty('--keyboard-inset', value);
  };

  apply();
  viewport.addEventListener('resize', apply);
  viewport.addEventListener('scroll', apply);
}
