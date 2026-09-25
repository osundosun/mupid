package com.osundosun.mupid.api.recommendation.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;

import com.osundosun.mupid.api.music.dto.MusicSummary;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;

/**
 * 추천 응답의 <b>최종 필터</b> — 계약 v3 에서 Spring 에 남는 일 (S15P21A204-458, 설계 §9.4).
 *
 * <p>추천 서버는 이제 후보를 우리 본문이 아니라 ClickHouse 사본에서 고른다. 사본은 늦을 수 있어서
 * (곡을 지운 직후, 스트림이 막 실패한 직후) 응답에 지워진 곡이 섞여 올 수 있다. 정본은 MySQL 이므로
 * 여기서 <b>MySQL 기준으로</b> 한 번 더 거른다. 네 곳(오늘의 모먼트·이어 듣기·대화 추천·닮은 구간)이
 * 같은 규칙을 써야 해서 한 곳에 둔다 — 예전에는 대화 추천만 재생 불가를 뺐고, 오늘의 모먼트는
 * 지워진 곡도 그대로 저장했다.
 *
 * <ol>
 *   <li>{@code external_id} → 구간. MySQL 에 없으면 뺀다(구간 적재가 색인보다 낡았다)</li>
 *   <li>구간 → 곡. 지워졌으면 뺀다 — 조립기({@code MusicSummaryAssembler.byIds})가 삭제된 곡을 돌려주지
 *       않는다</li>
 *   <li>재생할 스트림({@code DONE})이 없으면 뺀다({@link MusicSummary#playable()}). 후보 질의
 *       ({@code findCandidateMusic})와 같은 뜻이다</li>
 *   <li>같은 곡이 두 번 오면 <b>앞선 순위만</b> 남긴다. 추천 목록은 곡 목록이라 한 곡이 두 줄이면 안 된다</li>
 *   <li>앞에서부터 {@code limit} 개로 자른다. 추천 서버는 이 필터 몫으로 {@code limit} 보다 몇 개 더
 *       준다(절반, 최소 2) — 모자라면 모자란 대로 둔다. 억지로 채우지 않는다</li>
 * </ol>
 *
 * <p>스프링 빈이 아니다. 조회는 부르는 쪽이 넘기는 함수로 한다 — 대화 추천은 곡을 상세 조회
 * ({@code findDetailAllByIds})로 읽고 나머지는 조립기로 읽는데, 그 차이 때문에 규칙이 두 벌이
 * 될 이유는 없다.
 */
public final class RecommendationFinalFilter {

    private RecommendationFinalFilter() {
    }

    /**
     * 남은 추천 하나. 순서는 추천 서버가 준 순위 그대로다.
     *
     * @param item    추천 서버가 준 줄
     * @param section MySQL 구간
     * @param music   곡 표현. 살아 있고 재생할 수 있다
     */
    public record Kept<T>(T item, MusicSectionAnalysis section, MusicSummary music) {
    }

    /**
     * 거른 결과와 버린 건수.
     *
     * @param kept           남은 것. 많아야 {@code limit} 개
     * @param received       추천 서버가 준 수(여유분 포함)
     * @param missingSection MySQL 에 구간이 없다
     * @param missingMusic   곡이 없거나 지워졌다
     * @param notPlayable    재생할 스트림이 없다
     * @param duplicate      같은 곡의 뒤 순위
     * @param cut            다 거르고도 {@code limit} 을 넘어 자른 수 — 버린 것이 아니라 여유분이다
     */
    public record Outcome<T>(
        List<Kept<T>> kept,
        int received,
        int missingSection,
        int missingMusic,
        int notPlayable,
        int duplicate,
        int cut
    ) {

        /** 걸러서 버린 수. 자른 여유분은 넣지 않는다. */
        public int dropped() {
            return missingSection + missingMusic + notPlayable + duplicate;
        }
    }

