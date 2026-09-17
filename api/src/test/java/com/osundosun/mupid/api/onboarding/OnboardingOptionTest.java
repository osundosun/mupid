package com.osundosun.mupid.api.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.code.enumerate.CodeType;
import com.osundosun.mupid.api.onboarding.dto.CodeOption;
import com.osundosun.mupid.api.onboarding.response.OnboardingOptionResponse;
import com.osundosun.mupid.api.onboarding.service.OnboardingService;

/**
 * 온보딩 선택지(`GET /api/v1/onboarding/option`)가 **화면과 AI 양쪽에 쓸 수 있는
 * 모양인지** 본다.
 *
 * <p>핵심은 `CodeOption` 이 값 두 개를 갈라 싣는 것이다 — `name` 은 AI 로 넘길
 * 색인 값, `displayName` 은 화면에 그릴 이름. 하나로 합치면 taxonomy 무드처럼
 * 둘이 다른 종류에서 **오류 없이 조용히** 한쪽이 깨진다(화면에 영문이 뜨거나,
 * 색인에 한국어가 가서 씨앗이 0 이 되거나).
 *
 * <p>로컬 MySQL 이 떠 있어야 하고 트랜잭션은 테스트마다 롤백된다.
 *
 * <p><b>환경 전제가 하나 더 있다.</b> `codes.display_name` 컬럼과 무드 17종이
 * 실제 DB 에 있어야 한다 — `ddl-auto: none` 이라 앱이 만들지 않는다
 * (`knowledge/decision/0011`). 없는 상태로 돌리면 이 클래스가 먼저 실패한다.
 *
 * <pre>
 *   ALTER TABLE `codes` ADD COLUMN `display_name` VARCHAR(200) NOT NULL AFTER `code_name`;
 *   UPDATE `codes` SET `display_name` = `code_name` WHERE `display_name` = '';
 * </pre>
 *
 * 그리고 `knowledge/domain/seed-codes.sql` 을 적용하거나 앱을 한 번 띄운다
 * (기동 때 `BootstrapRunner` 가 빠진 코드를 채운다). 컬럼이 없으면 컨텍스트가
 * 못 뜨므로 **다른 `@SpringBootTest` 도 같이 죽는다** — 그때는 스키마를 먼저 본다.
 */
@SpringBootTest
@Transactional
class OnboardingOptionTest {

    /** taxonomy 무드 9종의 `code_name` → `display_name`. `BootstrapRunner` 와 같은 값. */
    private static final List<String[]> TAXONOMY_MOODS = List.of(
        new String[] {"애상적인", "melancholic"},
        new String[] {"따뜻한", "warm"},
        new String[] {"몽글한", "dreamy"},
        new String[] {"화사한", "bright"},
        new String[] {"희망찬", "hopeful"},
        new String[] {"웅장한", "powerful"},
        new String[] {"어두운", "dark"},
        new String[] {"고요한", "calm"},
        new String[] {"짜릿한", "exciting"}
    );

    @Autowired
    private OnboardingService onboardingService;

    @Test
    @DisplayName("선택지는 화면용 이름과 AI 전달용 이름을 함께 싣는다")
    void everyOptionCarriesBothNames() {
        OnboardingOptionResponse options = onboardingService.getOptions();

        for (CodeOption option : options.moods()) {
            assertNotNull(option.name(), "AI 전달용 이름이 비었다: " + option.codeId());
            assertNotNull(option.displayName(), "화면용 이름이 비었다: " + option.codeId());
            assertTrue(!option.displayName().isBlank(),
                "화면에 빈 칸이 그려진다: " + option.codeId());
        }
        for (CodeOption option : options.genres()) {
            assertNotNull(option.name());
            assertNotNull(option.displayName());
        }
    }

