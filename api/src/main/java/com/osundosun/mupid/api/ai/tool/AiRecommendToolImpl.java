package com.osundosun.mupid.api.ai.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.osundosun.mupid.api.ai.client.OpenAiTool;
import com.osundosun.mupid.api.ai.client.RecommenderClient;
import com.osundosun.mupid.api.ai.client.RecommenderDto;
import com.osundosun.mupid.api.ai.dto.AiRecommendedTrack;
import com.osundosun.mupid.api.music.dto.MusicSummary;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.music.service.MusicSectionService;
import com.osundosun.mupid.api.music.service.MusicSummaryAssembler;
import com.osundosun.mupid.api.recommendation.service.RecommendationReasonCache;
import com.osundosun.mupid.api.onboarding.dto.CodeOption;
import com.osundosun.mupid.api.onboarding.response.OnboardingResultResponse;
import com.osundosun.mupid.api.onboarding.service.OnboardingService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 추천 서버에 실제로 물어본다.
 *
 * 대화 모델은 **곡을 고르지 않는다.** 사용자가 말한 조건을 카탈로그 어휘로 옮기는 일까지만
 * 한다. 어떤 곡의 어느 순간이 나올지는 추천 파이프라인이 정한다 — 모델에게 카탈로그를
 * 통째로 물릴 수도 없고, 물리면 없는 곡을 지어낸다.
 *
 * <h2>왜 자연어 원문을 보내지 않는가</h2>
 *
 * 추천 서버의 `search` 는 원문을 받으면 별도의 자연어 해석기(GPU 가 필요하다)를 부른다.
 * 그게 없으면 501 이다. 우리는 이미 대화 모델을 쓰고 있으므로, 모델에게 조건을 뽑게 해
 * `query_plan` 으로 보낸다. 해석기를 세우지 않아도 조건이 걸린다.
 *
 * 대신 CLAP 임베딩 기반의 의미 재정렬은 빠진다. 무드는 추천 서버가 태그 일치로 부드럽게
 * 재정렬해 그 자리를 메운다.
 *
 * <h2>조건이 없으면</h2>
 *
 * 모델이 조건을 하나도 못 뽑았으면 `search` 가 아니라 `next` 를 부른다. 조건 없는
 * `search` 는 400 이다 — "검색했는데 조건이 안 걸린 결과" 를 막으려고 추천 서버가 일부러
 * 거부한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiRecommendToolImpl implements AiRecommendTool {

    private static final int DEFAULT_LIMIT = 3;
    private static final int MAX_LIMIT = 5;

    /** 조건에 쓸 수 있는 칸. 추천 서버의 `/internal/facets` 가 정하는 이름이다. */
    private static final List<String> FILTER_FIELDS =
        List.of("genre", "language", "vocal", "instrument", "mood", "flow");

    private final RecommenderClient recommender;
    private final MusicRepository musicRepository;
    private final MusicSectionService musicSectionService;
    private final MusicSummaryAssembler musicSummaryAssembler;
    private final OnboardingService onboardingService;
    private final RecommendationReasonCache reasonCache;

    @Override
    public OpenAiTool declaration() {
        // 어휘는 **선언 하나당 한 번만** 받는다. conditionSchema 가 filters·exclude 두 번
        // 불리는데, 여기서 받아 두지 않으면 어휘를 못 받았을 때 대화마다 연결 타임아웃을
        // 두 번 기다린다 (실패는 캐시되지 않는다).
        RecommenderDto.Facets facets = facetsOrNull();
        return OpenAiTool.function(
            NAME,
            """
            지금 듣고 있는 곡과 사용자의 취향을 바탕으로 다음에 들을 곡(순간)을 찾는다.
            곡·분위기·장르를 추천해 달라는 요청에는 반드시 이 툴을 먼저 부른다.
            결과에 없는 곡을 지어내지 않는다.
            조건은 열거된 값 중에서만 고른다. 목록에 없는 말을 넣으면 결과가 0 이 된다.
            사용자가 말하지 않은 조건은 넣지 않는다 — 비워 두면 그 사람의 취향으로 고른다.
            """,
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "query", Map.of(
                        "type", "string",
                        "description", "사용자가 원하는 느낌을 한국어 한 문장으로. 예: '지금 곡보다 잔잔하고 여운이 긴 곡'"
                    ),
                    "limit", Map.of(
                        "type", "integer",
                        "description", "추천받을 곡 수. 1~5, 기본 3",
                        "minimum", 1,
                        "maximum", MAX_LIMIT
                    ),
                    "filters", conditionSchema(facets, "반드시 만족해야 하는 조건"),
                    "exclude", conditionSchema(facets, "빼야 하는 조건")
                ),
                "required", List.of("query")
            )
        );
    }

    /**
     * 태그 어휘를 받아 온다. **어떤 실패도 툴 선언을 막지 않는다.**
     *
     * <p>{@link RecommenderClient#facets()} 는 실패를 이미 {@code null} 로 뭉갠다. 그런데도
     * 여기서 한 번 더 막는 이유는, 대화의 **첫 문장**이 툴 선언이고 그 선언이 추천 서버에
     * 매달려 있기 때문이다. 저쪽 계약이 깨지거나(예: 새 실패 모드) 어휘 해석에서 예상 못 한
     * 예외가 나면 툴 선언을 만들지도 못하고 LLM 호출도 가지 않는다 — 사용자 눈에는 정확히
     * "도구 호출이 안 되는" 상태로 보인다. 어휘가 없으면 enum 없이 진행하면 된다.
     */
    private RecommenderDto.Facets facetsOrNull() {
        try {
            return recommender.facets();
        } catch (RuntimeException e) {
            log.warn("추천 서버 어휘 조회가 예외로 끝났다 — 조건 값 목록 없이 툴 선언을 만든다", e);
            return null;
        }
    }

    /**
     * 조건 칸의 JSON Schema. 값 목록은 카탈로그에서 받아 채운다.
     *
     * 어휘를 못 받으면 열거 없이 자유 문자열로 둔다. 그래도 조건이 조용히 사라지지는
     * 않는다 — 추천 서버가 어휘에 없는 조건을 "만족시킬 수 없음" 으로 보고 결과 0 을 준다.
     */
    private Map<String, Object> conditionSchema(RecommenderDto.Facets facets, String description) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String field : FILTER_FIELDS) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "string");
            List<String> values = allowedValues(facets, field);
            if (!values.isEmpty()) {
                item.put("enum", values);
            }
            properties.put(field, Map.of("type", "array", "items", item));
        }
        return Map.of("type", "object", "description", description, "properties", properties);
    }

    private List<String> allowedValues(RecommenderDto.Facets facets, String field) {
        if (facets == null || facets.fields() == null) {
            return List.of();
        }
        if (!(facets.fields().get(field) instanceof Map<?, ?> spec)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        // 장르 게이트는 genre 와 style 양쪽에 맞춰 보므로 두 어휘를 함께 노출한다.
        for (String key : List.of("values", "style_values")) {
            if (spec.get(key) instanceof List<?> list) {
                for (Object row : list) {
                    if (row instanceof Map<?, ?> entry && entry.get("value") != null) {
                        out.add(String.valueOf(entry.get("value")));
                    }
                }
            }
        }
        return out.stream().distinct().toList();
    }

    @Override
    public Result recommend(Integer userId, Integer currentMusicId, Arguments arguments) {
        int limit = DEFAULT_LIMIT;
        String query = null;
        Map<String, Object> filters = Map.of();
        Map<String, Object> exclude = Map.of();
        if (arguments != null) {
            query = arguments.query();
            if (arguments.limit() != null) {
                limit = Math.clamp(arguments.limit(), 1, MAX_LIMIT);
            }
            filters = clean(arguments.filters());
            exclude = clean(arguments.exclude());
        }

        // 추천 한 묶음을 가리키는 번호. 아직 노출 기록(rec_impressions)을 남기지 않아
        // 저장되지는 않는다. 그 작업이 붙으면 발급처를 그쪽으로 옮긴다.
        long recommendationId = System.currentTimeMillis();
        // 추천 서버는 숫자 ID 를 모른다. 지금 듣는 곡을 문자열 ID 로 옮겨 보낸다.
        String currentTrack = currentMusicId == null ? null
            : musicSectionService.trackExternalIdOf(currentMusicId).orElse(null);
        List<String> excludeTracks = currentTrack == null ? List.of() : List.of(currentTrack);
        RecommenderDto.Onboarding onboarding = onboardingOf(userId);
        boolean conditioned = !filters.isEmpty() || !exclude.isEmpty();

        log.info("추천 툴 호출 user_id={} current_music_id={} limit={} 조건={} query={}",
            userId, currentMusicId, limit, conditioned ? filters.keySet() : "없음", query);

        List<RecommenderDto.Item> items;
        if (conditioned) {
            Map<String, Object> plan = new LinkedHashMap<>();
            if (!filters.isEmpty()) {
                plan.put("filters", filters);
            }
            if (!exclude.isEmpty()) {
                plan.put("exclude", exclude);
            }
            // userId 는 Integer 다. 여기서 primitive 로 언박싱하면 null 에서 NPE 가 나고
            // 대화 한 번이 통째로 500 이 된다 (S15P21A204-377). null 이면 요청 본문에서
            // 빠지고 추천 서버가 "필수 칸 없음" 으로 400 을 낸다 — 계약 위반이 계약대로
            // 드러나는 편이, 우리 코드가 터지는 것보다 낫다.
            items = recommender.search(new RecommenderDto.SearchRequest(
                recommendationId, userId, limit, excludeTracks, null, plan, onboarding));
        } else {
            items = recommender.next(new RecommenderDto.NextRequest(
                recommendationId, userId, limit, excludeTracks, currentTrack, null,
                onboarding));
        }
        if (items.isEmpty()) {
            log.info("조건에 맞는 곡이 없다 user_id={} 조건={}", userId, filters);
            return Result.empty();
        }
        List<AiRecommendedTrack> tracks = withMusicDetails(items);
        // 사유 문장이 나가는 유일한 자리다. 재생 이벤트에는 실리지 않으므로 화면이
        // 되돌려줄 때까지 적어 둔다 ({@link RecommendationReasonCache}).
        for (AiRecommendedTrack track : tracks) {
            reasonCache.remember(recommendationId, track.musicId(), track.reason());
        }
        return new Result(recommendationId, tracks);
    }

    /**
     * 모델이 만든 조건에서 빈 칸을 걷어낸다.
     *
     * 모델은 조건이 없을 때도 `{"genre": []}` 같은 빈 껍데기를 곧잘 만든다. 그대로 보내면
     * "조건이 있다" 로 판정돼 `next` 대신 `search` 로 가고 결과가 달라진다.
     */
    private Map<String, Object> clean(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (!FILTER_FIELDS.contains(key)) {
                return;                                 // 추천 서버가 읽지 않는 칸이다
            }
            if (value instanceof List<?> list && !list.isEmpty()) {
                out.put(key, list.stream().map(String::valueOf).distinct().toList());
            } else if (value instanceof String s && !s.isBlank()) {
                out.put(key, List.of(s));               // 모델이 배열 대신 문자열을 줄 때가 있다
            }
        });
        return out;
    }

    /**
     * 이력이 없는 사용자의 첫 추천 근거. 없으면 콜드스타트다.
     *
     * 코드표 이름을 **그대로** 보낸다. AI 카탈로그 태그로 옮기는 것은 추천 서버 몫이고,
     * 두 어휘는 한 건도 겹치지 않아서 우리가 어설프게 옮기면 조건이 조용히 사라진다.
     *
     * 조회에 실패해도 추천을 죽이지 않는다 — 온보딩 없이 부른다.
     */
    private RecommenderDto.Onboarding onboardingOf(Integer userId) {
        if (userId == null) {
            return null;
        }
        try {
            OnboardingResultResponse r = onboardingService.getResult(userId);
            if (!r.submitted()) {
                return null;
            }
            return new RecommenderDto.Onboarding(
                r.moods().stream().map(CodeOption::name).toList(),
                r.genres().stream().map(CodeOption::name).toList());
        } catch (RuntimeException e) {
            log.warn("온보딩을 읽지 못했다 user_id={} — 콜드스타트로 추천한다", userId, e);
            return null;
        }
    }

    /**
     * 추천 서버가 돌려준 문자열 ID 를 곡 표현으로 옮긴다.
     *
     * 추천 서버는 `external_id` 와 순위·사유만 준다. 곡·구간 번호·시간·구간 종류는
     * **전부 여기서** 나온다. 어차피 제목을 붙이려고 하던 조회라, 조회 조건만
     * `external_id` 로 바뀐 것이다.
     *
     * <p><b>곡은 {@link MusicSummary} 로 통째로 싣는다</b> (S15P21A204-424). 예전에는
     * 제목·아티스트·앨범·표지만 납작하게 실었는데, 그것만으로는 화면이 줄을 그려도
     * 재생할 수가 없어서 화면이 자기 카탈로그에서 같은 곡을 다시 찾아 채웠다. 그
     * 카탈로그가 앞쪽 100곡뿐이라 추천이 대부분 버려졌다 — 자세한 것은
     * {@link AiRecommendedTrack} 주석에 있다. 조립은 목록·상세와 같은
     * {@link MusicSummaryAssembler} 가 한다. 두 벌이 되면 같은 곡이 화면마다 다르게 보인다.
     *
     * MySQL 에 없는 `external_id` 는 **버린다.** 화면이 재생할 수 없는 줄을 그리게 되기
     * 때문이다. 그런 일이 생겼다면 구간 적재가 색인보다 낡았다는 뜻이라 로그에 남긴다.
     */
    private List<AiRecommendedTrack> withMusicDetails(List<RecommenderDto.Item> items) {
        List<String> externalIds = items.stream()
            .map(RecommenderDto.Item::externalId).filter(Objects::nonNull).distinct().toList();
        Map<String, MusicSectionAnalysis> sections =
            musicSectionService.findByExternalIds(externalIds);

        List<Integer> ids = sections.values().stream()
            .map(row -> row.getMusic().getMusicId()).distinct().toList();
        Map<Integer, Music> byId = musicRepository.findDetailAllByIds(ids).stream()
            .collect(Collectors.toMap(Music::getMusicId, m -> m, (a, b) -> a));

        // 남길 것을 먼저 고른다. 곡 표현은 **한 번에** 조립한다 — 곡마다 부르면
        // 길이·장르·분위기 조회가 곡 수만큼 반복돼 N+1 이 된다.
        List<RecommenderDto.Item> kept = new ArrayList<>();
        List<MusicSectionAnalysis> keptSections = new ArrayList<>();
        List<Music> keptMusics = new ArrayList<>();
        // 버린 건수를 센다. 건별 경고만으로는 "전부 버려서 곡 0건인데 대화는 정상 응답" 하는
        // 상태가 눈에 띄지 않는다 — 사용자에게는 "추천이 안 온다" 로 보인다.
        int missingSection = 0;
        int missingMusic = 0;
        for (RecommenderDto.Item item : items) {
            MusicSectionAnalysis section = sections.get(item.externalId());
            if (section == null) {
                missingSection++;
                log.warn("추천된 external_id={} 가 DB 에 없다 — 구간 적재가 색인보다 낡았다",
                    item.externalId());
                continue;
            }
            Music music = byId.get(section.getMusic().getMusicId());
            if (music == null) {
                missingMusic++;
                log.warn("구간 external_id={} 의 곡을 찾지 못했다", item.externalId());
                continue;
            }
            kept.add(item);
            keptSections.add(section);
            keptMusics.add(music);
        }

        // 넘긴 순서 그대로 돌려준다 (`MusicSummaryAssembler.assemble`). 자리로 맞물리므로
        // 길이가 어긋나면 곡과 구간이 뒤바뀐다 — 어긋나면 그 자리에서 멈춘다.
        List<MusicSummary> summaries = musicSummaryAssembler.assemble(keptMusics);
        if (summaries.size() != keptMusics.size()) {
            throw new IllegalStateException(
                "곡 표현 조립 결과가 넘긴 곡 수와 다르다 보낸=%d 받은=%d"
                    .formatted(keptMusics.size(), summaries.size()));
        }

        List<AiRecommendedTrack> out = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            RecommenderDto.Item item = kept.get(i);
            MusicSectionAnalysis section = keptSections.get(i);
            out.add(new AiRecommendedTrack(
                out.size() + 1,                          // 버린 것이 있어도 순번은 이어 붙인다
                summaries.get(i),
                section.getMusicSectionAnalysisId(),
                section.getSectionLabel(),
                section.getStartTimeMs(),
                section.getEndTimeMs(),
                item.score(),
                item.reasonCode(),
                RecommendReason.of(item.reasonCode(), section.getSectionLabel())
            ));
        }
        int dropped = missingSection + missingMusic;
        if (dropped > 0) {
            // 곡이 0건이 된 경우는 **한 줄로 드러낸다.** 조용히 빈 목록을 돌려주면 원인을
            // 찾을 단서가 사라진다. 원인은 보통 구간 적재가 색인보다 낡은 것이다.
            log.warn("추천 {}건 중 {}건을 DB 에서 찾지 못해 버렸다 (구간 없음={} 곡 없음={})"
                + " — 남은 곡 {}건. 구간 적재가 색인보다 낡았는지 본다",
                items.size(), dropped, missingSection, missingMusic, out.size());
        }
        return List.copyOf(out);
    }
}
