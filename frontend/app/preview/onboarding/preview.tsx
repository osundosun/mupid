'use client';

import { useState } from 'react';
import { WelcomeView } from '@/components/mupid/collection';
import type { MupidModel } from '@/hooks/use-mupid';

export default function OnboardingPreview() {
  const [completed, setCompleted] = useState(false);
  const model = {
    demoSession: {},
    onboardingSubmitted: false,
    completeOnboarding: async () => { setCompleted(true); },
    go: () => {},
  } as unknown as MupidModel;

  return <main className="mupid-shell page-welcome no-mini">
    {completed ? <div className="welcome-page">
      <p role="status">미리보기가 완료됐어요. 선택 내용은 저장되지 않았어요.</p>
      <button className="primary-button" onClick={() => setCompleted(false)}>다시 보기</button>
    </div> : <WelcomeView model={model} />}
  </main>;
}