    @Test
    @DisplayName("분위기 17종이 모두 내려오고, taxonomy 9종은 두 이름이 다르다")
    void taxonomyMoodsExposeBothLanguages() {
        List<CodeOption> moods = onboardingService.getOptions().moods();

        // 17종 이상이어야 한다. 배치가 코드를 더 넣었으면 더 많을 수 있다.
        assertTrue(moods.size() >= 17, "분위기 선택지가 17종보다 적다: " + moods.size());

        Set<String> names = moods.stream().map(CodeOption::name).collect(Collectors.toSet());
        for (String[] pair : TAXONOMY_MOODS) {
            String codeName = pair[0];
            String indexName = pair[1];

            CodeOption found = moods.stream()
                .filter(option -> codeName.equals(option.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("분위기 코드가 없다: " + codeName));

            // `name` 은 한국어 그대로다 — 색인으로 가는 값이라 바꾸면 안 된다.
            assertEquals(codeName, found.name(), "code_name 이 바뀌었다");
            // `display_name` 이 taxonomy 원문이다 — 장르 45종과 **방향이 반대**다.
            assertEquals(indexName, found.displayName(),
                codeName + " 의 표시명이 taxonomy 원문이어야 한다");
        }
        // taxonomy 원문이 `name` 으로 새어 나가면 화면에 영문이 뜬다.
        Set<String> indexNames = TAXONOMY_MOODS.stream()
            .map(pair -> pair[1]).collect(Collectors.toSet());
        assertEquals(Set.of(), names.stream().filter(indexNames::contains)
            .collect(Collectors.toSet()), "taxonomy 원문이 코드 이름으로 들어갔다");
    }

    @Test
    @DisplayName("기존 8종 분위기는 표시명이 코드 이름과 같다 — 기존 사용자 화면이 안 바뀐다")
    void theOriginalEightKeepTheirDisplayName() {
        List<CodeOption> moods = onboardingService.getOptions().moods();
        List<String> original = List.of("잔잔한", "벅찬", "몽환적인", "신나는",
                                        "집중되는", "감성적인", "강렬한", "설레는");

        for (String name : original) {
            CodeOption found = moods.stream()
                .filter(option -> name.equals(option.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("기존 분위기 코드가 없다: " + name));
            assertEquals(name, found.displayName(), name + " 의 표시명이 바뀌었다");
        }
    }

    @Test
    @DisplayName("장르는 표시명이 코드 이름과 같다 — 표시명이 아직 한국어가 아니다")
    void genresKeepCodeNameAsDisplayName() {
        List<CodeOption> genres = onboardingService.getOptions().genres();

        assertTrue(genres.size() >= 8, "장르 선택지가 8종보다 적다: " + genres.size());
        for (CodeOption option : genres) {
            assertEquals(option.name(), option.displayName(),
                "장르의 표시명이 코드 이름과 다르다: " + option.name());
        }
    }

    @Test
    @DisplayName("표시명이 컬럼 길이를 넘지 않는다 — INSERT 가 조용히 잘리면 안 된다")
    void displayNamesFitTheColumn() {
        OnboardingOptionResponse options = onboardingService.getOptions();

        for (CodeOption option : concat(options)) {
            assertTrue(option.displayName().length() <= 200,
                "표시명이 200자를 넘는다: " + option.codeId());
            assertTrue(option.name().length() <= 50,
                "코드 이름이 50자를 넘는다: " + option.codeId());
        }
    }

    @Test
    @DisplayName("선택지는 코드표 그대로다 — 화면이 하드코딩한 목록과 어긋나면 안 된다")
    void optionsComeFromTheCodeTable() {
        OnboardingOptionResponse options = onboardingService.getOptions();

        // 무드는 대분류 8 + taxonomy 9 로 시작한다.
        List<String> moods = options.moods().stream().map(CodeOption::name).toList();
        assertEquals(List.of("잔잔한", "벅찬", "몽환적인", "신나는", "집중되는",
                             "감성적인", "강렬한", "설레는",
                             "애상적인", "따뜻한", "몽글한", "화사한", "희망찬",
                             "웅장한", "어두운", "고요한", "짜릿한"),
            moods.subList(0, 17), "분위기 차례가 바뀌었다 — 화면 차례가 곧 code_id 차례다");

        // 장르 8종도 차례가 고정이다.
        List<String> genres = options.genres().stream().map(CodeOption::name).toList();
        assertEquals(List.of("Electronic", "Indie", "R&B", "Rock", "Pop", "Hip-hop",
                             "Jazz", "Classical"),
            genres.subList(0, 8), "장르 차례가 바뀌었다");
    }

    private List<CodeOption> concat(OnboardingOptionResponse options) {
        return java.util.stream.Stream.concat(options.moods().stream(), options.genres().stream())
            .toList();
    }
}
