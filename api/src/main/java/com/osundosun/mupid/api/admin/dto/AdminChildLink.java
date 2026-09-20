package com.osundosun.mupid.api.admin.dto;

/**
 * 이 자원의 행에서 **자식 자원으로 건너가는 링크** 하나.
 *
 * <p>"앨범 행에서 그 앨범에 속한 음악들을 볼 수 있게 한다" 를 자원 이름에 박아 넣지 않고
 * 데이터로 푼 것이다. 화면은 이 목록만 보고 링크를 그리므로, 링크를 늘리려면
 * `AdminResourceRegistry.CHILD_LINKS` 에 한 줄 넣으면 된다.
 *
 * <p>링크는 자식 자원을 **부모를 가리키는 필터로 걸어서** 연다. 자식 자원 목록 API 가
 * 이미 `filter.<필드>=<값>` 을 받으므로 새 엔드포인트가 필요 없다.
 * @param label         링크에 보일 이름 ("음악")
 * @param childResource 열 자식 자원 이름 (`music` — URL 조각이기도 하다)
 * @param filterField   자식 자원에서 부모를 가리키는 필드 이름 (`album`). 목록 조회의
 *                      `filter.album=<부모 id>` 로 나간다.
 */
public record AdminChildLink(
    String label,
    String childResource,
    String filterField
) {
}
