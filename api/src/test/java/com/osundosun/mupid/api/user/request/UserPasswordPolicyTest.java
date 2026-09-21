package com.osundosun.mupid.api.user.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Pattern;

/**
 * 비밀번호 조합 규칙이 <b>등장 순서에 의존하지 않고</b> 통과·거절되는지 못박는다.
 *
 * <h2>무엇을 지키는 테스트인가</h2>
 *
 * 이전 정규식은
 * {@code ^(?=(.*[A-Za-z].*[0-9])|(?=.*[A-Za-z].*[@$!%*#?&])|(?=.*[0-9].*[@$!%*#?&]))...}
 * 였다. 분기가 룩어헤드 안에 섞여 첫 분기만 캡처 그룹이었고, 각 분기가 {@code .*X.*Y}
 * 형태라 <b>영문이 숫자보다 먼저 와야</b> 통과했다. 그래서 규칙을 지킨 {@code 1234abcd}
 * 가 400 {@code MUPID_INVALID_REQUEST_BODY} 로 거절됐다 — S15P21A204-417.
 *
 * <p>여기서 못박는 것은 넷이다.
 *
 * <ol>
 *   <li><b>순서 무관.</b> 두 종류를 어느 쪽이 먼저 오게 섞어도 통과한다.</li>
 *   <li><b>2종류 이상 · 8~20자 · 허용 문자 밖 금지.</b> 한 종류뿐이거나 길이를 벗어나거나
 *       {@code @$!%*#?&} 밖의 문자·공백이 섞이면 거절한다.</li>
 *   <li><b>세 DTO 가 같은 규칙을 쓴다.</b> 규칙이 {@link UserPasswordPolicy} 한 곳에
 *       모였으므로 셋을 같은 입력으로 함께 검증한다 — 하나만 고쳐 규칙이 갈라지는 일을
 *       막는다.</li>
 *   <li><b>값이 없으면 형식은 보지 않는다.</b> 회원정보 수정의 선택 입력이 이것에 기댄다.</li>
 * </ol>
 *
 * 스프링 컨텍스트를 띄우지 않는다. {@code @Pattern} 검증은 Bean Validation 이 하는 일이라
 * {@link Validator} 만 있으면 충분하고, DB·Redis 없이도 돈다.
 */
