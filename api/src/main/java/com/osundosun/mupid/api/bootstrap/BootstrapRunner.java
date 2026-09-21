package com.osundosun.mupid.api.bootstrap;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
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
import com.osundosun.mupid.api.storage.service.StorageImageService;
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
     *
     * <p><b>무드 17종은 {@code codeName} 이 영문 내부 코드, {@code displayName} 이
     * 화면용 한국어다</b> (2026-09-21 정리). 운영 DB 에서 같은 무드가 한국어 이름과
     * 영문 이름으로 두 번 들어가(예: {@code melancholic} / {@code 애상적인}) 매핑
     * 테이블마다 다른 번호를 쓰던 것을, 영문 한 벌로 합쳤다
     * ({@code knowledge/domain/migrate-mood-codes-2026-09-21.sql}). 장르 45종과
     * 같은 방향이다. 이 값은 AI 서버로 그대로 넘어가므로({@code CodeOption::name})
     * {@code ai_server/app/onboarding.py} 의 {@code MOOD_MAP} 키와 맞아야 한다.
     */
    static final List<CodeSeed> CODE_SEEDS = List.of(
        // 대분류 8종 (화면 디자인). 운영 code_id 32~39.
        new CodeSeed(CodeType.MOOD, "serene", "잔잔한"),
        new CodeSeed(CodeType.MOOD, "uplifting", "벅찬"),
        new CodeSeed(CodeType.MOOD, "ethereal", "몽환적인"),
        new CodeSeed(CodeType.MOOD, "energetic", "신나는"),
        new CodeSeed(CodeType.MOOD, "focused", "집중되는"),
        new CodeSeed(CodeType.MOOD, "emotional", "감성적인"),
        new CodeSeed(CodeType.MOOD, "intense", "강렬한"),
        new CodeSeed(CodeType.MOOD, "romantic", "설레는"),
        // taxonomy 무드 9종 (2026-09-17 추가). **반드시 위 8종 뒤에 붙인다.**
        // 화면 차례가 곧 `code_id` 차례라(`findAllByCodeTypeOrderByCodeIdAsc`),
        // 사이에 끼워 넣으면 기존 사용자의 `onboarding_survey` 답이 다른 분위기로
        // 읽힌다. 운영 code_id 40~48. 이름은 `genre_mood_taxonomy.json` 원문이다.
        new CodeSeed(CodeType.MOOD, "melancholic", "애상적인"),
        new CodeSeed(CodeType.MOOD, "warm", "따뜻한"),
        new CodeSeed(CodeType.MOOD, "dreamy", "몽글한"),
        new CodeSeed(CodeType.MOOD, "bright", "화사한"),
        new CodeSeed(CodeType.MOOD, "hopeful", "희망찬"),
        new CodeSeed(CodeType.MOOD, "powerful", "웅장한"),
        new CodeSeed(CodeType.MOOD, "dark", "어두운"),
        new CodeSeed(CodeType.MOOD, "calm", "고요한"),
        new CodeSeed(CodeType.MOOD, "exciting", "짜릿한"),
        // 장르 대분류 8종 (온보딩 선택지). 운영 code_id 9~16.
        //
        // **`codeName` 은 그대로 둔다.** AI 서버가 이 이름을 세분류에 이름 포함으로
        // 잇고(`map_genre`), `GENRE_EXTRA` 의 키도 이 철자다 — 바꾸면 그 사용자의
        // 장르가 추천에 반영되지 않는다. 이미 영문이라 규칙(`code_name` = 영문)에 맞다.
        //
        // 표시명은 한국어다 (2026-09-21). 그전에는 비어 있어 화면이 `codeName` 으로
        // 되돌아갔고, 세분류 45종만 한국어라 **온보딩 목록에 영문과 한국어가 섞여**
        // 보였다. `R&B` 는 세분류 `rnb` 의 표시명(`R&B`)과 글자가 겹쳐 같은 칩이
        // 둘로 보이기까지 했다 — 그래서 대분류 쪽을 `알앤비` 로 갈라 둔다.
        new CodeSeed(CodeType.GENRE, "Electronic", "일렉트로닉"),
        new CodeSeed(CodeType.GENRE, "Indie", "인디"),
        new CodeSeed(CodeType.GENRE, "R&B", "알앤비"),
        new CodeSeed(CodeType.GENRE, "Rock", "록"),
        new CodeSeed(CodeType.GENRE, "Pop", "팝"),
        new CodeSeed(CodeType.GENRE, "Hip-hop", "힙합"),
        new CodeSeed(CodeType.GENRE, "Jazz", "재즈"),
        new CodeSeed(CodeType.GENRE, "Classical", "클래식")
    );

    /** MuBTI 캐릭터 이미지가 담긴 classpath zip. 파일 이름이 곧 코드다 (`VTFC.webp`). */
    static final String MUBTI_IMAGE_ZIP = "bootstrap/mubti-characters.zip";

    /** zip 안의 확장자. 원본 PNG 는 16장에 19MB 라 WebP(q=90) 로 줄여 넣었다. */
    static final String MUBTI_IMAGE_SUFFIX = ".webp";

    /** `object_storage_files.mime` 은 varchar(15) 다. `image/webp` 는 10 자라 들어간다. */
    private static final String MUBTI_IMAGE_MIME = "image/webp";

    /**
     * 조회 키. 코드가 유일하니 키도 유일하다 (`VTFC.webp`).
     *
     * <p>이것은 저장 키가 아니다. 저장 키는 {@link #mubtiImageStorageKey} 가 만들고, 조회 URL 은
     * {@code StorageImageService} 가 이 값에 카테고리를 붙여 만든다.
     */
    static String mubtiImageKey(String code) {
        return code + MUBTI_IMAGE_SUFFIX;
    }

    /**
     * 오브젝트 스토리지에 올릴 저장 키 — `image/mubti/VTFC.webp`.
     *
     * <p>접두사를 <b>손으로 적으면 안 된다.</b> 저장 키의 모양(`image/{카테고리}/{키}`)을 정하는
     * 곳은 {@code StorageImageServiceImpl.getStorageKey} 한 곳이고,
     * {@code StorageImageService.imageUrl(ObjectStorageFile)} 이 그 모양을 되짚어 조회 URL 을
     * 만든다. 여기서 접두사를 빼고 `mubti/VTFC.webp` 로 올렸더니 {@code split("/", 3)} 이
     * 3마디를 못 만들어 <b>조회 URL 이 조용히 null</b> 이 되었고, 그것이 `/report` 화면의
     * MuBTI 캐릭터가 뜨지 않던 원인이다.
     *
     * <p>그래서 접두사를 직접 적지 않고 스토리지 계층에 물어본다. 규약이 한 곳에만 있으면
     * 다음에 다른 시드가 들어와도 같은 방식으로 틀리지 않는다.
     */
    private String mubtiImageStorageKey(String code) {
        // 접두사를 손으로 적지 않는다. 키 모양을 아는 곳은 `StorageImageService` 하나뿐이고,
        // 조회 URL 이 그 모양을 되짚어 만들어진다 — 직접 적으면 둘이 조용히 갈라진다.
        return StorageImageService.storageKey(FileCategory.MUBTI, mubtiImageKey(code));
    }

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
        seed("취향 유형 이미지 키", this::repairMubtiImageKeys);
    }

    /**
     * 글자가 깨진 채로 들어간 코드가 있으면 기동 로그에 남긴다.
     *
     * <p>{@code mysql} 클라이언트에 {@code --default-character-set=utf8mb4} 를 빼고
     * 시드 SQL 을 부으면, 파일의 UTF-8 바이트를 클라이언트가 cp1252 로 읽어 다시
     * 인코딩한다. `잔잔한` 이 `ìž”ìž”í•œ` 이 된다.
     * <b>오류가 나지 않는다</b> — 행 수도 맞고 SQL 도 성공한다
     * ({@code knowledge/failure/database-and-jpa.md}).
     *
     * <p>이 배치는 (용도, 이름) 으로 대조하므로 깨진 행을 <b>고치지도, 알아채지도</b>
     * 못한다. 제대로 된 이름이 따로 들어오면 두 벌이 공존하고, 온보딩 화면에
     * 선택지가 두 배로 뜬다. 그래서 여기서 찾아 알린다.
     *
     * <p>고치는 것은 사람이 한다 — {@code code_id} 를 온보딩 응답이 참조하므로
     * 함부로 지울 수 없다.
     */
    /**
     * 코드표가 이미 쓰고 있는 이름 — <b>{@code code_name} 과 {@code display_name} 둘 다</b>.
     *
     * <p>두 벌을 한 자리에 모으는 것이 핵심이다. 코드표는 이름을 두 벌 갖는데 어느
     * 쪽이 영문이고 어느 쪽이 한국어인지가 판마다 달랐다 — 2026-09-21 정리 전에는
     * 분위기만 {@code code_name} 이 한국어였다. 한쪽만 보면 같은 코드를 못 알아본다.
     */
    static Set<CodeSeed> existingNames(List<Code> codes) {
        Set<CodeSeed> existing = new HashSet<>();
        for (Code code : codes) {
            existing.add(new CodeSeed(code.getCodeType(), code.getCodeName()));
            if (code.getDisplayName() != null && !code.getDisplayName().isBlank()) {
                existing.add(new CodeSeed(code.getCodeType(), code.getDisplayName()));
            }
        }
        return existing;
    }

    /**
     * 이 시드가 이미 코드표에 있는가. <b>이름과 표시명 중 하나만 겹쳐도 있는 것이다.</b>
     *
     * <p>좁게 보면 같은 것을 두 번 넣는다. 운영에서 2026-09-21 에 두 번 그랬다 —
     * 코드표의 분위기 {@code code_name} 을 한국어에서 영문으로 바꾼 뒤
     * ({@code 애상적인} → {@code melancholic}), 아직 배포되지 않은 옛 판이 한국어
     * 이름을 찾다 못 찾고 <b>17행을 새로 넣었다</b>(98~106, 다시 107~123). 어느 곡도
     * 참조하지 않는 고아였고 화면에는 같은 분위기가 두 칩으로 떴다.
     *
     * <p>넣으려는 {@code (MOOD, 애상적인)} 은 이미 있는
     * {@code (MOOD, melancholic, 애상적인)} 의 <b>표시명</b>과 겹치므로 여기서 걸린다.
     * 반대 방향도 같다 — 표시명이 영문이던 옛 행도 새 시드의 이름과 겹쳐 걸린다.
     */
    static boolean alreadyThere(Set<CodeSeed> existing, CodeSeed seed) {
        // `key()` 로 비교한다 — 표시명이 달라도 이름이 같으면 같은 코드다.
        return existing.contains(seed.key())
            || existing.contains(new CodeSeed(seed.type(), seed.displayName()));
    }

    private void warnGarbled(List<Code> codes) {
        for (Code code : codes) {
            String recovered = recoverDoubleEncoded(code.getCodeName());
            if (recovered == null) {
                continue;
            }
            log.warn("""
                글자가 깨진 공통 코드가 있습니다: code_id={} {}:'{}' (원래 값으로 보이는 것: '{}').
                시드 SQL 을 --default-character-set=utf8mb4 없이 부으면 이렇게 됩니다.
                이 행은 배치가 고치지 않습니다 — code_id 를 온보딩 응답이 참조하므로                 참조를 확인한 뒤 사람이 지워야 합니다.""",
                code.getCodeId(), code.getCodeType(), code.getCodeName(), recovered);
        }
    }

    /**
     * MySQL {@code latin1} 로 잘못 읽힌 UTF-8 인지 보고, 맞으면 원래 글자를 돌려준다.
     * 아니면 null.
     *
     * <p>판정은 되돌려 보는 것으로 한다 — 한 글자씩 <b>바이트 하나로</b> 되돌릴 수
     * 있고, 그 바이트열이 <b>온전한 UTF-8</b> 이며, 되살린 결과에 한글이 있으면 그렇게
     * 들어간 것이다. 영문 장르명({@code R&B}·{@code Hip-hop})은 되돌려도 한글이
     * 나오지 않으므로 걸리지 않는다.
     *
     * <p>순수 cp1252 로는 부족하다. {@code 0x81}·{@code 0x8D}·{@code 0x8F}·
     * {@code 0x90}·{@code 0x9D} 다섯 칸은 cp1252 에 정의가 없는데, MySQL 의
     * {@code latin1} 은 그 칸을 C1 제어문자로 그대로 통과시킨다. `몽환적인` 의
     * UTF-8 에 {@code 0x81} 이 들어 있어, 이 다섯 칸을 빼먹으면 절반만 잡힌다.
     */
    static String recoverDoubleEncoded(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        byte[] raw = new byte[value.length()];
        for (int i = 0; i < value.length(); i++) {
            Byte b = MYSQL_LATIN1_REVERSE.get(value.charAt(i));
            if (b == null) {
                return null;  // 바이트 하나로 되돌릴 수 없는 글자 — 이 경우가 아니다
            }
            raw[i] = b;
        }
        try {
            String recovered = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString();
            return hasHangul(recovered) ? recovered : null;
        } catch (CharacterCodingException e) {
            return null;  // UTF-8 로 안 읽힌다 — 그냥 그런 글자였다
        }
    }

    private static boolean hasHangul(String value) {
        return value.codePoints().anyMatch(cp -> cp >= 0xAC00 && cp <= 0xD7A3);
    }

    /**
     * MySQL {@code latin1} 의 글자 → 바이트 역매핑.
     *
     * <p>cp1252 가 정의한 251칸은 그 표대로, 나머지 다섯 칸은 바이트 값을 그대로
     * 코드포인트로 쓴다 — MySQL 이 그렇게 흘려보낸다.
     */
    private static final java.util.Map<Character, Byte> MYSQL_LATIN1_REVERSE = buildLatin1Reverse();

    private static java.util.Map<Character, Byte> buildLatin1Reverse() {
        Charset cp1252 = Charset.forName("windows-1252");
        java.util.Map<Character, Byte> map = new java.util.HashMap<>(256);
        for (int b = 0; b < 256; b++) {
            String decoded = new String(new byte[] {(byte) b}, cp1252);
            char c = decoded.length() == 1 && decoded.charAt(0) != '�'
                ? decoded.charAt(0)
                : (char) b;          // cp1252 에 없는 칸 — 바이트 값이 곧 코드포인트다
            map.putIfAbsent(c, (byte) b);
        }
        return java.util.Map.copyOf(map);
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
    /**
     * 없는 코드만 넣는다. <b>"없다" 를 좁게 보면 같은 것을 두 번 넣는다.</b>
     *
     * <p>2026-09-21 에 운영에서 두 번 그랬다. 코드표를 정리해 분위기 `code_name` 을
     * 한국어에서 영문으로 바꿨는데(`애상적인` → `melancholic`), 그때까지 배포돼 있던
     * 판의 시드는 한국어 이름을 찾고 있었다. 이름이 없으니 <b>없는 코드로 보고 17행을
     * 새로 넣었다</b> — 98~106, 다시 107~123. 둘 다 어느 곡도 참조하지 않는 고아였고,
     * 화면에는 같은 분위기가 두 칩으로 떴다.
     *
     * <p>그래서 <b>이름과 표시명 둘 다</b>로 본다. 한쪽이라도 같은 종류 안에서 겹치면
     * 이미 있는 것으로 친다. 위 사고에서 넣으려던 `(MOOD, 애상적인)` 은 이미 있는
     * `(MOOD, melancholic, 애상적인)` 의 표시명과 겹치므로 여기서 걸린다.
     *
     * <p>이 판단은 <b>넣을지 말지만</b> 정한다. 이미 있는 행은 고치지 않는다 —
     * 운영에서 사람이 이름을 손봤다면 그 값이 남아야 한다.
     */
    private void seedCodes() {
        List<Code> all = codeRepository.findAll();
        Set<CodeSeed> existing = existingNames(all);
        warnGarbled(all);

        List<Code> missing = new ArrayList<>();
        for (CodeSeed seed : CODE_SEEDS) {
            if (alreadyThere(existing, seed)) {
                continue;
            }
            missing.add(Code.builder()
                .codeType(seed.type())
                .codeName(seed.name())
                .displayName(seed.displayName())
                .build());
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

    /**
     * 이미지를 올리고 그 파일을 물린 유형 한 종을 만든다.
     *
     * <p>업로드 경로가 {@code StorageImageService.uploadImage} 가 아니라 {@code StorageService}
     * 직접인 것은 이 호출이 <b>컨트롤러 밖</b>이기 때문이다. 그래서 저장 키도
     * {@link #mubtiImageStorageKey} 로 직접 만든다 — 대신 접두사를 손으로 적지 않고
     * 스토리지 계층에 물어봐서 규약이 갈라지지 않게 한다.
     */
    private void addMubti(MubtiSeed seed, byte[] image) {
        String fileName = mubtiImageKey(seed.code());
        String key = mubtiImageStorageKey(seed.code());

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

    /**
     * 잘못된 접두사로 올라가 있는 캐릭터 이미지를 제자리로 되돌린다.
     *
     * <p>{@link #seedMubti} 는 <b>이미 있는 유형을 건드리지 않는다.</b> 그래서 규약을 고친 뒤
     * 새 코드를 배포해도, 예전 코드가 `mubti/VTFC.webp` 로 올려 둔 행은 그대로 남는다 —
     * {@code imageUrl} 이 3마디를 못 찾아 조회 URL 이 계속 null 이고, 화면은 여전히 캐릭터를
     * 못 그린다. <b>기동은 그대로 통과하므로 조용히 실패한다.</b> 그래서 여기서 한 번 훑는다.
     *
     * <h2>바이트를 zip 이 아니라 스토리지에서 읽는다</h2>
     *
     * <p>수리 대상은 <b>이미 스토리지에 있는</b> 이미지의 자리를 옮기는 일이다. zip 을 다시 열어
     * 그 코드의 그림을 올리면, 스토리지에 있는 것과 다른 판본으로 덮어써질 수 있다 — 운영에서
     * 캐릭터를 손봤다면 그 수정이 사라진다. 그래서 있는 것을 그대로 옮긴다.
     *
     * <p>덤으로 얻는 것이 하나 더 있다. 이 경로는 스토리지만 읽으므로 <b>JPA 세션이 필요 없다.</b>
     * 처음에는 `mubti.getImageFile().getStorageObjectKey()` 로 키를 읽으려 했는데, 그 연관이
     * 지연 로딩이고 {@code ApplicationRunner} 에는 트랜잭션이 없어
     * {@code LazyInitializationException} 으로 <b>기동 로그에만</b> 남고 넘어갔다. 키는
     * {@link #mubtiImageKey} 로 코드에서 만들 수 있으므로 연관을 아예 건드리지 않는다.
     *
     * <h2>순서</h2>
     *
     * <ol>
     *   <li>행의 키를 새 값으로 옮긴다.</li>
     *   <li>그 키로 올린다 — {@code uploadFile} 이 그 행을 찾아 내용만 갱신한다.</li>
     *   <li>옛 객체를 지운다.</li>
     * </ol>
     *
     * <p><b>1번과 2번을 바꾸면 안 된다.</b> 올리기를 먼저 하면 {@code uploadFile} 이 새 키로 행을
     * 찾지 못해 <b>행을 하나 더 만든다.</b> 그러면 한 키에 행이 둘이 되고
     * {@code findByStorageObjectKeyAndIsDeletedFalse} 가 결과 두 개를 받아
     * {@code IncorrectResultSizeDataAccessException} 으로 터진다 — 이미지 조회 전체가 죽는다.
     * 실제로 그 순서로 한 번 돌려서 16행이 중복으로 생겼다. 행을 먼저 옮기면 `file_id` 도 그대로라
     * (`mubti.image_file_id` 가 그 번호를 들고 있다) 그 점도 함께 지켜진다.
     *
     * <p>3번은 마지막이다. 먼저 지우면 그 사이 조회가 404 가 된다. S3 삭제는 <b>키 하나에만</b>
     * 걸리므로 3번이 2번을 되돌릴 위험은 없다.
     *
     * <p>옛 키에서 읽지 못하면 <b>아무것도 하지 않는다.</b> 스토리지가 죽었거나 그 객체가 이미
     * 없는 경우인데, 그때 새 키로 무엇을 올릴지 알 수 없다. zip 으로 대신 채우지 않는 것은
     * 위의 판본 문제 때문이다.
     */
    private void repairMubtiImageKeys() {
        int repaired = 0;

        for (Mubti mubti : mubtiRepository.findAll()) {
            String code = mubtiSeedCode(mubti);
            if (code == null) {
                // 네 축이 16종 밖이다. 시드가 만든 행이 아니므로 손대지 않는다.
                continue;
            }
            String wanted = mubtiImageStorageKey(code);
            String stale = legacyMubtiImageKeys(code).stream()
                .filter(key -> !key.equals(wanted))
                // 있는지 묻는 것은 **오브젝트**에 한다. `isFileExists` 는 DB 행을 보는데,
                // 여기서 찾는 옛 키의 행은 이미 지워졌을 수 있다 — 그러면 "없다" 로 답해서
                // 옮길 수 있는 이미지를 그냥 두고 지나간다. 실패하면 없는 것으로 본다.
                .filter(key -> storageService.getFileDataByKey(key).isPresent())
                .findFirst()
                .orElse(null);
            if (stale == null) {
                // 이미 규약대로거나, 옮길 원본이 없다. 둘 다 할 일이 없다.
                continue;
            }

            byte[] image = storageService.getFileDataByKey(stale).orElse(null);
            if (image == null) {
                log.warn("취향 유형 {} 의 옛 이미지({})를 읽지 못해 저장 키를 고치지 않았습니다.",
                    code, stale);
                continue;
            }

            // 순서가 중요하다. **행을 먼저 옮기고 나서 올린다.**
            //
            // 반대로 하면(upload → rename) `uploadFile` 이 새 키로 행을 찾지 못해
            // **행을 하나 더 만든다.** 그 뒤 rename 이 옛 행까지 같은 키로 맞추면 한 키에 행이
            // 둘이 되고, `findByStorageObjectKeyAndIsDeletedFalse` 가 결과 두 개를 받아
            // `IncorrectResultSizeDataAccessException` 으로 터진다 — 이미지 조회 전체가 죽는다.
            // 실제로 그 순서로 돌려서 16행이 중복으로 생겼다.
            //
            // 행을 먼저 옮기면 upload 가 그 행을 찾아 **내용만 갱신**하므로 `file_id` 가 그대로다.
            // `mubti.image_file_id` 가 그 번호를 들고 있어서 이 점이 중요하다.
            storageService.renameStorageObjectKey(stale, wanted);
            try {
                storageService.uploadFile(
                    FileType.IMG,
                    FileCategory.MUBTI,
                    wanted,
                    new InMemoryMultipartFile(mubtiImageKey(code), MUBTI_IMAGE_MIME, image),
                    MUBTI_IMAGE_MIME);
            } catch (RuntimeException e) {
                // 올리기가 실패하면 행만 새 키를 가리키게 남는다 — 그 상태의 조회는 404 다.
                // 되돌려서 "옛 키에 이미지가 있는" 원래 모양으로 만든다.
                storageService.renameStorageObjectKey(wanted, stale);
                throw e;
            }
            // 새 자리에 자리를 잡은 뒤에야 옛 객체를 지운다. S3 삭제는 키 하나에만 걸린다.
            storageService.deleteObjectQuietly(stale);
            repaired++;
        }

        if (repaired > 0) {
            log.info("잘못된 접두사로 올라가 있던 취향 유형 이미지 {}장을 규약(`image/mubti/`)에 맞춰 "
                + "다시 올렸습니다.", repaired);
        }
    }

    /**
     * 이 코드의 이미지가 놓여 있을 수 있는 옛 키들. 먼저 온 것부터 본다.
     *
     * <p>규약이 한 번 어긋났던 자리라 옛 모양을 <b>여기 적어 두는 것이 맞다.</b> 그때 무엇이었는지
     * 아는 곳이 여기밖에 없고, 앞으로 또 달라지면 그때 이 목록에 더한다.
     *
     * <ul>
     *   <li>`mubti/VTFC.webp` — 접두사 없이 올리던 시절의 키</li>
     *   <li>`image/mubti/VTFC.webp` — 규약대로인 키. 옛 키가 없을 때 여기서 찾아
     *       "이미 규약대로" 로 판정한다</li>
     * </ul>
     *
     * <p><b>접두사 없는 키를 빠뜨리면 수리가 조용히 아무것도 하지 않는다.</b> 실제로 처음에
     * 여기에 `VTFC.webp`(접두사도 카테고리도 없는 모양)를 적어 두는 바람에 후보가 하나도
     * 맞지 않아 기동이 그냥 지나갔다 — 로그도 안 남고 화면도 그대로였다.
     */
    static List<String> legacyMubtiImageKeys(String code) {
        String lookup = mubtiImageKey(code);
        return List.of(
            "mubti/" + lookup,
            StorageImageService.storageKey(FileCategory.MUBTI, lookup));
    }

    /**
     * 네 축을 이은 코드(`VTFC`). 16종 밖의 조합이면 null.
     *
     * <p>행의 정체를 축 조합으로 보는 것은 {@link #seedMubti} 와 같다 — 제목은 사람이 고칠 수 있다.
     */
    static String mubtiSeedCode(Mubti mubti) {
        if (mubti.getClassA() == null || mubti.getClassB() == null
            || mubti.getClassC() == null || mubti.getClassD() == null) {
            return null;
        }
        String code = "" + mubti.getClassA() + mubti.getClassB()
            + mubti.getClassC() + mubti.getClassD();
        return MUBTI_SEEDS.stream().anyMatch(seed -> seed.code().equals(code)) ? code : null;
    }

    /**
     * 넣어야 할 공통 코드 한 종.
     *
     * <p>{@code displayName} 은 화면에 보여줄 이름이다. 두 인자짜리 생성자는
     * {@code codeName} 과 같게 채운다 — 장르 8종은 코드 이름이 곧 표시명이다.
     */
    record CodeSeed(CodeType type, String name, String displayName) {

        CodeSeed(CodeType type, String name) {
            this(type, name, name);
        }

        /**
         * "이미 있는가" 를 비교하는 키. **표시명은 뺀다.**
         *
         * <p>{@code equals} 는 모든 항목을 보므로, 표시명을 나중에 채우거나 고친
         * 행은 이 시드와 다른 값이 되어 <b>없는 것으로 판정되고 다시 INSERT 된다.</b>
         * 그러면 `(code_type, code_name)` UNIQUE 에 걸려 기동이 실패한다 —
         * 표시명을 바꾸는 것만으로 서버가 못 뜨는 상황을 여기서 막는다.
         */
        CodeSeed key() {
            return new CodeSeed(type, name, name);
        }
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
