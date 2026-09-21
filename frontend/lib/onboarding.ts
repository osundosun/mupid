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

import { ApiError } from './api/client';
import { api } from './api/session';
import { localDemoEnabled } from './api/local-demo';
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
/** 마지막으로 돌려준 것이 서버 목록이 아니라 대체 목록이었는가. */
let servedFallback = false;

export function loadOnboardingOptions(): Promise<OnboardingOptions> {
  if (localDemoEnabled()) { servedFallback = true; return Promise.resolve(fallbackOptions); }
  // 여러 화면이 공유하는 요청은 한 화면의 unmount로 취소하지 않는다.
  cached ??= api.onboarding
    .options()
    .then((value) => {
      servedFallback = false;
      return value;
    })
    .catch((error: unknown) => {
      console.warn('[mupid] 온보딩 선택지를 불러오지 못했어요. 기본 목록을 씁니다.', error);
      // 다음 시도에서 다시 부를 수 있게 캐시를 비운다.
      cached = null;
      servedFallback = true;
      return fallbackOptions;
    });
  return cached;
}

/**
 * 캐시를 버리고 서버에서 다시 받는다.
 *
 * 저장이 `OnboardingOptionsStale` 로 막힌 뒤 화면이 부른다. 그때의 목록은
 * 이미 서버와 어긋나 있으므로 그대로 두고 다시 고르게 해 봐야 또 막힌다.
 */
export function refreshOnboardingOptions(): Promise<OnboardingOptions> {
  cached = null;
  return loadOnboardingOptions();
}

/**
 * 고른 이름이 서버 코드와 하나도 맞지 않는다.
 *
 * 선택지를 아직 못 받아 왔거나(대체 목록), 서버의 코드 데이터가 바뀐 경우다.
 * 어느 쪽이든 **고른 사람 잘못이 아니다.** 화면은 이 오류를 보고 선택지를 새로
 * 받아 다시 고르게 해야 한다 — 예전에는 "하나 이상 골라 주세요" 로 뭉뚱그려
 * 던져서, 다 골랐는데도 못 골랐다는 말을 듣고 온보딩에 갇혔다.
 */
export class OnboardingOptionsStale extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'OnboardingOptionsStale';
  }
}

/**
 * 계정에 이미 설문이 있다는 응답인가.
 *
 * 서버는 이 설문을 **한 번만** 받는다. 콜드스타트 입력이라 계속 고쳐 쓰는
 * 값이 아니라는 서버 쪽 규약이고, 다시 보내면 409 다.
 */
const isAlreadySubmitted = (error: unknown): boolean =>
  error instanceof ApiError &&
  (error.status === 409 || error.code === 'MUPID_ONBOARDING_ALREADY_SUBMITTED');

/**
 * 화면에 그릴 이름 목록.
 *
 * `display_name`을 우선적으로 사용하며, 없을 경우 `name`을 씁니다.
 */
const optionName = (option: CodeOption): string =>
  (option.display_name?.trim() || option.name.trim()).normalize('NFC');

export const namesOf = (options: CodeOption[]): string[] =>
  [...new Set(options.map(optionName).filter(Boolean))];

/**
 * 이름을 코드 번호로 바꾼다. 서버가 모르는 이름은 버린다 — 예전 브라우저 저장분에
 * 지금은 없는 항목이 남아 있을 수 있다.
 *
 * **같은 이름이 여러 번호로 오면 가장 작은 번호를 쓴다.** 서버 목록은 `code_id`
 * 오름차순이고, 같은 분위기가 중복 적재로 큰 번호에 또 있을 수 있다 — 운영에서
 * 32~48 의 17종이 98~106 · 107~123 · 124~132 로 세 번 더 들어가 있었다.
 * `new Map(options.map(...))` 는 **나중 항목이 앞 항목을 덮어쓰므로**, 화면에서
 * `고요한` 을 고른 사람이 47 이 아니라 132 를 보내게 된다. 실제로 그렇게 보낸 답이
 * `onboarding_survey` 에 쌓여 나중에 손으로 이관해야 했다.
 *
 * 서버도 분위기를 32~48 로 좁혀 내놓지만(`MoodCodeIds`), 팀원의 로컬 DB 나 옛
 * 배포본은 아직 중복을 그대로 내놓는다. 받는 쪽에서도 막는다.
 * 규약은 `api/knowledge/decision/0025-mood-codes-32-48-are-frozen.md`.
 */
const idsOf = (options: CodeOption[], names: string[]): number[] => {
  const byName = new Map<string, number>();
  for (const option of options) {
    const name = optionName(option);
    // 먼저 온 것(= 작은 code_id)을 남긴다. 덮어쓰지 않는다.
    if (!byName.has(name)) byName.set(name, option.code_id);
  }
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
    // 고른 이름이 서버 코드와 하나도 맞지 않는다. 다음에 열 때는 새로 받는다.
    cached = null;
    throw new OnboardingOptionsStale(
      servedFallback
        ? '선택지를 불러오지 못했어요. 잠시 뒤 다시 골라 주세요.'
        : '선택지가 바뀌었어요. 새로 불러왔으니 다시 골라 주세요.',
    );
  }
  try {
    // 닉네임은 보내지 않는다. 회원가입이 이미 받고 있어 같은 값을 두 번 묻는
    // 꼴이었고, 서버도 더는 받지 않는다 (`OnboardingSubmitRequest`). 고치는
    // 길은 `PATCH /api/v1/user` 하나다.
    await api.onboarding.submit({
      mood_code_ids: moodCodeIds,
      genre_code_ids: genreCodeIds,
    });
  } catch (error) {
    // 이미 계정에 있으면 **목적은 이미 이뤄진 것**이다. 실패로 다루면 안 된다.
    //
    // 화면 진입 분기는 회원정보의 `onboarding_submitted` 로 하지만, 그 값을
    // 못 받은 채 온보딩에 서 있을 수도 있다. 거기서 409 를 오류로 올리면
    // 계정에 답이 있는 사람을 온보딩에 가둔다. 통과시킨다.
    if (isAlreadySubmitted(error)) return;
    throw error;
  }
}