@DisplayName("비밀번호 조합 규칙")
class UserPasswordPolicyTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    /** 20자 경계값 — 영문 16 + 숫자 4. */
    private static final String LENGTH_20 = "abcdefghijklmnop1234";

    /** 21자 — 상한 바로 바깥. */
    private static final String LENGTH_21 = "abcdefghijklmnopq1234";

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    /**
     * 같은 비밀번호를 세 DTO 에 각각 실어 주는 공급자. 이름은 실패 메시지에 그대로 찍힌다.
     */
    static Stream<Object[]> dtoFactories() {
        return Stream.of(
            new Object[] {"UserCreateRequest",
                (Function<String, Object>) password -> new UserCreateRequest(
                    "user@example.com", 1, password, "닉네임", 2000, "MALE")},
            new Object[] {"UserUpdateRequest",
                (Function<String, Object>) password -> new UserUpdateRequest(
                    password, "닉네임", 2000, "MALE")},
            new Object[] {"UserSubmitPasswordResetRequest",
                (Function<String, Object>) password -> new UserSubmitPasswordResetRequest(
                    1, "token", password)}
        );
    }

    /** 규칙을 지킨 비밀번호. 종류 조합과 <b>등장 순서</b>를 모두 섞어 뒀다. */
    static Stream<String> validPasswords() {
        return Stream.of(
            "abcd1234",   // 영문 → 숫자
            "1234abcd",   // 숫자 → 영문 (이전 정규식이 거절하던 값)
            "Abcd!234",   // 영문 · 숫자 · 특수문자
            "!@#$abcd",   // 특수문자 → 영문
            "abcd!@#$",   // 영문 → 특수문자
            "1234!@#$",   // 숫자 → 특수문자
            "!@#$1234",   // 특수문자 → 숫자
            LENGTH_20     // 상한 경계
        );
    }

    /** 규칙을 어긴 비밀번호. */
    static Stream<String> invalidPasswords() {
        return Stream.of(
            "abcd123",     // 7자 — 하한 바로 바깥
            LENGTH_21,     // 21자 — 상한 바로 바깥
            "abcdefgh",    // 영문만
            "12345678",    // 숫자만
            "!@#$%*#?",    // 특수문자만
            "abcd1234 ",   // 공백 — 허용 문자 밖
            "abcd1234<",   // 허용하지 않는 특수문자
            "abcd1234한글",  // 한글
            ""             // 빈 문자열
        );
    }

    /** 공급자 × 비밀번호 곱집합. {@code @MethodSource} 가 만들어 주지는 않는다. */
    static Stream<Object[]> validCases() {
        return cross(validPasswords());
    }

    static Stream<Object[]> invalidCases() {
        return cross(invalidPasswords());
    }

    private static Stream<Object[]> cross(Stream<String> passwords) {
        return passwords.flatMap(password -> dtoFactories()
            .map(dto -> new Object[] {dto[0], password, dto[1]}));
    }

    @ParameterizedTest(name = "{0} · \"{1}\"")
    @MethodSource("validCases")
    @DisplayName("규칙을 지킨 비밀번호는 등장 순서와 무관하게 통과한다")
    void acceptsRulePassingPasswords(String dtoName, String password,
        Function<String, Object> request) {

        assertEquals(Set.of(), passwordViolations(request.apply(password)),
            dtoName + " 이 \"" + password + "\" 를 거절했다");
    }

    @ParameterizedTest(name = "{0} · \"{1}\"")
    @MethodSource("invalidCases")
    @DisplayName("종류·길이·허용 문자를 어기면 거절한다")
    void rejectsRuleBreakingPasswords(String dtoName, String password,
        Function<String, Object> request) {

        assertEquals(Set.of(UserPasswordPolicy.PASSWORD_MESSAGE),
            passwordViolations(request.apply(password)),
            dtoName + " 이 \"" + password + "\" 를 통과시켰다");
    }

    /**
     * 세 DTO 가 같은 상수를 가리키는지 본다. 하나만 손대 규칙이 갈라지면 여기서 걸린다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(classes = {
        UserCreateRequest.class, UserUpdateRequest.class, UserSubmitPasswordResetRequest.class})
    @DisplayName("세 요청이 같은 공용 상수를 가리킨다")
    void sharesOneRuleAcrossRequests(Class<?> type) throws NoSuchFieldException {
        Pattern pattern = type.getDeclaredField("password").getAnnotation(Pattern.class);

        assertEquals(UserPasswordPolicy.PASSWORD_REGEXP, pattern.regexp());
        assertEquals(UserPasswordPolicy.PASSWORD_MESSAGE, pattern.message());
    }

    /**
     * {@code @Pattern} 은 {@code null} 을 통과시킨다 — 비밀번호를 바꾸지 않는 회원정보
     * 수정이 이것에 기댄다. 필수 여부는 {@code @NotNull} 이 따로 본다.
     */
    @Test
    @DisplayName("값이 없으면 형식 검증을 건너뛴다")
    void skipsFormatCheckWhenAbsent() {
        assertTrue(passwordViolations(
            new UserUpdateRequest(null, "닉네임", 2000, "MALE")).isEmpty());
    }

    /** {@code password} 필드의 {@code @Pattern} 위반 메시지만 추린다. */
    private Set<String> passwordViolations(Object request) {
        return validator.validate(request).stream()
            .filter(violation -> violation.getPropertyPath().toString().equals("password"))
            .filter(violation -> violation.getConstraintDescriptor()
                .getAnnotation() instanceof Pattern)
            .map(ConstraintViolation::getMessage)
            .collect(Collectors.toSet());
    }

}
