package com.osundosun.mupid.api.user.request;

/**
 * 사용자가 직접 입력하는 비밀번호의 형식 규칙. 회원가입·회원정보 수정·비밀번호 재설정
 * 세 요청이 같은 규칙을 쓴다.
 *
 * <h2>규칙</h2>
 *
 * <ul>
 *   <li>길이 8~20자</li>
 *   <li>영문(대소문자)·숫자·특수문자 {@code @$!%*#?&} <b>중 2가지 이상</b> 조합</li>
 *   <li>위 세 종류 밖의 문자는 허용하지 않는다 (공백 포함)</li>
 * </ul>
 *
 * {@code knowledge/architecture/auth-security.md} 가 기록한 의도와 같다. 원래 주석의 KISA
 * 근거는 "2종류 조합이면 10자 이상"이지만 실제로 강제해 온 하한은 8자이고, 이 수정은
 * 하한을 바꾸지 않는다.
 *
 * <h2>왜 상수인가</h2>
 *
 * 세 요청 DTO 에 같은 정규식이 복붙돼 있었고, 그중 하나만 고치면 규칙이 갈라진다.
 * {@code @Pattern} 의 {@code regexp} 는 컴파일 타임 상수여야 하므로
 * {@code public static final String} 으로 둔다.
 *
 * <h2>정규식 형태</h2>
 *
 * 이전 정규식은 {@code ^(?=(.*[A-Za-z].*[0-9])|(?=...))} 처럼 룩어헤드 안에서 분기가
 * 섞여 있어 첫 분기만 캡처 그룹이었고, 각 분기가 {@code .*X.*Y} 라 <b>등장 순서</b>까지
 * 강제했다. 숫자가 영문보다 앞에 오는 {@code 1234abcd} 가 거절되던 원인이다. 지금은
 * 분기마다 독립 룩어헤드 둘을 나란히 두어 순서에 의존하지 않는다.
 */
public final class UserPasswordPolicy {

    /**
     * 영문·숫자·특수문자 중 2가지 이상 조합, 허용 문자만으로 8~20자.
     */
    public static final String PASSWORD_REGEXP =
        "^(?:(?=.*[A-Za-z])(?=.*\\d)"
        + "|(?=.*[A-Za-z])(?=.*[@$!%*#?&])"
        + "|(?=.*\\d)(?=.*[@$!%*#?&]))"
        + "[A-Za-z\\d@$!%*#?&]{8,20}$";

    /**
     * 규칙을 어겼을 때 내려보내는 안내. 허용 특수문자를 직접 적어 둔다 —
     * 허용 밖 문자를 써서 거절될 때도 같은 메시지가 나가기 때문이다.
     */
    public static final String PASSWORD_MESSAGE =
        "비밀번호는 영문, 숫자, 특수문자(@$!%*#?&) 중 2가지 이상을 조합하여 8~20자로 입력해주세요.";

    private UserPasswordPolicy() {
    }

}
