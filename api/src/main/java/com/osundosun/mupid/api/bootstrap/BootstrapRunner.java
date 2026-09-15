package com.osundosun.mupid.api.bootstrap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.code.enumerate.CodeType;
import com.osundosun.mupid.api.code.repository.CodeRepository;
import com.osundosun.mupid.api.report.entity.Mubti;
import com.osundosun.mupid.api.report.enumerate.MubtiClass;
import com.osundosun.mupid.api.report.repository.MubtiRepository;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.storage.service.StorageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 운영에 필요한 기준 데이터를 기동 때 채워 넣는다.
 *
 * <p>`ddl-auto` 가 `none` 이라 스키마는 사람이 만들지만(decision/0011), 그 안에 들어갈
 * 기준 데이터까지 사람이 넣게 두면 빈 DB 로 띄운 환경에서 기능이 막힌다 —
 * `codes` 가 비어 있으면 {@code GET /api/v1/onboarding/option} 이 503 이다
 * ({@code ONBOARDING_OPTION_EMPTY}). 그래서 없을 때만 넣는다.
 *
 * <p>이미 있는 행은 건드리지 않는다. 여러 번 띄워도 늘어나지 않고, 운영에서 이름을
 * 고쳐 두었다면 그 값이 그대로 남는다.
 *
 * <p>채우는 것은 두 가지다 — 공통 코드(`codes`)와 취향 유형(`mubti`).
 *
 * <p>넣을 값(`CODE_SEEDS` · `MUBTI_SEEDS`)과 그 값을 읽는 조각들이 `private` 이 아니라
 * 패키지 범위인 것은 `BootstrapSeedTest` 가 DB 없이 들여다보기 위해서다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BootstrapRunner implements ApplicationRunner {

    /**
     * 공통 코드 기준 데이터.
     *
     * <p>출처는 화면 디자인이다 — `frontend/lib/mupid-data.ts` 의 `moodOptions` ·
     * `genreOptions`, 그리고 같은 목록을 담은 `api/knowledge/domain/seed-codes.sql`.
     * 셋 중 하나를 고치면 나머지도 같이 봐야 한다.
     *
     * <p>온보딩 선택지는 <strong>등록 순서대로</strong> 화면에 깔린다
     * ({@code findAllByCodeTypeOrderByCodeIdAsc}). 여기 적은 차례가 곧 화면 차례다.
     *
     * <p>{@link CodeType#INSTRUMENT} 는 아직 확정된 목록이 없어 넣지 않는다.
     */
    static final List<CodeSeed> CODE_SEEDS = List.of(
        new CodeSeed(CodeType.MOOD, "잔잔한"),
        new CodeSeed(CodeType.MOOD, "벅찬"),
        new CodeSeed(CodeType.MOOD, "몽환적인"),
        new CodeSeed(CodeType.MOOD, "신나는"),
        new CodeSeed(CodeType.MOOD, "집중되는"),
        new CodeSeed(CodeType.MOOD, "감성적인"),
        new CodeSeed(CodeType.MOOD, "강렬한"),
        new CodeSeed(CodeType.MOOD, "설레는"),
        new CodeSeed(CodeType.GENRE, "Electronic"),
        new CodeSeed(CodeType.GENRE, "Indie"),
        new CodeSeed(CodeType.GENRE, "R&B"),
        new CodeSeed(CodeType.GENRE, "Rock"),
        new CodeSeed(CodeType.GENRE, "Pop"),
        new CodeSeed(CodeType.GENRE, "Hip-hop"),
        new CodeSeed(CodeType.GENRE, "Jazz"),
        new CodeSeed(CodeType.GENRE, "Classical")
    );

    /** MuBTI 캐릭터 이미지가 담긴 classpath zip. 파일 이름이 곧 코드다 (`VTFC.webp`). */
    static final String MUBTI_IMAGE_ZIP = "bootstrap/mubti-characters.zip";

    /** zip 안의 확장자. 원본 PNG 는 16장에 19MB 라 WebP(q=90) 로 줄여 넣었다. */
    static final String MUBTI_IMAGE_SUFFIX = ".webp";

    /** `object_storage_files.mime` 은 varchar(15) 다. `image/webp` 는 10 자라 들어간다. */
    private static final String MUBTI_IMAGE_MIME = "image/webp";

    /** 올라간 이미지의 스토리지 키. 코드가 유일하니 키도 유일하다. */
    private static final String MUBTI_IMAGE_KEY_FORMAT = "mubti/%s.webp";

    /**
     * 취향 유형(`mubti`) 16종. 기획의 조합표 그대로다.
     *
     * <p>네 축의 조합이 곧 코드이고(`classA`~`classD`), 코드가 이미지 파일 이름이다.
     * 설명문은 축 정의에서 만들어 낸다 — {@link MubtiSeed#description()} 참고.
     */
    static final List<MubtiSeed> MUBTI_SEEDS = List.of(
        new MubtiSeed(MubtiClass.A.V, MubtiClass.B.T, MubtiClass.C.F, MubtiClass.D.C,
            "보컬 인싸형", "모두의 보컬은 곧 나의 최애"),
        new MubtiSeed(MubtiClass.A.V, MubtiClass.B.T, MubtiClass.C.F, MubtiClass.D.E,
            "보컬 소개팅형", "뜨는 목소리는 끝까지 들어보는"),
        new MubtiSeed(MubtiClass.A.V, MubtiClass.B.T, MubtiClass.C.S, MubtiClass.D.C,
            "보컬 뚝심형", "인싸지만 나만의 색이 있는"),
        new MubtiSeed(MubtiClass.A.V, MubtiClass.B.T, MubtiClass.C.S, MubtiClass.D.E,
            "보컬 오디션형", "네 다음 보컬 나와주세요"),
        new MubtiSeed(MubtiClass.A.V, MubtiClass.B.M, MubtiClass.C.F, MubtiClass.D.C,
            "보컬 순애형", "최애 숨소리까지 음악이다"),
        new MubtiSeed(MubtiClass.A.V, MubtiClass.B.M, MubtiClass.C.F, MubtiClass.D.E,
            "보컬 발굴가형", "원석같은 보컬은 끝까지 파보는"),
        new MubtiSeed(MubtiClass.A.V, MubtiClass.B.M, MubtiClass.C.S, MubtiClass.D.C,
            "보컬 철벽형", "나의 보컬 취향은 바꿀 수 없어"),
        new MubtiSeed(MubtiClass.A.V, MubtiClass.B.M, MubtiClass.C.S, MubtiClass.D.E,
            "보컬 감별사형", "한 소절 만에 감별해 드립니다"),
        new MubtiSeed(MubtiClass.A.I, MubtiClass.B.T, MubtiClass.C.F, MubtiClass.D.C,
            "멜로디 인싸형", "모두가 듣는 순간은 이유가 있다"),
        new MubtiSeed(MubtiClass.A.I, MubtiClass.B.T, MubtiClass.C.F, MubtiClass.D.E,
            "멜로디 소개팅형", "새로 뜨는 사운드는 언제나 환영"),
        new MubtiSeed(MubtiClass.A.I, MubtiClass.B.T, MubtiClass.C.S, MubtiClass.D.C,
            "멜로디 뚝심형", "유명한 사운드도 안 꽂히면 싫어"),
        new MubtiSeed(MubtiClass.A.I, MubtiClass.B.T, MubtiClass.C.S, MubtiClass.D.E,
            "멜로디 오디션형", "네 다음 사운드 나와주세요"),
        new MubtiSeed(MubtiClass.A.I, MubtiClass.B.M, MubtiClass.C.F, MubtiClass.D.C,
            "멜로디 순애형", "나만의 작고 소중한 사운드"),
        new MubtiSeed(MubtiClass.A.I, MubtiClass.B.M, MubtiClass.C.F, MubtiClass.D.E,
            "멜로디 모험가형", "인생 사운드를 찾아 떠나는"),
        new MubtiSeed(MubtiClass.A.I, MubtiClass.B.M, MubtiClass.C.S, MubtiClass.D.C,
            "멜로디 철벽형", "나의 사운드 취향은 바꿀 수 없어"),
        new MubtiSeed(MubtiClass.A.I, MubtiClass.B.M, MubtiClass.C.S, MubtiClass.D.E,
            "멜로디 감별사형", "한 마디 만에 감별해 드립니다")
    );

    private final CodeRepository codeRepository;
    private final MubtiRepository mubtiRepository;
    private final StorageService storageService;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 기준 데이터가 없다고 해서 애플리케이션이 못 뜰 이유는 없다. 테이블이 아직
        // 없거나 계정에 권한이 없더라도 기동은 그대로 두고, 무엇이 막혔는지만 남긴다.
        seed("공통 코드", this::seedCodes);
        seed("취향 유형", this::seedMubti);
    }

    /**
     * 한 덩어리를 채운다. 실패해도 다음 덩어리는 계속 간다.
     *
     * <p>기준 데이터 하나가 안 들어갔다고 애플리케이션이 못 뜰 이유는 없다. 테이블이
     * 아직 없거나 계정에 권한이 없더라도 기동은 그대로 두고, 무엇이 막혔는지만 남긴다.
     */
    private void seed(String what, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.error("{} 기준 데이터를 채우지 못했습니다. 관련 기능이 막힐 수 있습니다.", what, e);
        }
    }

    /**
     * 공통 코드(`codes`) 중 빠진 것만 채운다.
     *
     * <p>있는지 없는지는 (용도, 이름) 으로 본다 — `code_id` 는 온보딩 응답이 참조하는
     * 값이라 다시 매기면 안 된다. 그래서 지우고 다시 넣지 않고, 없는 것만 더한다.
     *
     * <p>넣는 일 자체는 {@code saveAll} 한 번이라 그 안에서 트랜잭션이 끝난다 —
     * 여기에 {@code @Transactional} 을 달아도 같은 빈 안에서 부르는 호출이라 프록시를
     * 타지 않아 아무 효과가 없다.
     */
    private void seedCodes() {
        Set<CodeSeed> existing = new HashSet<>();
        for (Code code : codeRepository.findAll()) {
            existing.add(new CodeSeed(code.getCodeType(), code.getCodeName()));
        }

        List<Code> missing = new ArrayList<>();
        for (CodeSeed seed : CODE_SEEDS) {
            if (!existing.contains(seed)) {
                missing.add(Code.builder()
                    .codeType(seed.type())
                    .codeName(seed.name())
                    .build());
            }
        }

        if (missing.isEmpty()) {
            log.debug("공통 코드 {}건이 모두 있어 넣을 것이 없습니다.", CODE_SEEDS.size());
            return;
        }

        // 화면 차례가 code_id 차례라 선언한 순서대로 하나씩 넣는다.
        codeRepository.saveAll(missing);
        log.info("공통 코드 {}건이 없어 자동으로 등록했습니다: {}",
            missing.size(),
            missing.stream().map(code -> code.getCodeType() + ":" + code.getCodeName()).toList());
    }

    /**
     * 취향 유형(`mubti`) 중 빠진 것만 채운다.
     *
     * <p>`image_file_id` 가 NOT NULL 이라 행만 따로 만들 수 없다. 그래서 classpath zip 에서
     * 캐릭터 이미지를 꺼내 오브젝트 스토리지에 올리고, 그 파일을 물려 행을 만든다.
     *
     * <p>zip 은 <strong>빠진 유형이 있을 때만</strong> 연다. 16종이 다 있으면 20MB 를
     * 건드리지 않고, 있더라도 한 번에 한 장씩만 메모리에 올린다.
     */
    private void seedMubti() {
        // 유형의 정체는 네 축의 조합이다. 제목은 사람이 고칠 수 있으니 조합으로 본다.
        Set<String> existing = new HashSet<>();
        for (Mubti mubti : mubtiRepository.findAll()) {
            existing.add("" + mubti.getClassA() + mubti.getClassB()
                + mubti.getClassC() + mubti.getClassD());
        }

        Set<String> missing = new LinkedHashSet<>();
        for (MubtiSeed seed : MUBTI_SEEDS) {
            if (!existing.contains(seed.code())) {
                missing.add(seed.code());
            }
        }

        if (missing.isEmpty()) {
            log.debug("취향 유형 {}종이 모두 있어 넣을 것이 없습니다.", MUBTI_SEEDS.size());
            return;
        }

        int added = 0;
        ClassPathResource zip = new ClassPathResource(MUBTI_IMAGE_ZIP);
        try (ZipInputStream entries = new ZipInputStream(zip.getInputStream())) {
            ZipEntry entry;
            while ((entry = entries.getNextEntry()) != null) {
                String code = codeOf(entry);
                if (code == null || !missing.contains(code)) {
                    continue;
                }
                // 여기서 읽어야 다음 엔트리로 넘어가기 전에 내용을 가져올 수 있다.
                byte[] image = StreamUtils.copyToByteArray(entries);
                addMubti(seedOf(code), image);
                missing.remove(code);
                added++;
            }
        } catch (IOException e) {
            throw new IllegalStateException(MUBTI_IMAGE_ZIP + " 를 읽지 못했습니다.", e);
        }

        if (!missing.isEmpty()) {
            // zip 이 코드와 안 맞는 것은 배포 산출물 문제다. 넣은 것까지는 그대로 둔다.
            log.warn("취향 유형 {}종의 이미지가 {} 에 없어 넣지 못했습니다: {}",
                missing.size(), MUBTI_IMAGE_ZIP, missing);
        }
        if (added > 0) {
            log.info("취향 유형 {}종이 없어 캐릭터 이미지와 함께 자동으로 등록했습니다.", added);
        }
    }

    /** 이미지를 올리고 그 파일을 물린 유형 한 종을 만든다. */
    private void addMubti(MubtiSeed seed, byte[] image) {
        String fileName = seed.code() + MUBTI_IMAGE_SUFFIX;
        String key = MUBTI_IMAGE_KEY_FORMAT.formatted(seed.code());

        // 같은 키가 이미 있으면 uploadFile 이 내용만 갱신한다 — 파일이 늘어나지 않는다.
        ObjectStorageFile imageFile = storageService.uploadFile(
            FileType.IMG,
            FileCategory.MUBTI,
            key,
            new InMemoryMultipartFile(fileName, MUBTI_IMAGE_MIME, image),
            MUBTI_IMAGE_MIME);

        mubtiRepository.save(Mubti.builder()
            .title(seed.title())
            .subtitle(seed.subtitle())
            .description(seed.description())
            .classA(seed.classA())
            .classB(seed.classB())
            .classC(seed.classC())
            .classD(seed.classD())
            .imageFile(imageFile)
            .build());
    }

    /** zip 엔트리 이름에서 코드를 뽑는다. 디렉터리와 `VTFC.webp` 가 아닌 것은 버린다. */
    static String codeOf(ZipEntry entry) {
        if (entry.isDirectory()) {
            return null;
        }
        String name = entry.getName();
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (!name.endsWith(MUBTI_IMAGE_SUFFIX)) {
            return null;
        }
        return name.substring(0, name.length() - MUBTI_IMAGE_SUFFIX.length()).toUpperCase();
    }

    private static MubtiSeed seedOf(String code) {
        return MUBTI_SEEDS.stream()
            .filter(seed -> seed.code().equals(code))
            .findFirst()
            .orElseThrow();
    }

    /** 넣어야 할 코드 한 건. 있는지 없는지를 이 값으로 비교한다. */
    record CodeSeed(CodeType type, String name) {
    }

    /**
     * 넣어야 할 취향 유형 한 종.
     *
     * <p>제목·부제는 기획이 정한 문구라 그대로 적지만, 설명문은 네 축의 정의를 이어
     * 붙여 만든다. 16종을 손으로 쓰면 축을 고쳤을 때 어긋나는 것을 못 잡는다.
     */
    record MubtiSeed(
        MubtiClass.A classA,
        MubtiClass.B classB,
        MubtiClass.C classC,
        MubtiClass.D classD,
        String title,
        String subtitle
    ) {

        /** 네 축을 이은 코드. 이미지 파일 이름이기도 하다 (`VTFC`). */
        String code() {
            return "" + classA + classB + classC + classD;
        }

        /** `description` 은 varchar(150) 이다. 가장 긴 조합이 97 자라 넉넉하다. */
        String description() {
            return "%s, %s %s %s".formatted(
                classA == MubtiClass.A.V
                    ? "가사와 목소리를 중심으로 듣고"
                    : "멜로디와 사운드를 중심으로 듣고",
                classB == MubtiClass.B.T
                    ? "차트와 신곡을 빠르게 따라갑니다."
                    : "특정 장르와 아티스트를 깊게 파고듭니다.",
                classC == MubtiClass.C.F
                    ? "한 곡을 처음부터 끝까지 듣는 편이고,"
                    : "빠르게 넘기며 취향에 맞는 구간을 찾는 편이고,",
                classD == MubtiClass.D.C
                    ? "익숙한 최애곡을 반복해서 듣습니다."
                    : "낯선 곡과 새로운 아티스트를 적극적으로 발견합니다.");
        }

    }

}
