package com.osundosun.mupid.api.music.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 오브젝트 스토리지에 올라가는 파형 파일의 모양 —
 * `{"sample_count": 160, "values": [...]}`.
 *
 * <p>응답 DTO(`WaveformResponse`)와 필드가 겹치지만 <b>일부러 나눠 두었다.</b>
 * 이쪽은 <b>디스크에 굳는 형식</b>이라 한번 올라간 파일을 계속 읽을 수 있어야
 * 하고, 저쪽은 API 계약이라 곡 번호처럼 파일에 없는 것이 붙는다. 하나로 합치면
 * 응답에 필드를 더하는 순간 예전 파일이 읽히지 않는다.
 *
 * <p><b>이름을 전역 설정에 맡기지 않고 직접 적는다.</b> `spring.jackson.
 * property-naming-strategy` 는 <b>응답</b>의 규약이다. 그걸 이 파일에도 쓰면,
 * 누가 그 설정을 바꾸는 순간 <b>이미 스토리지에 올라간 파형을 못 읽는다</b> —
 * Redis 의 재생 큐 문서가 같은 이유로 전역 매퍼를 쓰지 않는다
 * (`PlayQueueRepository`).
 */
public record WaveformFile(

    @JsonProperty("sample_count")
    int sampleCount,

    @JsonProperty("values")
    List<Double> values

) {

}
