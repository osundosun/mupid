package com.osundosun.mupid.api.ai.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.osundosun.mupid.api.ai.client.OpenAiTool;
import com.osundosun.mupid.api.ai.dto.AiRecommendedTrack;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.enumerate.SectionLabel;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.storage.service.StorageImageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ⚠ <b>목업이다.</b> 사용자가 무엇을 물었든 결과가 같다.
 *
 * 추천 점수도 취향도 보지 않는다. 카탈로그 앞쪽에서 몇 곡을 집어 정해진 사유를 붙일
 * 뿐이다. <b>다만 실제 곡 번호를 쓴다</b> — 없는 번호를 돌려주면 화면이 재생할 수 없는
 * 줄만 그리게 되고, 그러면 대화가 이어지는지 눈으로 확인할 수가 없다. 카탈로그가 비어
 * 있을 때만 고정 예시로 돌아간다.
 *
 * 진짜 추천은 별도 서버가 한다 (`ai_server/`). 붙일 때 바꾸는 것은 이 클래스 하나다 —
 * `recommend()` 안에서 아래를 호출하고, 응답 `items[]` 를 {@link AiRecommendedTrack} 으로
 * 옮긴 뒤 `musicId` 로 곡명·아티스트·표지를 채우면 된다. 필드 이름을 저쪽 계약에 맞춰 둔
 * 이유다.
 *
 * <pre>
 * POST /internal/recommendations/search
 *   { "recommendation_id": .., "user_id": .., "query": .., "limit": ..,
 *     "exclude_music_ids": [..] }
 * </pre>
 *
 * ⚠ `search` 는 자연어 해석기(`MUPID_AI_NLP_URL`)가 없으면 **501** 이다. 대화가 쓰는 경로가
 * 하필 그쪽이라, 붙일 때 해석기를 함께 세우거나 `/internal/recommendations/next`
 * (지금 듣는 곡 기준)로 시작할지 먼저 정해야 한다 — `ai_server/README.md`, S15P21A204-219.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiRecommendToolImpl implements AiRecommendTool {

    private static final int DEFAULT_LIMIT = 3;
    private static final int MAX_LIMIT = 5;

    /** 순위별 사유. 목업이라 곡과 무관하게 순서대로 붙는다. */
    private static final List<String> REASONS = List.of(
        "지금 곡보다 템포를 조금 낮추고 잔향을 길게 남긴 곡이에요.",
        "저장하신 순간과 결이 비슷해서, 분위기를 그대로 이어 갑니다.",
        "리듬은 그대로 두고 악기를 덜어 낸 편성이라 더 담백해요.",
        "평소 듣던 결과는 조금 다르지만, 여운이 긴 마무리가 어울릴 것 같아요.",
        "보컬이 앞에 나오는 곡이라 가사에 오래 머무르게 됩니다."
    );

    /**
     * 카탈로그가 비었을 때 쓰는 고정 예시. **곡 번호가 실제 카탈로그에 없어서 재생되지
     * 않는다.** 화면은 이런 곡을 목록에서 걸러 낸다 (`frontend/lib/ai-chat.ts`).
     */
    private static final List<AiRecommendedTrack> FIXTURES = List.of(
        new AiRecommendedTrack(
            1, 9001, "밤의 가장자리", "여름 산책", "긴 밤", null,
            9101, SectionLabel.CHORUS, 72_000, 96_000,
            0.91, "QUERY_SEMANTIC_MATCH", REASONS.get(0)
        ),
        new AiRecommendedTrack(
            2, 9002, "선유도 새벽", "모래시계", "새벽 세 시", null,
            9102, SectionLabel.BRIDGE, 118_000, 142_000,
            0.87, "SIMILAR_PREFERRED_MOMENT", REASONS.get(1)
        ),
        new AiRecommendedTrack(
            3, 9003, "Slow Tide", "Harbor Lights", "Low Water", null,
            9103, SectionLabel.VERSE, 24_000, 51_000,
            0.83, "QUERY_SEMANTIC_MATCH", REASONS.get(2)
        )
    );

    private final MusicRepository musicRepository;

    @Override
    public OpenAiTool declaration() {
        return OpenAiTool.function(
            NAME,
            """
            지금 듣고 있는 곡과 사용자의 취향을 바탕으로 다음에 들을 곡(구간)을 찾는다.
            곡·분위기·장르를 추천해 달라는 요청에는 반드시 이 툴을 먼저 부른다.
            결과에 없는 곡을 지어내지 않는다.
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
                    )
                ),
                "required", List.of("query")
            )
        );
    }

    @Override
    public List<AiRecommendedTrack> recommend(Integer userId, Integer currentMusicId, Arguments arguments) {
        int limit = DEFAULT_LIMIT;
        String query = null;
        if (arguments != null) {
            query = arguments.query();
            if (arguments.limit() != null) {
                limit = Math.clamp(arguments.limit(), 1, MAX_LIMIT);
            }
        }
        log.info("추천 툴 호출(목업) user_id={} current_music_id={} limit={} query={}",
            userId, currentMusicId, limit, query);

        // +1 은 지금 듣는 곡이 앞쪽에 섞여 들어와도 개수를 채우려는 것이다.
        // 연관을 함께 끌어오는 질의라 아티스트·앨범을 지연 로딩하지 않는다.
        List<Music> candidates = musicRepository
            .search(null, PageRequest.of(0, limit + 1))
            .getContent();

        List<AiRecommendedTrack> picked = new ArrayList<>();
        for (Music music : candidates) {
            if (picked.size() >= limit) {
                break;
            }
            // 듣고 있는 곡을 다시 권하지 않는다. 추천 서버에서는 exclude_music_ids 가 한다.
            if (currentMusicId != null && currentMusicId.equals(music.getMusicId())) {
                continue;
            }
            picked.add(toRecommended(music, picked.size() + 1));
        }
        if (!picked.isEmpty()) {
            return List.copyOf(picked);
        }

        log.warn("카탈로그에서 추천할 곡을 찾지 못해 고정 예시로 답한다 (재생되지 않는 곡이다)");
        return FIXTURES.stream().limit(limit).toList();
    }

    /**
     * 구간은 채우지 않는다. 목업이 임의로 정한 구간을 내보내면 화면이 곡의 엉뚱한
     * 지점을 "추천된 순간" 으로 표시한다. 추천 서버가 붙으면 그때 진짜 구간이 온다.
     */
    private AiRecommendedTrack toRecommended(Music music, int rank) {
        return new AiRecommendedTrack(
            rank,
            music.getMusicId(),
            music.getTitle(),
            music.getArtist().getName(),
            music.getAlbum().getTitle(),
            StorageImageService.imageUrl(music.getAlbum().getImageFile()),
            null,
            null,
            0,
            0,
            Math.max(0.5, 0.92 - (rank - 1) * 0.04),
            "COLD_START",
            REASONS.get((rank - 1) % REASONS.size())
        );
    }

}
