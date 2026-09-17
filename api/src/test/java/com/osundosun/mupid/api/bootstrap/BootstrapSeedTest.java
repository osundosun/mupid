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

import com.osundosun.mupid.api.report.entity.Mubti;
import com.osundosun.mupid.api.report.enumerate.MubtiClass;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.storage.service.StorageImageService;

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

    @Test
    @DisplayName("취향 유형 이미지의 저장 키가 조회 URL 로 이어진다")
    void mubtiImageKeysRoundTrip() {
        // 저장 키를 만드는 곳은 `StorageImageService` 하나이고, 조회 URL 도 그 모양을
        // 되짚어 만들어진다. 이 둘이 갈라지면 `imageUrl` 이 예외도 로그도 없이 null 이
        // 되고 화면은 조용히 캐릭터를 못 그린다 — 실제로 그렇게 캐릭터가 뜨지 않았다.
        // 그래서 여기서 16종 전부 왕복시켜 본다.
        for (BootstrapRunner.MubtiSeed seed : BootstrapRunner.MUBTI_SEEDS) {
            String lookup = BootstrapRunner.mubtiImageKey(seed.code());

            String storageKey = StorageImageService.storageKey(FileCategory.MUBTI, lookup);
            assertThat(storageKey).as("%s 저장 키", seed.code())
                .isEqualTo("image/mubti/" + lookup);

            assertThat(StorageImageService.imageUrl(fileAtKey(storageKey)))
                .as("%s 조회 URL", seed.code())
                .isEqualTo("/api/v1/storage/image/MUBTI/" + lookup);
        }
    }

    @Test
    @DisplayName("접두사를 뺀 옛 키는 조회 URL 이 되지 않는다 — 캐릭터가 안 뜨던 그 모양이다")
    void legacyKeyWithoutCategoryPrefixYieldsNoUrl() {
        // 고치는 자리는 `imageUrl` 이 아니라 키를 만드는 쪽이다. 여기서 너그럽게 받아 주면
        // 규약이 둘로 갈라진 채 남고, 어느 쪽이 맞는지 아무도 모르게 된다.
        assertThat(StorageImageService.imageUrl(fileAtKey("mubti/VTFC.webp"))).isNull();
    }

    @Test
    @DisplayName("지워진 파일과 키가 어긋난 행은 주소를 만들지 않는다")
    void deletedOrMalformedFilesHaveNoUrl() {
        ObjectStorageFile deleted = fileAtKey("image/mubti/VTFC.webp");
        deleted.setIsDeleted(true);
        assertThat(StorageImageService.imageUrl(deleted)).isNull();

        assertThat(StorageImageService.imageUrl(fileAtKey("VTFC.webp"))).isNull();
        assertThat(StorageImageService.imageUrl(null)).isNull();
    }

    private static ObjectStorageFile fileAtKey(String storageKey) {
        return ObjectStorageFile.builder()
            .fileType(FileType.IMG)
            .category(FileCategory.MUBTI)
            .originalFileName(storageKey)
            .storageObjectKey(storageKey)
            .fileSizeBytes(1)
            .mime("image/webp")
            .build();
    }

    /**
     * 네 축이 16종 밖인 행은 코드를 만들지 않는다.
     *
     * <p>수리는 축 조합을 코드로 봐서 하는데, 그 판정이 너그러우면 시드가 만든 적 없는 행을
     * 건드리게 된다.
     */
    @Test
    @DisplayName("16종 밖의 조합은 시드 코드로 보지 않는다")
    void unknownCombinationsHaveNoSeedCode() {
        assertThat(BootstrapRunner.mubtiSeedCode(Mubti.builder()
            .classA(null).classB(MubtiClass.B.T).classC(MubtiClass.C.F).classD(MubtiClass.D.C)
            .title("t").subtitle("s").description("d").build())).isNull();

        assertThat(BootstrapRunner.mubtiSeedCode(Mubti.builder()
            .classA(MubtiClass.A.V).classB(MubtiClass.B.T)
            .classC(MubtiClass.C.F).classD(MubtiClass.D.C)
            .title("t").subtitle("s").description("d").build())).isEqualTo("VTFC");
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
