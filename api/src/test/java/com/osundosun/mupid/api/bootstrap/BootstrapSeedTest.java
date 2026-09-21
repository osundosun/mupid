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

import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.code.enumerate.CodeType;
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
    @DisplayName("공통 코드의 표시명이 비어 있지 않다 — 화면이 빈 칸을 그리면 안 된다")
    void codeSeedDisplayNamesArePresent() {
        /* `codes.display_name` 은 NOT NULL 이고 최대 200자다.
           `BootstrapRunner` 가 이 값으로 INSERT 하므로 기동 시점에 터진다. */
        BootstrapRunner.CODE_SEEDS.forEach(seed -> {
            assertThat(seed.displayName()).as("%s 표시명", seed.name()).isNotBlank();
            assertThat(seed.displayName()).as("%s 표시명 길이", seed.name())
                .hasSizeLessThanOrEqualTo(200);
            assertThat(seed.name()).as("%s 이름 길이", seed.name())
                .hasSizeLessThanOrEqualTo(50);
        });
    }

    @Test
    @DisplayName("분위기 17종은 대분류 8종을 앞에 그대로 두고 taxonomy 9종을 뒤에 붙인다")
    void moodSeedsKeepTheOriginalEightFirst() {
        /* 화면 차례가 곧 `code_id` 차례다(`findAllByCodeTypeOrderByCodeIdAsc`).
           기존 8종의 차례와 이름은 **바뀌면 안 된다** — 이미 저장된
           `onboarding_survey` 가 번호로 답을 갖고 있어서, 차례가 밀리면
           기존 사용자의 답이 다른 분위기로 읽힌다. */
        List<String> moods = BootstrapRunner.CODE_SEEDS.stream()
            .filter(seed -> seed.type() == CodeType.MOOD)
            .map(BootstrapRunner.CodeSeed::name)
            .toList();

        assertThat(moods).hasSize(17);
        assertThat(moods.subList(0, 8)).containsExactly(
            "serene", "uplifting", "ethereal", "energetic", "focused", "emotional", "intense", "romantic");
        assertThat(moods.subList(8, 17)).containsExactly(
            "melancholic", "warm", "dreamy", "bright", "hopeful", "powerful", "dark", "calm", "exciting");
    }

    @Test
    @DisplayName("분위기는 코드 이름이 영문 내부 코드이고 표시명이 한국어다 — 운영 DB 정리(2026-09-21)와 같은 방향")
    void moodSeedsUseEnglishCodeNamesAndKoreanDisplayNames() {
        /* 운영에서 같은 무드가 한국어·영문 두 이름으로 들어가 매핑 테이블마다 다른
           번호를 쓰던 것을 영문 한 벌로 합쳤다. 시드가 한국어 이름으로 돌아가면
           기동 때 그 이름이 없다고 보고 **중복 행을 다시 만든다.** */
        List<BootstrapRunner.CodeSeed> moods = BootstrapRunner.CODE_SEEDS.stream()
            .filter(seed -> seed.type() == CodeType.MOOD)
            .toList();

        for (BootstrapRunner.CodeSeed seed : moods) {
            assertThat(seed.name()).as("%s 코드 이름", seed.name()).matches("[a-z]+");
            assertThat(seed.displayName()).as("%s 표시명", seed.name()).matches("[가-힣]+");
        }
        assertThat(moods.stream().map(BootstrapRunner.CodeSeed::displayName).distinct().count())
            .as("화면에 같은 한국어가 두 번 뜨면 안 된다").isEqualTo(moods.size());
        assertThat(moods).extracting(BootstrapRunner.CodeSeed::displayName).containsExactly(
            "잔잔한", "벅찬", "몽환적인", "신나는", "집중되는", "감성적인", "강렬한", "설레는",
            "애상적인", "따뜻한", "몽글한", "화사한", "희망찬", "웅장한", "어두운", "고요한", "짜릿한");
    }

    @Test
    @DisplayName("이미 있는 코드는 이름이든 표시명이든 겹치면 다시 넣지 않는다")
    void seedSkipsCodesAlreadyThereUnderEitherName() {
        /* 2026-09-21 운영 사고의 재현. 코드표를 영문 이름으로 정리한 뒤, 아직
           배포되지 않은 옛 판이 한국어 이름으로 시드를 시도했다. 이름만 보던
           판정은 '없다' 고 보고 17행을 새로 넣었다 — 두 번(98~106, 107~123). */
        List<Code> table = List.of(
            code(CodeType.MOOD, "melancholic", "애상적인"),
            code(CodeType.MOOD, "serene", "잔잔한"));
        Set<BootstrapRunner.CodeSeed> existing = BootstrapRunner.existingNames(table);

        // 옛 판의 시드 — 이름이 한국어이고 표시명이 영문이다.
        assertThat(BootstrapRunner.alreadyThere(existing,
            new BootstrapRunner.CodeSeed(CodeType.MOOD, "애상적인", "melancholic")))
            .as("표시명이 겹치는데 새로 넣으려 한다").isTrue();
        // 지금 판의 시드 — 이름이 영문이다.
        assertThat(BootstrapRunner.alreadyThere(existing,
            new BootstrapRunner.CodeSeed(CodeType.MOOD, "serene", "잔잔한")))
            .as("같은 코드를 새로 넣으려 한다").isTrue();
        // 정말 없는 코드는 넣어야 한다 — 빈 DB 로 띄운 환경이 막히면 안 된다.
        assertThat(BootstrapRunner.alreadyThere(existing,
            new BootstrapRunner.CodeSeed(CodeType.MOOD, "warm", "따뜻한")))
            .as("없는 코드를 있다고 본다").isFalse();
        // 종류가 다르면 남이다. 같은 글자가 장르에 있어도 분위기는 넣는다.
        assertThat(BootstrapRunner.alreadyThere(existing,
            new BootstrapRunner.CodeSeed(CodeType.GENRE, "serene", "serene")))
            .as("종류를 가리지 않고 있다고 본다").isFalse();
    }

    @Test
    @DisplayName("지금 코드표 위에서 시드는 한 건도 넣지 않는다 — 기동해도 늘지 않는다")
    void seedingIsANoOpOnTheCurrentTable() {
        /* 시드 목록이 곧 코드표의 지금 모습이어야 한다. 하나라도 '없다' 로 판정되면
           운영에서 기동할 때마다 고아 행이 생긴다. */
        List<Code> table = BootstrapRunner.CODE_SEEDS.stream()
            .map(seed -> code(seed.type(), seed.name(), seed.displayName()))
            .toList();
        Set<BootstrapRunner.CodeSeed> existing = BootstrapRunner.existingNames(table);

        assertThat(BootstrapRunner.CODE_SEEDS)
            .allSatisfy(seed -> assertThat(BootstrapRunner.alreadyThere(existing, seed))
                .as("%s:%s 를 다시 넣으려 한다", seed.type(), seed.name()).isTrue());
    }

    /** 코드표 한 줄. 저장하지 않는다 — 판정만 본다. */
    private static Code code(CodeType type, String name, String displayName) {
        return Code.builder().codeType(type).codeName(name).displayName(displayName).build();
    }

    @Test
    @DisplayName("장르 8종도 차례가 그대로다")
    void genreSeedsKeepTheirOrder() {
        List<String> genres = BootstrapRunner.CODE_SEEDS.stream()
            .filter(seed -> seed.type() == CodeType.GENRE)
            .map(BootstrapRunner.CodeSeed::name)
            .toList();

        assertThat(genres).containsExactly(
            "Electronic", "Indie", "R&B", "Rock", "Pop", "Hip-hop", "Jazz", "Classical");
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
    @DisplayName("MuBTI 캐릭터 행은 파일 번호로 조회 URL 이 된다")
    void mubtiImageKeyYieldsNumberedUrl() {
        // 한때 조회 URL 이 저장 키를 되짚어 만들어졌다(`image/mubti/VTFC.webp` → 마디를
        // 떼어 URL 조립). 그때는 키 모양이 곧 조회 계약이라, 키를 손으로 만든 코드 하나가
        // `imageUrl` 을 예외도 로그도 없이 null 로 만들었고 `/report` 캐릭터가 조용히
        // 사라졌다. 지금은 파일 번호로 URL 을 만들므로 키 모양이 URL 에 관여하지 않는다 —
        // 그래서 옛 키든 새 키든 같은 주소가 나오고, 저장 키를 만드는 자리는
        // `StorageImageService.storageKey` 하나로 남는다.
        for (BootstrapRunner.MubtiSeed seed : BootstrapRunner.MUBTI_SEEDS) {
            String lookup = BootstrapRunner.mubtiImageKey(seed.code());

            String storageKey = StorageImageService.storageKey(FileCategory.MUBTI, lookup);
            assertThat(storageKey).as("%s 저장 키", seed.code())
                .isEqualTo("image/mubti/" + lookup);
        }

        assertThat(StorageImageService.imageUrl(fileAtKey("image/mubti/VTFC.webp", 7)))
            .as("저장 키가 아니라 파일 번호로 만든다")
            .isEqualTo("/api/v1/storage/image/7");
    }

    @Test
    @DisplayName("이미지가 아닌 파일과 지워진 행은 주소를 만들지 않는다")
    void deletedOrNonImageFilesHaveNoUrl() {
        ObjectStorageFile deleted = fileAtKey("image/mubti/VTFC.webp", 3);
        deleted.setIsDeleted(true);
        assertThat(StorageImageService.imageUrl(deleted)).isNull();

        // 음원 청크(`FileType.MUSIC`)는 이미지가 아니다. 주소를 주면 화면이 그 주소를
        // <img> 에 걸고, 조회 API 가 `INVALID_FILE_TYPE` 으로 거절해 깨진 그림이 된다.
        ObjectStorageFile chunk = fileAtKey("image/streaming/chunk_0001.rtp", 4);
        chunk.setFileType(FileType.MUSIC);
        chunk.setCategory(FileCategory.STREAMING);
        assertThat(StorageImageService.imageUrl(chunk)).isNull();

        assertThat(StorageImageService.imageUrl(null)).isNull();
    }

    @Test
    @DisplayName("기본 프로필 주소는 카테고리 대문자 규약을 따른다")
    void defaultProfileUrlKeepsCategoryUppercase() {
        // `SecurityConfig` 의 공개 경로와 프런트의 `DEFAULT_KEY` 가 이 모양에 맞물려 있다.
        assertThat(StorageImageService.defaultProfileImageUrl())
            .isEqualTo("/api/v1/storage/image/PROFILE/default");
    }

    private static ObjectStorageFile fileAtKey(String storageKey, int fileId) {
        return ObjectStorageFile.builder()
            .fileId(fileId)
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
