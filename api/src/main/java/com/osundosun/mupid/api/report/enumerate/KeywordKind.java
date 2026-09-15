package com.osundosun.mupid.api.report.enumerate;

/**
 * 키워드가 어느 표에서 왔는가. 화면이 색을 달리 줄 수 있게 구분해 내보낸다.
 */
public enum KeywordKind {

    /** `music_moods` → `codes(MOOD)` */
    MOOD,

    /** `music_genres` → `codes(GENRE)` */
    GENRE

}
