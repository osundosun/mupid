/**
 * 온보딩 설문의 서버 연동.
 *
 * 화면은 분위기·장르를 **이름**으로 다루고 서버는 **코드 번호**로 주고받는다.
 * 이름이 바뀌어도 저장된 응답이 흔들리지 않게 하려는 서버 쪽 규약이라,
 * 번역은 여기서 한다.
 *
 * 서버가 없거나 비로그인일 때도 온보딩 화면은 떠야 한다. 온보딩은 앱에 들어가는
 * 관문이라 여기서 막히면 아무것도 못 한다. 그래서 선택지는 못 받아 오면
 * `lib/mupid-data.ts` 의 기본 목록으로 되돌아가고, 저장은 브라우저 쪽
 * (`mupidService.savePreferences`)이 따로 맡는다.
 *
 * **읽는 함수는 없다.** 제출한 내용을 화면이 되돌려 받는 API 가 없어서다.
 * 진입 분기에 필요한 "마쳤는가" 는 `GET /api/v1/user` 가 실어 보낸다.
 */

import { api } from './api/session';
import type { CodeOption, OnboardingOptions } from './api/endpoints';
import { genreOptions, moodOptions } from './mupid-data';

export type OnboardingChoice = { moods: string[]; genres: string[] };

/** 서버에서 못 받아 왔을 때 쓰는 선택지. 화면 디자인의 목록과 같다. */
const fallbackOptions: OnboardingOptions = {
  moods: moodOptions.map((name, i) => ({ code_id: -(i + 1), name })),
  genres: genreOptions.map((name, i) => ({ code_id: -(i + 1 + moodOptions.length), name })),
};

/** 한 번 받아 오면 세션 동안 다시 부르지 않는다. 바뀌지 않는 마스터 데이터다. */
let cached: Promise<OnboardingOptions> | null = null;

export function loadOnboardingOptions(signal?: AbortSignal): Promise<OnboardingOptions> {
  cached ??= api.onboarding.options(signal).catch((error: unknown) => {
    console.warn('[mupid] 온보딩 선택지를 불러오지 못했어요. 기본 목록을 씁니다.', error);
    // 다음 시도에서 다시 부를 수 있게 캐시를 비운다.
    cached = null;
    return fallbackOptions;
  });
  return cached;
}

/** 서버에서 받은(또는 기본) 선택지를 이름만 뽑아 준다. 화면 칩이 쓰는 모양이다. */
export const namesOf = (options: CodeOption[]): string[] => options.map((o) => o.name);

/**
 * 이름을 코드 번호로 바꾼다. 서버가 모르는 이름은 버린다 — 예전 브라우저 저장분에
 * 지금은 없는 항목이 남아 있을 수 있다.
 */
const idsOf = (options: CodeOption[], names: string[]): number[] => {
  const byName = new Map(options.map((o) => [o.name, o.code_id]));
  const ids: number[] = [];
  for (const name of names) {
    const id = byName.get(name);
    if (typeof id === 'number' && id > 0) ids.push(id);
  }
  return ids;
};

/**
 * 설문을 서버에 저장한다. **한 번만 할 수 있다.**
 *
 * 두 번째 제출은 서버가 409 로 막는다 — 온보딩은 이력이 없는 사람의 첫 추천을
 * 만드는 콜드스타트 입력이라 고쳐 쓰는 값이 아니다. 그래서 이 함수를 부르는
 * 곳은 온보딩 화면 하나뿐이다.
 *
 * 실패하면 던진다 — 브라우저 저장과 달리 이건 조용히 넘어가면 안 된다.
 * 호출 쪽이 "계정에는 저장되지 않았다"고 알려야 한다.
 */
export async function saveOnboarding(choice: OnboardingChoice): Promise<void> {
  const options = await loadOnboardingOptions();
  const moodCodeIds = idsOf(options.moods, choice.moods);
  const genreCodeIds = idsOf(options.genres, choice.genres);
  if (!moodCodeIds.length || !genreCodeIds.length) {
    throw new Error('분위기와 장르를 각각 하나 이상 골라 주세요.');
  }
  await api.onboarding.submit({
    mood_code_ids: moodCodeIds,
    genre_code_ids: genreCodeIds,
  });
}
