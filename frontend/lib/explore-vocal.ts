/**
 * 탐색 화면의 '보컬' 선택을 서버 계약(`vocal` 쿼리)으로 옮긴다.
 *
 * <h2>왜 화면 선택 키와 서버 값을 따로 두는가</h2>
 *
 * `explore-categories.tsx` 의 선택 키는 `${큰분류}/${그룹}/${항목}`
 * (예: `보컬·악기/보컬/여성 보컬`) 이다. 화면 문구가 바뀌면 키도 바뀐다. 그 키를
 * 그대로 서버에 보내면 **문구를 손볼 때마다 API 계약이 깨진다.** 그래서 화면 문구와
 * 서버 값 사이에 이 표 하나를 둔다.
 *
 * <h2>세 값의 뜻</h2>
 *
 * - `여성 보컬` → `female`  (서버: `has_vocal = true`, `vocal_gender = 'F'`)
 * - `남성 보컬` → `male`    (서버: `has_vocal = true`, `vocal_gender = 'M'`)
 * - `인스트루멘탈` → `instrumental` (서버: `has_vocal = false`)
 *
 * 곡 제목·아티스트명·앨범명 필터(`보컬·악기/기타`, `분위기`, `장르`)는 **아직 서버
 * 계약이 없다.** 여기 없는 선택은 서버로 보내지 않는다 — 보내면 서버가 400 을 준다.
 */

/** 화면 문구 → 서버 값. 서버의 `VocalFilter` enum 이름(소문자)과 같아야 한다. */
const VOCAL_VALUES: Record<string, string> = {
  '여성 보컬': 'female',
  '남성 보컬': 'male',
  인스트루멘탈: 'instrumental',
};

/** 화면이 보컬 필터 그룹을 가리키는 키 조각. `explore-categories.tsx` 와 맞춰야 한다. */
const VOCAL_GROUP = '보컬·악기/보컬/';

/**
 * 고른 조건에서 보컬 필터만 뽑아 서버가 아는 값으로 바꾼다.
 *
 * **모르는 값은 조용히 버리지 않고 떨어뜨린다** — 서버가 400 을 주는 편이 낫다.
 * 다만 여기서 거르는 것은 보컬이 아닌 선택(기타·장르·분위기)이라, 그건 버리는 게
 * 맞다. 아직 계약이 없기 때문이다.
 *
 * @param selected `explore-categories` 가 들고 있는 선택 키들
 * @returns 중복 없는 서버 값. 아무것도 안 골랐으면 빈 배열
 */
export function vocalFilterValues(selected: readonly string[]): string[] {
  const values = selected
    .filter((key) => key.startsWith(VOCAL_GROUP))
    .map((key) => VOCAL_VALUES[key.slice(VOCAL_GROUP.length)])
    .filter((value): value is string => Boolean(value));
  return [...new Set(values)];
}

/**
 * 서버가 받는 `vocal` 쿼리 값. 고른 것이 없으면 **`undefined`** 다.
 *
 * `''` 이 아니라 `undefined` 인 이유: `''` 를 보내면 주소에 `vocal=` 이 붙고,
 * 캐시 키와 접근 로그가 같은 요청을 두 가지로 기록한다.
 */
export function vocalQueryValue(selected: readonly string[]): string | undefined {
  const values = vocalFilterValues(selected);
  return values.length ? values.join(',') : undefined;
}
