package com.osundosun.mupid.api.report.enumerate;

/**
 * 이 리포트를 어디서 만들어 왔는가. 화면이 "실시간" 표시를 켤지 정하는 근거다.
 */
public enum ReportSource {

    /** ClickHouse 에서 방금 집계했다. 오늘 날짜는 항상 이쪽이다. */
    LIVE,

    /** MySQL `reports` 에 확정 저장된 스냅샷을 읽었다. */
    STORED

}
