package com.osundosun.mupid.api.admin.response;

import java.util.List;
import java.util.Map;

import com.osundosun.mupid.api.admin.dto.AdminTimelineAxis;

/**
 * 타임라인의 밴드 하나 = 축 하나.
 *
 * <p>축이 무엇인지({@link AdminTimelineAxis})와 그 축의 항목들을 함께 보낸다. 화면이
 * 자원 이름을 몰라도 그릴 수 있게 하려는 것이다 — 380-A 가 `AdminField.relation`·
 * `AdminChildLink` 로 컬럼과 링크를 데이터로 만든 것과 같은 규칙이다.
 *
 * @param axis      이 밴드를 어떻게 그릴지 (점/구간, 시간 필드, 이름 필드)
 * @param total     음악 하나에 달린 항목 수. 아래 {@code rows} 가 잘렸는지 판단하는 근거다
 * @param rows      시간 오름차순 항목. 각 항목은 자원 목록 조회와 **같은 모양**의 행 `Map`
 *                  이라 `_id` 로 그대로 수정(`PATCH`)할 수 있다
 * @param truncated 상한에 걸려 잘렸는가.
 *
 *                  <p>화면이 잘린 사실을 말할 수 있게 **서버가 계산해 보낸다.** 조용히
 *                  잘리면 화면은 있는 것만 그려서 "이 곡에 가사가 이것뿐" 이라는 거짓말이
 *                  된다.
 *
 *                  <p><b>record 구성 요소여야 한다 — 계산 메서드로 두면 안 된다.</b> Jackson
 *                  은 record 의 구성 요소만 직렬화하고 `isXxx()`·`xxx()` 같은 파생 메서드는
 *                  내보내지 않는다. 실제로 처음에 메서드로 만들었다가 **응답에 키가 아예
 *                  없었고**, 화면은 `undefined` 를 거짓으로 읽어 잘렸다는 경고를 조용히
 *                  안 그렸다(silent). 실호출로만 드러나는 부류다.
 */
public record AdminTimelineBand(
    AdminTimelineAxis axis,
    long total,
    List<Map<String, Object>> rows,
    boolean truncated
) {

}
