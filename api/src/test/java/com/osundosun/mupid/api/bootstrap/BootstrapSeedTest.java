package com.osundosun.mupid.api.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 부트스트랩이 넣을 값이 **DB 에 들어갈 수 있는 모양인지**만 본다.
 * 스프링 컨텍스트도 DB 도 필요 없다.
 *
 * <p>여기서 잡는 것은 전부 기동 시점에야 드러나는 것들이다 — 이미지가 빠지면 그 유형만
 * 조용히 안 들어가고, 문구가 길면 INSERT 가 잘려 터진다. 둘 다 눈으로 보기 어렵다.
 */
class BootstrapSeedTest {

    /** `mubti.title` · `mubti.subtitle` 은 varchar(20) 이다. */
    private static final int MUBTI_NAME_LIMIT = 20;

    /** `mubti.description` 은 varchar(150) 이다. */
    private static final int MUBTI_DESCRIPTION_LIMIT = 150;

    @Test
    @DisplayName("취향 유형은 네 축의 조합 16종을 하나도 빠짐없이 덮는다")
    void mubtiCoversEveryCombination() {
        Set<String> codes = new HashSet<>();
        BootstrapRunner.MUBTI_SEEDS.forEach(seed -> codes.add(seed.code()));

        Set<String> expected = new HashSet<>();
        for (char a : new char[] {'V', 'I'}) {
            for (char b : new char[] {'T', 'M'}) {
                for (char c : new char[] {'F', 'S'}) {
                    for (char d : new char[] {'C', 'E'}) {
                        expected.add("" + a + b + c + d);
                    }
                }
            }
        }

        assertThat(codes).isEqualTo(expected);
    }

    @Test
    @DisplayName("유형 이름은 서로 달라야 한다 — title 에 UNIQUE 가 걸려 있다")
    void mubtiTitlesAreUnique() {
        Set<String> titles = new HashSet<>();
        BootstrapRunner.MUBTI_SEEDS.forEach(seed -> titles.add(seed.title()));

        assertThat(titles).hasSize(BootstrapRunner.MUBTI_SEEDS.size());
    }

    @Test
    @DisplayName("유형 문구가 컬럼 길이를 넘지 않는다")
    void mubtiTextFitsColumns() {
        for (BootstrapRunner.MubtiSeed seed : BootstrapRunner.MUBTI_SEEDS) {
            assertThat(seed.title()).as("%s title", seed.code())
                .hasSizeLessThanOrEqualTo(MUBTI_NAME_LIMIT);
            assertThat(seed.subtitle()).as("%s subtitle", seed.code())
                .hasSizeLessThanOrEqualTo(MUBTI_NAME_LIMIT);
            assertThat(seed.description()).as("%s description", seed.code())
                .hasSizeLessThanOrEqualTo(MUBTI_DESCRIPTION_LIMIT);
        }
    }

    @Test
    @DisplayName("유형마다 캐릭터 이미지가 classpath zip 에 들어 있다")
    void mubtiImagesArePackaged() throws Exception {
        Set<String> packaged = new HashSet<>();
        ClassPathResource zip = new ClassPathResource(BootstrapRunner.MUBTI_IMAGE_ZIP);
        try (InputStream raw = zip.getInputStream(); ZipInputStream entries = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = entries.getNextEntry()) != null) {
                String code = BootstrapRunner.codeOf(entry);
                if (code != null) {
                    packaged.add(code);
                }
            }
        }

        Set<String> wanted = new HashSet<>();
        BootstrapRunner.MUBTI_SEEDS.forEach(seed -> wanted.add(seed.code()));

        // 남는 이미지도 걸러 낸다 — 코드를 지웠는데 파일만 남은 것도 어긋난 상태다.
        assertThat(packaged).isEqualTo(wanted);
    }

    @Test
    @DisplayName("공통 코드는 같은 용도 안에서 이름이 겹치지 않는다")
    void codeSeedsAreDistinct() {
        Set<BootstrapRunner.CodeSeed> distinct = new HashSet<>(BootstrapRunner.CODE_SEEDS);

        assertThat(distinct).hasSize(BootstrapRunner.CODE_SEEDS.size());
    }

    @Test
    @DisplayName("깨져 들어간 한글을 전부 찾아 원래 값을 되살린다")
    void detectsDoubleEncodedKorean() {
        /* 운영 DB 에서 실제로 나온 값. 눈에 안 보이는 글자(NBSP·C1)가 섞여 있어
           소스에 붙여 넣지 않고 같은 규칙으로 만들어 쓴다. */
        for (String name : List.of("잔잔한", "벅찬", "몽환적인", "신나는",
                                   "집중되는", "감성적인", "강렬한", "설레는")) {
            assertThat(BootstrapRunner.recoverDoubleEncoded(garble(name)))
                .as(name).isEqualTo(name);
        }
    }

    @Test
    @DisplayName("멀쩡한 이름은 깨진 것으로 보지 않는다")
    void leavesHealthyNamesAlone() {
        for (String name : List.of("잔잔한", "몽환적인", "집중되는", "감성적인",
                                   "R&B", "Hip-hop", "Electronic", "Jazz")) {
            assertThat(BootstrapRunner.recoverDoubleEncoded(name)).as(name).isNull();
        }
        assertThat(BootstrapRunner.recoverDoubleEncoded(null)).isNull();
        assertThat(BootstrapRunner.recoverDoubleEncoded("  ")).isNull();
    }

    @Test
    @DisplayName("시드 목록 자체에는 깨진 이름이 없다")
    void seedsAreNotGarbled() {
        BootstrapRunner.CODE_SEEDS.forEach(seed ->
            assertThat(BootstrapRunner.recoverDoubleEncoded(seed.name())).as(seed.name()).isNull());
        BootstrapRunner.MUBTI_SEEDS.forEach(seed -> {
            assertThat(BootstrapRunner.recoverDoubleEncoded(seed.title())).as(seed.title()).isNull();
            assertThat(BootstrapRunner.recoverDoubleEncoded(seed.subtitle())).as(seed.subtitle()).isNull();
        });
    }

    /**
     * 시드 SQL 을 {@code --default-character-set=utf8mb4} 없이 부었을 때 일어나는 일.
     *
     * <p>UTF-8 바이트를 MySQL {@code latin1}(= cp1252 + 정의 없는 다섯 칸은 그대로)
     * 로 읽는다. 오류가 나지 않아 조용히 들어간다.
     */
    private static String garble(String korean) {
        byte[] utf8 = korean.getBytes(StandardCharsets.UTF_8);
        Charset cp1252 = Charset.forName("windows-1252");
        StringBuilder out = new StringBuilder(utf8.length);
        for (byte b : utf8) {
            String decoded = new String(new byte[] {b}, cp1252);
            out.append(decoded.length() == 1 && decoded.charAt(0) != '�'
                ? decoded.charAt(0)
                : (char) (b & 0xFF));
        }
        return out.toString();
    }
}
