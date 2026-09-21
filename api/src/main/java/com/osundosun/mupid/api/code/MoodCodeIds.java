package com.osundosun.mupid.api.code;

/**
 * 무드 코드가 쓰는 <b>동결된 번호대</b> — {@code codes.code_id} 32~48 의 17종.
 *
 * <p>⛔ <b>이 범위를 넓히거나 옮기려면 사람에게 확인받는다</b> —
 * {@code knowledge/decision/0025-mood-codes-32-48-are-frozen.md}. 상수 두 개를 고치는
 * 일이라 쉬워 보이지만, 이 번호는 사용자의 온보딩 답({@code onboarding_survey.mood_code_id})이
 * 가리키고 있어 되돌릴 수 없다.
 *
 * <p><b>왜 번호를 코드에 박아 두나.</b> 운영 DB 의 {@code codes} 에는 17종 말고도 무드가
 * 더 있다 — 2026-09-18·09-21 에 옛 부트스트랩이 같은 17종을 새 번호로 다시 넣어
 * 98~106 · 107~123 · 124~132 가 쌓였다. 그 행들은 참조가 걸려 있어 한 번에 지울 수 없고,
 * 지우기 전까지는 <b>"MOOD 인 코드를 전부 가져온다" 가 곧 중복을 화면에 내놓는 것</b>이다.
 * 실제로 온보딩 선택지에 같은 분위기가 두 칩으로 떴고, 그 중복 칩을 고른 답이
 * {@code onboarding_survey} 에 쌓였다.
 *
 * <p>그래서 온보딩은 용도({@code code_type})가 아니라 <b>번호</b>로 고른다. 중복분을 다
 * 치우고 나서도 이 경계는 남겨 둔다 — 팀원의 로컬 DB 는 정리 시점이 제각각이라, 경계가
 * 없으면 같은 일이 로컬에서 되풀이된다.
 *
 * <p><b>전처리가 무드를 늘리는 경우.</b> 곡을 추가로 전처리하면서 새 무드가 나오면 그
 * 번호는 이 범위 밖이다. 그 번호는 곡에 붙어({@code music_moods}) 탐색 화면에 자연히
 * 나타나지만, <b>온보딩 선택지에는 이 범위를 넓히기 전까지 나오지 않는다.</b> 넓히는 것은
 * ADR 0025 의 확인 절차를 거쳐 사람이 정한다.
 */
public final class MoodCodeIds {

    /** 동결 구간의 첫 번호. {@code serene}(잔잔한). */
    public static final int FIRST = 32;

    /** 동결 구간의 끝 번호. {@code exciting}(짜릿한). */
    public static final int LAST = 48;

    /** 동결 구간의 개수. 17종. */
    public static final int COUNT = LAST - FIRST + 1;

    /** 이 번호가 동결 구간 안인가. */
    public static boolean isFrozen(int codeId) {
        return codeId >= FIRST && codeId <= LAST;
    }

    private MoodCodeIds() {
    }

}
