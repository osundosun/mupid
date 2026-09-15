/**
 * 계정 정보 수정의 서버 연동.
 *
 * 마이페이지에서 닉네임을 바꾸면 이 길로 간다. 브라우저 저장
 * (`mupidService.savePreferences`)만 고치면 새로고침에 되돌아가 버린다 —
 * 표시 이름의 기준은 계정 쪽(`PATCH /api/v1/user`)이다. 온보딩 조회도
 * 같은 값을 돌려주므로 다시 들어와도 바뀐 이름이 따라온다.
 */

import { api } from './api/session';

/** 서버 `UserUpdateRequest` 의 제약과 같은 값이다. */
export const NICKNAME_MIN_LENGTH = 2;
export const NICKNAME_MAX_LENGTH = 10;

/** 앞뒤 공백을 버리고 서버가 받는 길이까지만 남긴다. */
export const normalizeNickname = (value: string): string =>
  value.trim().slice(0, NICKNAME_MAX_LENGTH);

/**
 * 닉네임을 계정에 저장한다.
 *
 * 실패하면 던진다. 화면이 "저장되지 않았다"고 알려야 한다 — 조용히 넘기면
 * 바뀐 줄 알고 나갔다가 새로고침에서 예전 이름을 다시 만난다.
 *
 * @returns 실제로 저장된 이름. 화면 상태는 이 값으로 맞춘다.
 */
export async function updateNickname(nickname: string): Promise<string> {
  const value = normalizeNickname(nickname);
  if (value.length < NICKNAME_MIN_LENGTH) {
    throw new Error(`닉네임은 ${NICKNAME_MIN_LENGTH}~${NICKNAME_MAX_LENGTH}자로 입력해 주세요.`);
  }
  await api.user.update({ nickname: value });
  return value;
}
