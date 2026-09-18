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