    /**
     * @param items            추천 서버가 준 순서대로
     * @param externalIdOf     줄에서 구간 문자열 ID 를 꺼낸다
     * @param limit            화면에 줄 수
     * @param sectionsOf       문자열 ID → MySQL 구간({@code MusicSectionService.findByExternalIds})
     * @param summariesOf      곡 번호 → 곡 표현. <b>삭제된 곡은 돌려주지 않아야 한다</b>. 순서는 보지 않는다
     * @param log              부르는 쪽 로거. 버린 건수를 부르는 쪽 이름으로 남긴다
     * @param what             로그에 쓸 이름({@code "추천"}·{@code "닮은 구간"})
     */
    public static <T> Outcome<T> apply(
        List<T> items,
        Function<T, String> externalIdOf,
        int limit,
        Function<List<String>, Map<String, MusicSectionAnalysis>> sectionsOf,
        Function<List<Integer>, List<MusicSummary>> summariesOf,
        Logger log,
        String what
    ) {
        if (items == null || items.isEmpty()) {
            return new Outcome<>(List.of(), 0, 0, 0, 0, 0, 0);
        }
        List<String> externalIds = items.stream()
            .map(externalIdOf).filter(Objects::nonNull).distinct().toList();
        Map<String, MusicSectionAnalysis> sections = sectionsOf.apply(externalIds);

        List<Integer> musicIds = sections.values().stream()
            .map(row -> row.getMusic().getMusicId()).distinct().toList();
        // 곡 표현은 **한 번에** 조립한다 — 곡마다 부르면 길이·장르·분위기 조회가 곡 수만큼 돈다.
        Map<Integer, MusicSummary> summaries = musicIds.isEmpty() ? Map.of()
            : summariesOf.apply(musicIds).stream()
                .collect(Collectors.toMap(MusicSummary::musicId, Function.identity(), (a, b) -> a));

        List<Kept<T>> kept = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        int missingSection = 0;
        int missingMusic = 0;
        int notPlayable = 0;
        int duplicate = 0;
        int cut = 0;
        for (T item : items) {
            String externalId = externalIdOf.apply(item);
            MusicSectionAnalysis section = externalId == null ? null : sections.get(externalId);
            if (section == null) {
                missingSection++;
                log.warn("{} external_id={} 가 DB 에 없다 — 구간 적재가 색인보다 낡았다", what, externalId);
                continue;
            }
            MusicSummary music = summaries.get(section.getMusic().getMusicId());
            if (music == null) {
                // 지운 곡이다. 사본(`track_features.is_deleted`)이 아직 모르는 틈이다.
                missingMusic++;
                continue;
            }
            if (!music.playable()) {
                // 완료된 스트림이 없으면 눌러도 소리가 나지 않는다 (S15P21A204-426).
                notPlayable++;
                continue;
            }
            if (!seen.add(music.musicId())) {
                duplicate++;
                continue;
            }
            if (kept.size() >= limit) {
                cut++;
                continue;
            }
            kept.add(new Kept<>(item, section, music));
        }

        Outcome<T> outcome = new Outcome<>(List.copyOf(kept), items.size(),
            missingSection, missingMusic, notPlayable, duplicate, cut);
        if (outcome.dropped() > 0) {
            // 곡이 0건이 된 경우를 **한 줄로 드러낸다.** 건별 경고만으로는 "전부 버려서 곡 0건인데
            // 정상 응답" 하는 상태가 눈에 띄지 않는다 — 사용자에게는 "추천이 안 온다" 로 보인다.
            log.warn("{} {}건 중 {}건을 버렸다 (구간 없음={} 곡 없음·삭제={} 재생 불가={} 같은 곡={})"
                + " — 남은 곡 {}건 (limit {}). 구간 적재가 색인보다 낡았는지, 스트림 변환이 밀렸는지 본다",
                what, outcome.received(), outcome.dropped(), missingSection, missingMusic,
                notPlayable, duplicate, kept.size(), limit);
        }
        return outcome;
    }

}
