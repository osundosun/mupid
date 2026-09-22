package com.osundosun.mupid.api.admin.response;

import java.util.List;

import com.osundosun.mupid.api.admin.dto.AdminTimelineKind;

/**
 * 사용자 행동 타임라인의 밴드 하나 (S15P21A204-430).
 *
 * <p>가사·구간 타임라인의 {@code AdminTimelineBand} 와 모양이 닮았지만 <b>같은 것이
 * 아니다.</b> 그쪽 밴드는 관리 자원 하나(`lyric`)를 가리키고 행이 목록 조회와 같은 모양이라
 * 화면이 같은 편집기로 고칠 수 있다. 이쪽 밴드가 가리키는 것은 ClickHouse 의 이벤트
 * 스트림이고 <b>고칠 수 있는 것이 아니다</b> — 자원 이름도 식별자도 없다. 한 record 로
 * 합치면 "편집 가능한 자원" 자리가 비어 있는 밴드가 생겨, 화면이 그 빈 자리를 어떻게
 * 읽을지에 기대게 된다.
 *
 * @param group     밴드 이름표 (`SESSION`·`PLAYBACK`…). 화면이 색을 고르는 데 쓴다
 * @param label     밴드에 보일 한글 이름
 * @param kind      {@link AdminTimelineKind}. 재생 세션만 {@code RANGE} 다
 * @param total     <b>그날 이 밴드에 속한 전체 건수.</b> 아래 {@code rows} 가 잘렸는지
 *                  판단하는 근거이고, 서버가 따로 세어 보낸 값이다
 * @param rows      시각 오름차순 항목
 * @param truncated 상한에 걸려 잘렸는가.
 *
 *                  <p>화면이 {@code rows.length < total} 로 직접 판단하게 두지 않는다.
 *                  그 비교가 어긋나는 순간 잘린 사실이 조용히 사라지고, 화면은 있는 것만
 *                  그려서 "이 사람은 그날 이것밖에 안 했다" 는 거짓말이 된다.
 *
 *                  <p><b>record 구성 요소여야 한다.</b> Jackson 은 record 의 구성 요소만
 *                  직렬화하고 {@code isXxx()} 같은 파생 메서드는 내보내지 않는다 —
 *                  가사·구간 타임라인에서 실제로 그렇게 만들었다가 응답에 키가 아예 없었고,
 *                  화면이 경고를 조용히 안 그렸다
 */
public record AdminUserEventBand(
    String group,
    String label,
    AdminTimelineKind kind,
    long total,
    List<AdminUserEvent> rows,
    boolean truncated
) {

}
