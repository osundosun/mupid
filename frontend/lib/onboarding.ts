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
 */

import { ApiError } from './api/client';
import { api } from './api/session';
import type { CodeOption, OnboardingOptions } from './api/endpoints';
import { genreOptions, moodOptions } from './mupid-data';

export type OnboardingChoice = { nickname: string; moods: string[]; genres: string[] };

/** 서버에서 못 받아 왔을 때 쓰는 선택지. 화면 디자인의 목록과 같다. */
const fallbackOptions: OnboardingOptions = {
  moods: moodOptions.map((name, i) => ({ code_id: -(i + 1), name })),
  genres: genreOptions.map((name, i) => ({ code_id: -(i + 1 + moodOptions.length), name })),
};

/** 한 번 받아 오면 세션 동안 다시 부르지 않는다. 바뀌지 않는 마스터 데이터다. */
let cached: Promise<OnboardingOptions> | null = null;
/** 마지막으로 돌려준 것이 서버 목록이 아니라 대체 목록이었는가. */
let servedFallback = false;

export function loadOnboardingOptions(signal?: AbortSignal): Promise<OnboardingOptions> {
  cached ??= api.onboarding
    .options(signal)
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
export function refreshOnboardingOptions(signal?: AbortSignal): Promise<OnboardingOptions> {
  cached = null;
  return loadOnboardingOptions(signal);
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
 * 서버에 저장된 설문을 읽는다.
 *
 * 아직 제출하지 않았으면 `null`. 비로그인이거나 서버가 답하지 않아도 `null` 이라
 * 호출 쪽은 "서버에 없다"로만 다루면 된다.
 */
export async function fetchOnboarding(
  signal?: AbortSignal,
): Promise<(OnboardingChoice & { submittedAt: string }) | null> {
  try {
    const status = await api.onboarding.status(signal);
    if (!status?.submitted) return null;
    return {
      submittedAt: status.submitted_at ?? new Date().toISOString(),
      nickname: status.nickname,
      moods: namesOf(status.moods),
      genres: namesOf(status.genres),
    };
  } catch (error) {
    if (signal?.aborted) return null;
    console.warn('[mupid] 온보딩 정보를 불러오지 못했어요.', error);
    return null;
  }
}

/**
 * 설문을 서버에 저장한다.
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
    await api.onboarding.submit({
      nickname: choice.nickname,
      mood_code_ids: moodCodeIds,
      genre_code_ids: genreCodeIds,
    });
  } catch (error) {
    // 이미 계정에 있으면 **목적은 이미 이뤄진 것**이다. 실패로 다루면 안 된다.
    //
    // 제출 여부를 미리 물을 길이 없을 때(`fetchOnboarding` 이 실패하는 서버가
    // 있다) 화면은 온보딩을 다시 보여 주는데, 거기서 409 를 오류로 올리면
    // 계정에 답이 있는 사람을 온보딩에 영영 가둔다. 통과시킨다.
    if (isAlreadySubmitted(error)) return;
    throw error;
  }
}
