package com.osundosun.mupid.api.report.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.osundosun.mupid.api.report.enumerate.KeywordKind;
import com.osundosun.mupid.api.report.enumerate.ReportSource;

/**
 * '내 취향' 화면 한 장. 필드는 `spring.jackson.property-naming-strategy: SNAKE_CASE`
 * 로 snake_case 가 되어 나간다.
 */
public record ReportResponse(

    LocalDate date,

    LocalDateTime generatedAt,

    ReportSource source,

    /**
     * 그날 들은 시간 전부.
     *
     * `tracks` 는 상위 40곡만 담으므로 **41곡 이상 들은 날에는 `tracks` 의 합보다
     * 크다.** 비율(`share`)은 잘린 목록이 아니라 늘 이 값을 분모로 쓴다.
     */
    long totalListeningMs,

    /**
     * 이벤트에는 있었지만 MySQL 에서 곡을 찾지 못해 버린 청취량.
     *
     * 0 이 아니면 `track_id` 와 `music_id` 가 어긋나고 있다는 뜻이다
     * (open-questions C5). 조용히 버리지 않고 드러내려고 내보낸다.
     */
    long unmatchedMs,

    /** 청취 시간 내림차순. 트리맵의 타일이 된다. */
    List<TrackListening> tracks,

    /** 청취 시간으로 가중한 상위 키워드. */
    List<Keyword> keywords,

    /** 표본이 모자라면 `null`. 화면은 `sample.sufficient` 를 보고 갈래를 탄다. */
    Mubti mubti,

    Sample sample

) {

    public record TrackListening(

        int musicId,

        String title,

        String artistName,

        String albumTitle,

        /** 곡(TRACK) 커버. 없으면 null. */
        String imageUrl,

        long listeningMs,

        /** 전체 청취 시간 중 이 곡의 비중 (0~1). 타일 면적이 이 값이다. */
        double share

    ) {

    }

    public record Keyword(String name, KeywordKind kind, double weight) {

    }

    public record Mubti(

        /** `VTFC` 같은 네 글자. */
        String code,

        /** 유형명 — `보컬 인싸형` */
        String title,

        /** 수식 문구 — `모두의 보컬은 곧 나의 최애` */
        String subtitle,

        String description,

        String imageUrl,

        List<Axis> axes

    ) {

    }

    /**
     * 축 하나.
     *
     * `score` 는 실시간으로 흐르고 `code` 는 잘 바뀌지 않는다(이력 현상). 화면은
     * 막대를 움직이되 유형 이름은 그대로 두는 식으로 이 둘을 다르게 쓴다.
     */
    public record Axis(

        /** `A` `B` `C` `D` */
        String axis,

        String code,

        String name,

        String oppositeCode,

        String oppositeName,

        /** −1 ~ +1. 양수면 `code` 쪽이다. */
        double score,

        /** 0 ~ 1. 0 이면 근거가 없어 기본값으로 둔 축이다. 화면이 흐리게 그린다. */
        double confidence

    ) {

    }

    /**
     * 판정 근거가 얼마나 있었는가. `sufficient` 가 false 면 `mubti` 가 null 이다.
     */
    public record Sample(

        boolean sufficient,

        /** 그날의 재생 횟수. 지난 날짜(STORED)는 저장하지 않으므로 null 이다. */
        Long playCount,

        int distinctMusicCount,

        long listeningMs

    ) {

    }

}
