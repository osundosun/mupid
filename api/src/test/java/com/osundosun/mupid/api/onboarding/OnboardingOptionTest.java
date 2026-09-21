package com.osundosun.mupid.api.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * 무드 17종의 `code_name` → `display_name`. `BootstrapRunner` 와 같은 값.
     *
     * <p>코드 이름은 영문 내부 코드, 표시명은 한국어다 (2026-09-21 정리). 앞 8종이
     * 대분류, 뒤 9종이 taxonomy 세분류다.
     */
    private static final List<String[]> MOODS = List.of(
        new String[] {"serene", "잔잔한"},
        new String[] {"uplifting", "벅찬"},
        new String[] {"ethereal", "몽환적인"},
        new String[] {"energetic", "신나는"},
        new String[] {"focused", "집중되는"},
        new String[] {"emotional", "감성적인"},
        new String[] {"intense", "강렬한"},
        new String[] {"romantic", "설레는"},
        new String[] {"melancholic", "애상적인"},
        new String[] {"warm", "따뜻한"},
        new String[] {"dreamy", "몽글한"},
        new String[] {"bright", "화사한"},
        new String[] {"hopeful", "희망찬"},
        new String[] {"powerful", "웅장한"},
        new String[] {"dark", "어두운"},
        new String[] {"calm", "고요한"},
        new String[] {"exciting", "짜릿한"}
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
    @DisplayName("분위기 17종이 모두 내려오고, 코드 이름은 영문·표시명은 한국어다")
    void moodsExposeEnglishCodeAndKoreanDisplayName() {
        List<CodeOption> moods = onboardingService.getOptions().moods();

        // 17종 이상이어야 한다. 배치가 코드를 더 넣었으면 더 많을 수 있다.
        assertTrue(moods.size() >= 17, "분위기 선택지가 17종보다 적다: " + moods.size());

        for (String[] pair : MOODS) {
            String codeName = pair[0];
            String korean = pair[1];

            CodeOption found = moods.stream()
                .filter(option -> codeName.equals(option.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("분위기 코드가 없다: " + codeName));

            // `name` 은 영문 내부 코드다 — AI 서버로 가는 값이라 바꾸면 안 된다.
            assertEquals(codeName, found.name(), "code_name 이 바뀌었다");
            // `display_name` 이 화면용 한국어다.
            assertEquals(korean, found.displayName(),
                codeName + " 의 표시명이 한국어여야 한다");
        }
        // 한국어가 `name` 으로 들어가면 같은 무드가 두 번호로 갈라진다 (운영에서 겪었다).
        Set<String> koreanNames = MOODS.stream().map(pair -> pair[1]).collect(Collectors.toSet());
        Set<String> names = moods.stream().map(CodeOption::name).collect(Collectors.toSet());
        assertEquals(Set.of(), names.stream().filter(koreanNames::contains)
            .collect(Collectors.toSet()), "한국어가 코드 이름으로 들어갔다");
    }

    @Test
    @DisplayName("분위기 표시명은 화면에서 겹치지 않는다 — 같은 한국어가 두 번호로 뜨면 안 된다")
    void moodDisplayNamesAreDistinct() {
        List<CodeOption> moods = onboardingService.getOptions().moods();

        Set<String> shown = moods.stream().map(CodeOption::displayName).collect(Collectors.toSet());
        assertEquals(moods.size(), shown.size(), "겹치는 표시명이 있다: " + moods);
    }

    @Test
    @DisplayName("장르도 코드 이름은 영문, 표시명은 한국어다 — 목록에 두 언어가 섞이면 안 된다")
    void genresShowKoreanDisplayNames() {
        /* 2026-09-21 정리 전에는 대분류 8종의 표시명이 비어 있어 화면이 `code_name`
           으로 되돌아갔고, 세분류 45종만 한국어라 온보딩 목록에 영문과 한국어가
           섞여 보였다. 대분류 8종은 `Electronic` → `일렉트로닉` 처럼 표시명을 채웠다.
           `code_name` 은 그대로다 — AI 서버가 그 철자로 세분류를 잇는다. */
        List<CodeOption> genres = onboardingService.getOptions().genres();

        assertTrue(genres.size() >= 8, "장르 선택지가 8종보다 적다: " + genres.size());
        for (CodeOption option : genres) {
            assertNotNull(option.displayName(), "표시명이 없다: " + option.name());
            assertTrue(!option.displayName().isBlank(),
                "표시명이 비어 화면이 영문으로 되돌아간다: " + option.name());
        }
        // 코드 이름에는 한국어가 없어야 한다 — AI 색인으로 가는 값이다.
        for (CodeOption option : genres) {
            assertFalse(option.name().matches(".*[가-힣].*"),
                "장르 코드 이름에 한국어가 들어갔다: " + option.name());
        }
    }

    @Test
    @DisplayName("장르 표시명은 겹치지 않는다 — 같은 글자가 두 칩으로 뜨면 고를 수 없다")
    void genreDisplayNamesAreDistinct() {
        /* `R&B`(대분류) 와 `rnb`(세분류) 의 표시명이 둘 다 `R&B` 라 칩이 둘이었다.
           대분류 쪽을 `알앤비` 로 갈라 두었다 (2026-09-21). */
        List<CodeOption> genres = onboardingService.getOptions().genres();

        Set<String> shown = genres.stream().map(CodeOption::displayName)
            .collect(Collectors.toSet());
        assertEquals(genres.size(), shown.size(), "겹치는 장르 표시명이 있다");
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
        assertEquals(MOODS.stream().map(pair -> pair[0]).toList(),
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
