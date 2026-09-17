# 인증과 보안

로그인·토큰·권한·이메일 인증에 손대기 전에 읽는다. 엔드포인트를 추가하면서 공개 경로를 열어야 할 때, 토큰 수명이나 저장 위치를 바꿀 때, 비밀번호를 다루는 코드를 건드릴 때, 401/403 응답 형태가 이상해 보일 때가 대상이다. 클라이언트를 붙이는 쪽이라면 "토큰 저장 전략"과 "함정" 절을 먼저 본다.

## 한눈에 보기

Spring Security + jjwt 0.12.5 기반의 무상태 JWT 인증이다. 세션은 없고(`STATELESS`), 토큰도 서버에 저장하지 않는다. Redis 는 로그아웃한 토큰의 무효화 목록으로만 쓴다.

| 엔드포인트 | 인증 | 하는 일 |
|---|---|---|
| `POST /api/v1/auth` | 없음 (permitAll) | 로그인 — 액세스 토큰(본문) + 리프레시 토큰(쿠키) |
| `GET /api/v1/auth` | 액세스 토큰 + `refresh_token` 쿠키 | 액세스 토큰 재발급 |
| `DELETE /api/v1/auth` | 액세스 토큰 + `refresh_token` 쿠키 | 로그아웃 — 두 토큰을 Redis 블록 목록에 등록 |
| `POST /api/v1/user` | 없음 (permitAll) | 회원가입 (이메일 인증 완료 필요) |
| `GET`/`POST /api/v1/user/reset` | 없음 (permitAll) | 비밀번호 재설정 메일 발송 / 코드 제출 |
| `/api/v1/mail/verification` | 없음 (permitAll) | 이메일 인증 메일 발송 / 코드 제출 |
| 그 외 전부 | 액세스 토큰 | `anyRequest().authenticated()` |

## 파일 지도

베이스 패키지는 `com.osundosun.mupid.api` (`api/AGENTS.md`). 아래 경로는 전부 `api/src/main/java/com/osundosun/mupid/api/` 하위다.

| 역할 | 파일 |
|---|---|
| 필터 체인·permitAll·`PasswordEncoder` 빈 | `common/config/SecurityConfig.java` |
| 로그인/재발급/로그아웃 컨트롤러 | `auth/controller/AuthController.java` |
| 로그인 검증·블록 등록 | `auth/service/AuthServiceImpl.java` |
| 토큰 발급 + 수명 상수 | `auth/provider/JwtProvider.java` |
| 토큰 검증·`Authentication` 생성·재발급 | `auth/provider/AuthProvider.java` |
| `Bearer` 헤더 파싱 필터 | `auth/filter/AuthFilter.java` |
| 401 / 403 직접 직렬화 | `auth/exception/AuthUnauthorizedHandler.java`, `auth/exception/AuthForbiddenHandler.java` |
| 인증 오류 코드 | `auth/error/AuthErrorCode.java` |
| Redis 블록 목록 | `cache/enumerate/CacheKind.java`, `cache/service/CacheServiceImpl.java` |
| 이메일 인증 | `email/` (controller / service / entity / enumerate / error) |
| 회원가입·비밀번호 인코딩 | `user/service/UserServiceImpl.java` |
| 비밀번호 재설정 | `user/controller/UserPasswordResetController.java` |
| Gmail 발송 | `common/gmail/GmailConfig.java`, `common/gmail/GmailProvider.java` |

수동 확인용 Bruno 컬렉션은 `api/docs/` 에 있다 (`Auth/`, `Email/`, `User/`).

## 로그인 — `POST /api/v1/auth`

1. `AuthController.login(@Valid AuthLoginRequest{email, password})`.
2. `AuthServiceImpl.login` → `userService.getUserByEmail(email)` (= `findByEmailAndStatusNot(email, WITHDRAW)`) 을 `.orElse(null)` 로 받는다.
3. `user == null || !passwordEncoder.matches(password, user.getPassword())` → `BusinessException(AuthErrorCode.WRONG_EMAIL_OR_PASSWORD)` (401).
   **미가입 이메일과 비밀번호 오류를 같은 코드로 합친 건 의도다** — 커밋 `ffbac12` "로그인 실패 시 이메일 존재 여부가 드러나지 않도록 수정". 여기를 나눠서 응답하면 계정 열거가 가능해진다.
4. `JwtProvider.issueAccessToken(user)` / `issueRefreshToken(user)` → `AuthLoginResult(accessToken, refreshToken, user)`.
5. 컨트롤러가 리프레시 토큰을 쿠키로 만든다.

```java
ResponseCookie.from("refresh_token", result.refreshToken())
    .sameSite("Strict")
    .httpOnly(true)
    .secure(true)
    .maxAge(Duration.ofMillis(JwtProvider.ACCESS_TOKEN_VALIDITY_MS))
    .build();
```

`path` 와 `domain` 은 지정하지 않는다. `maxAge` 에 들어간 값이 액세스 토큰 수명인 점은 함정 절 참고.

응답 본문은 `BaseResponse.of(new AuthLoginResponse(accessToken, userId, role.name()))`. `application.yaml` 의 `spring.jackson.property-naming-strategy: SNAKE_CASE` 때문에 JSON 키는 snake_case 다.

```json
{
  "message": null,
  "data": { "access_token": "eyJ...", "user_id": 1, "user_role": "USER" }
}
```

## 인증된 요청 — `AuthFilter`

`SecurityConfig` 가 `addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class)` 로 끼워 넣은 `OncePerRequestFilter` 다.

1. `Authorization` 헤더가 `Bearer ` 로 시작하면 `substring(7)`, 아니면 `null`.
2. 아래 셋 중 하나라도 참이면 **인증을 세팅하지 않고 체인을 그대로 통과**시킨다.
   - `accessToken == null`
   - `!authProvider.isValidToken(accessToken)` (서명·만료 검증 실패를 `JwtException | IllegalArgumentException` 으로 잡아 `false`)
   - `cacheService.exists(accessToken, CacheKind.BlockToken)` (로그아웃된 토큰)
3. 통과하면 `authProvider.auth(accessToken)` 결과를 `SecurityContext` 에 넣는다.
   - `token_type` 클레임이 `"ACCESS"` 가 아니면 `BadCredentialsException`
   - `sub` → `userId`, `role` → `UserRole.valueOf(...)`
   - `findByUserIdAndStatusNot(userId, WITHDRAW)` 실패 시 `BadCredentialsException`
   - `new UsernamePasswordAuthenticationToken(principal = User 엔티티, credentials = accessToken, authorities = [UserRole.name()])`
4. `AuthenticationException` 이 나오면 `authUnauthorizedHandler.commence` 로 401 을 직접 쓴다.

**"인증 실패"가 아니라 "인증 안 함"으로 처리하는 것이 핵심 설계다.** 최종 판정은 `authorizeHttpRequests` 가 하고 거절은 `AuthUnauthorizedHandler` 가 한다. 커밋 `70547a2` 에서 만들어진 구조이며, 덕분에 permitAll 경로는 깨진 토큰이 붙어 있어도 동작한다. 필터에서 예외를 던지도록 되돌리면 permitAll 경로가 깨진다.

컨트롤러는 `Authentication auth` 파라미터를 받아 `(User) auth.getPrincipal()` 로 유저를 꺼낸다 (`UserController`). `@AuthenticationPrincipal` 은 쓰지 않는다.

## 액세스 토큰 재발급 — `GET /api/v1/auth`

1. `@CookieValue(name = "refresh_token") String refreshToken` — `required` 기본값이 true 라 쿠키가 없으면 `MissingRequestCookieException` 이 난다. 이 예외를 다루는 핸들러가 없어 `GlobalExceptionHandler` 의 `Exception` 핸들러가 잡고 **500** 으로 나간다. `api/docs/Auth/액세스 토큰 재발급.yml` 은 400 이라고 적고 있지만 실제 응답은 500 이다.
2. `AuthServiceImpl.reissueAccessToken` 이 `cacheService.exists(refreshToken, BlockToken)` 를 먼저 보고, 걸리면 `AuthErrorCode.UNAUTHORIZED`.
3. `AuthProvider.refresh` — 서명 검증 → `token_type == "REFRESH"` 확인 → `findByUserIdAndStatusNot(WITHDRAW)` → `jwtProvider.issueAccessToken(user)`.
4. 응답은 `data.access_token` 하나. **새 `Set-Cookie` 는 없다 — 리프레시 토큰 회전(rotation)을 하지 않는다.**

이 경로는 permitAll 이 아니라서 **유효한 액세스 토큰도 함께 필요하다**. `api/docs/Auth/액세스 토큰 재발급.yml` 에 명시돼 있고, 실질적인 제약이므로 함정 절을 볼 것.

## 로그아웃 — `DELETE /api/v1/auth`

1. `String.valueOf(auth.getCredentials())` 로 액세스 토큰을, `@CookieValue` 로 리프레시 토큰을 꺼낸다. 둘 다 필요하다.
2. `authService.logout(List.of(accessToken, refreshToken))` 이 토큰마다 만료 시각을 파싱해 Redis 에 등록한다.

```java
Instant expiration = authProvider.parseExpiration(token).toInstant();
cacheService.set(token, CacheKind.BlockToken, Duration.between(now, expiration));
```

3. 키는 `block:token:<jwt 원문>`, 값은 빈 문자열, TTL 은 그 토큰의 남은 수명이다. 토큰이 자연 만료되면 키도 함께 사라져 목록이 무한히 커지지 않는다. 그래서 `CacheService` 에 `delete` 가 없어도 성립한다.
4. 응답은 `BaseResponse.message("로그아웃 처리되었습니다.")`. **쿠키를 지우는 `Set-Cookie` 는 보내지 않는다.**

## JWT 구조와 수명

`api/src/main/java/com/osundosun/mupid/api/auth/provider/JwtProvider.java`

```java
public static final long ACCESS_TOKEN_VALIDITY_MS  = 1000L * 60 * 60;          // 1H
public static final long REFRESH_TOKEN_VALIDITY_MS = 1000L * 60 * 60 * 24 * 7; // 7D
```

| | 액세스 토큰 | 리프레시 토큰 |
|---|---|---|
| 수명 | 1시간 | 7일 |
| `sub` | `userId` | `userId` |
| `role` | `UserRole.name()` | **없음** |
| `token_type` | `"ACCESS"` | `"REFRESH"` |
| `iat` / `exp` | 있음 | 있음 |

- 서명 키는 `Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret))`, `jwt.secret` ← `MUPID_JWT_SECRET`.
- **`JwtProvider` 와 `AuthProvider` 가 각각 `@Value("${jwt.secret}")` 로 `SecretKey` 를 따로 만든다.** 키 생성 방식을 바꾸면 두 곳을 함께 고쳐야 한다.
- 검증은 `Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token)` 한 형태로 통일돼 있다 (`AuthProvider.isValidToken`, `parseClaims`).
- `role` 이 리프레시 토큰에 없으므로 `AuthProvider.refresh` 는 DB 를 다시 조회한다. 권한 변경이 재발급 시점에 반영되는 구조다.

## 토큰 저장 전략

| 토큰 | 서버 저장 | 전달 방식 |
|---|---|---|
| 액세스 | 없음 (무상태) | 응답 본문 `data.access_token` → 이후 `Authorization: Bearer` 헤더 |
| 리프레시 | 없음 (무상태) | `refresh_token` 쿠키 (HttpOnly, Secure, SameSite=Strict) |
| 무효화 목록 | Redis `block:token:<jwt>`, 값 `""`, TTL = 토큰 잔여 수명 | 로그아웃 시에만 기록 |

리프레시 토큰을 DB/Redis 에 화이트리스트로 저장하지 않는다. 즉 `AuthProvider.refresh` 는 서명과 `token_type` 만 보고 새 액세스 토큰을 찍으며, 어떤 저장소와도 대조하지 않는다. **무상태를 유지하면서 로그아웃만 가능하게 만든 최소 설계**이고, 그 대가로 유저 단위 일괄 무효화 수단이 없다(비밀번호를 바꿔도 기존 토큰이 살아 있다 — 함정 절 참고).

Redis 접근은 반드시 `CacheService` 를 거친다. 키 접두사는 `CacheKind` enum 이 소유하고, 조립은 `CacheServiceImpl.buildKey` 한 곳뿐이다.

```java
private String buildKey(String key, CacheKind kind) {
    return "%s:%s".formatted(kind.getRedisKey(), key);
}
```

`CacheKind` 상수는 현재 `BlockToken("block:token")` 하나이고, Redis 를 쓰는 코드도 `AuthFilter` 와 `AuthServiceImpl` 둘뿐이다. 즉 이 프로젝트의 Redis 는 범용 캐시가 아니라 사실상 인증 전용이다. 전용 `@Configuration` 은 없고 Boot 자동설정이 준 `StringRedisTemplate` 을 그대로 쓴다.

## SecurityConfig

`api/src/main/java/com/osundosun/mupid/api/common/config/SecurityConfig.java`

- `csrf(disable)`, `httpBasic(disable)`, `formLogin(disable)`, `sessionCreationPolicy(STATELESS)`
- `exceptionHandling` — `authenticationEntryPoint(authUnauthorizedHandler)` (401), `accessDeniedHandler(authForbiddenHandler)` (403)
- `@Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }` — 강도 인자 없이 기본값

permitAll 목록 (코드에 적힌 순서·주석 그대로):

| 매처 | 주석 |
|---|---|
| `POST /api/v1/auth` | 로그인 |
| `GET /api/v1/auth` | 토큰 재발급 (자격증명이 쿠키의 리프레시 토큰이라 액세스 토큰을 요구할 수 없다) |
| `POST /api/v1/user` | 회원가입 |
| `/api/v1/user/reset` (메서드 무관) | 패스워드 초기화 |
| `/api/v1/mail/verification` (메서드 무관) | 이메일 인증 |
| `GET /api/v1/mail/verification/**` | 이메일 인증 확인 |
| `GET /api/actuator/**` | Health Check |
| `GET /api/v1/storage/image/**` | 이미지 조회. **경로로 가릴 수 없어 통째로 열고, 공개 여부는 서비스가 카테고리 정책으로 판단한다** (아래) |
| `GET /api/v1/onboarding/option` | 온보딩 선택지 (무드·장르 이름. 마스터 데이터) |
| `GET /api/admin`, `GET /api/admin/login` | 관리자 화면의 **HTML 골격만** |

나머지는 `anyRequest().authenticated()`.

### 이미지 조회 권한은 경로가 아니라 서비스가 판단한다

조회 주소가 `/api/v1/storage/image/{fileId}` 라 **번호만 보이고 카테고리가 없다.**
그래서 `SecurityConfig` 가 경로로 가릴 수 없다 — 한때는
`/api/v1/storage/image/ALBUM/*` 처럼 카테고리별로 열어 둘 수 있었지만, 조회를 저장
키에서 파일 번호로 바꾸면서 그 방법이 사라졌다.

대신 판단 자리가 **서비스로 내려왔다**(`StorageImageServiceImpl.getImageData`).
기준은 카테고리가 들고 있는 정책 하나(`FileCategory.accessPolicy`)다.

| 카테고리 | 읽기 | 이유 |
|---|---|---|
| `ALBUM` `ARTIST` `TRACK` `MUBTI` | **공개** | `<img src>` 에는 Authorization 을 붙일 수 없어 닫으면 목록의 표지가 깨진다. 키가 앨범·아티스트 이름이나 유형 코드라 개인 정보도 아니다 |
| `PROFILE` | 비공개 (401) | 개인 정보. 토큰을 붙여 바이트를 받아 object URL 로 쓴다 |
| `STREAMING` | 비공개 (401) | 음원 청크. 원래 nginx 가 스프링을 거치지 않고 스토리지로 흘려보낸다 |

`GET /api/v1/storage/image/PROFILE/default` 는 열려 있다 — 그 그림은 특정 사람의
사진이 아니라 사진 없는 계정의 자리를 채우는 기본값이기 때문이다.

쓰기(업로드·삭제)도 같은 정책이 판단한다. 프로필은 본인 번호만, 카탈로그 이미지는
`ROLE_SYSTEM` 이상만 — **업로드와 삭제가 같은 검사**를 지난다.

곡 카탈로그(목록·상세·아티스트·앨범·가사·분석)는 **열지 않는다.** 한때 열려 있었으나
비로그인이 곡을 보는 화면 자체가 없어져서 닫았다 — 근거는 `SecurityConfig` 의 주석 블록.

관리자 화면 두 장이 열려 있는 것은 **어쩔 수 없어서**다. 브라우저 주소창으로 들어오는
요청에는 `Authorization` 헤더를 붙일 수 없고 `AuthFilter` 는 헤더만 보므로, 닫아 두면
관리자도 들어올 수 없다. 그 HTML 에는 데이터가 없고 화면이 fetch 로 부르는
`/api/v1/admin/**` 가 `@AdminRole` 로 막혀 있다 — 실제 방어선은 그쪽 하나다. 자세한
것은 [관리자 화면](admin-console.md).

없는 것도 알아 둬야 한다.

- **CORS 설정이 없다.** `.cors(...)` 도, `@CrossOrigin` 도, nginx 의 `Access-Control-*` 헤더도 없다.
- **메서드 보안은 애너테이션 두 개로만 쓴다.** `SecurityConfig` 에 `@EnableMethodSecurity` 가 붙어 있고, 실제로 쓰는 것은 `common/annotation` 의 `@SystemRole`(`hasAnyRole('SYSTEM','ADMIN')`) 과 `@AdminRole`(`hasRole('ADMIN')`) 뿐이다. 컨트롤러에 `@PreAuthorize` 를 직접 적지 않고 이 둘 중 하나를 붙인다.
- 권한 문자열은 `UserRole.name()` 을 그대로 쓴다. ERD 에 맞춰 상수명이 `ROLE_USER` / `ROLE_SYSTEM` / `ROLE_ADMIN` 이 되면서 Spring 의 `hasRole` 규약과도 맞게 됐다.

## 401 / 403 응답

Security 필터 단계의 거절은 `@RestControllerAdvice`(`GlobalExceptionHandler`)를 타지 않는다. 그래서 두 핸들러가 `ErrorResponse.of(AuthErrorCode.…)` 를 직접 직렬화해 쓴다(커밋 `70547a2`).

```java
private final ObjectMapper objectMapper = new ObjectMapper();
```

스프링이 구성한 매퍼가 아니라 직접 만든 매퍼라서 **이 두 경로만 SNAKE_CASE 가 적용되지 않는다** — `error_details` 가 아니라 `errorDetails` 로 나간다. `api/docs/opencollection.yml` 에 "⚠️ 401 / 403 만 필드명이 다릅니다" 로 문서화돼 있다.

에러 코드 체계는 `common/error/ErrorCode` 인터페이스의 default 메서드가 만든다.

```java
default String getCode() { return "MUPID_%s".formatted(name()); }
```

`AuthErrorCode` 는 세 개뿐이다.

| 상수 | 상태 | 메시지 |
|---|---|---|
| `FORBIDDEN` | 403 | 접근 권한이 없습니다. |
| `UNAUTHORIZED` | 401 | 인증 정보가 유효하지 않거나 존재하지 않습니다. |
| `WRONG_EMAIL_OR_PASSWORD` | 401 | 이메일 혹은 패스워드가 잘못되었습니다. |

## 이메일 인증

`api/src/main/java/com/osundosun/mupid/api/email/` — Redis 가 아니라 **MySQL 테이블**(`email_verifications`)에 저장한다. 인증 코드 버그를 Redis 에서 찾으면 안 된다.

상수는 `EmailServiceImpl` 에 있다.

```java
private static final int TOKEN_LENGTH = 6;                            // 0-9, A-Z 36자 테이블
public static final long EMAIL_VERIFICATION_EXPIRED_MINUTES = 15;     // 코드 제출 시한
public static final long EMAIL_VERIFICATION_VALID_EXPIRATION_HOURS = 1; // 인증 완료 후 유효 창
```

**"코드 6자리 + 유효 창" 2단계 설계다.** 코드는 15분 안에 제출해야 하고, 제출에 성공하면 그 인증 건은 1시간 동안 "유효"로 간주된다. 회원가입/비밀번호 재설정은 이 유효 창 안에서 `verification_id` 를 제시해야 통과한다.

| 요청 | 하는 일 |
|---|---|
| `GET /api/v1/mail/verification` (본문에 `email`) | `sendVerification(email, SIGNUP, null)` → `data.verification_id` |
| `POST /api/v1/mail/verification` (`verification_id`, `token`) | `validateToken` → `verifiedAt` 기록 |
| `GET /api/v1/mail/verification/{verificationId}` | `isValid` → `data` 에 유효 여부 |

`validateToken` 의 거절 순서와 코드:

| 조건 | 오류 코드 | 상태 |
|---|---|---|
| `findById` 실패 | `MUPID_INVALID_VERIFICATION_ID` | 404 |
| `verifiedAt != null` | `MUPID_ALREADY_VERIFIED_TOKEN` | 400 |
| `expiredAt.isBefore(now)` | `MUPID_VERIFICATION_HAS_EXPIRED` | 400 |
| 토큰 불일치 | `MUPID_INVALID_VERIFICATION_TOKEN` | 401 |

통과하면 `verification.setVerifiedAt(now)` — `@Transactional` 더티 체킹으로 반영된다.

`EmailVerificationPurpose` 가 용도별 표시명·메일 제목·템플릿 경로·링크 경로를 묶는다.

| 상수 | 메일 제목 | 템플릿 | 링크 경로 |
|---|---|---|---|
| `SIGNUP` | 이메일 인증 번호를 보내드립니다. | `mail/email-verification` | `/verify/email` |
| `PW_RESET` | 패스워드 초기화 인증 번호를 보내드립니다. | `mail/password-reset` | `/verify/password` |

메일 본문은 코드와 링크를 **둘 다** 제공한다. `mupid-client.website`(← `MUPID_CLIENT_WEBSITE`, 기본값 빈 문자열)가 비어 있으면 `verifyUrl` 변수를 아예 넣지 않고, `templates/mail/fragments/button.html` 이 버튼 블록을 렌더링하지 않는다. 클라이언트 웹사이트가 없는 환경에서도 코드 입력만으로 인증이 되게 한 설계다. 발송은 `GmailProvider` 가 Gmail API(`users().messages().send`)로 하고, 자격증명은 `GmailConfig` 가 `UserCredentials`(client-id/secret + refresh token)로 만든다. 실패하면 `GmailException`(RuntimeException) → 500.

## 회원가입에서의 인증 확인

`UserServiceImpl.createUser` 는 `User.builder()` 로 객체를 먼저 만들고(`password` 는 `passwordEncoder.encode`, `status = PENDING`, `role = USER`) **그 다음** 검사한다.

1. `!emailService.isValid(id) || !emailVerification.getEmail().equals(dto.email())` → `MUPID_EMAIL_VERIFICATION_REQUIRED` (400)
2. `getUserByEmail(dto.email()).isPresent()` → `MUPID_DUPLICATE_EMAIL` (400)
3. `repository.save(newUser)`

비밀번호 형식은 `UserCreateRequest` 의 `@Pattern` 이 검증한다 — 영문/숫자/특수문자 중 2가지 이상, 8~20자 (주석에 KISA 가이드라인 근거).

가입 직후 상태는 `PENDING` 이며, 이를 `ACTIVE` 로 바꾸는 코드는 현재 없다.

## 비밀번호 재설정 — `/api/v1/user/reset`

1. `GET` (본문에 `email`) → `getUserByEmail(...).orElseThrow(USER_NOT_FOUND)` → `sendVerification(email, PW_RESET, user)` → `data.verification_id`.
2. `POST` (`verification_id`, `token`, `password`) → `validateToken` → `getVerification` → `verification.getUser()` 가 null 이면 `USER_NOT_FOUND` → `userService.updateUser(userId, new UserUpdateRequest(password, null, null, null))`.

코드 검증과 비밀번호 변경이 **한 요청에 묶인 단일 단계**다. 별도의 "코드 확인" 왕복이 없다.

`updateUser` 는 회원정보 수정(`PATCH /api/v1/user`)과 이 재설정 경로가 함께 지나가는 지점이다.

```java
if (dto.password() != null) user.setPassword(passwordEncoder.encode(dto.password()));
```

이 한 줄이 커밋 `054cfb5` "회원 정보 수정 시 비밀번호가 평문으로 저장되는 문제 수정" 의 결과다. 여기서 인코딩이 빠지면 재설정한 계정 전부가 평문으로 저장된다.

## 설정과 환경변수

`application.yaml` 은 값을 직접 적지 않고 `${MUPID_...}` 로만 참조한다. 실제 값은 `.env` 에 두고 `spring.config.import: optional:file:.env[.properties]` 로 주입된다.

| 프로퍼티 | 환경변수 | 비고 |
|---|---|---|
| `jwt.secret` | `MUPID_JWT_SECRET` | **Base64 디코딩된다.** `.env.example` 주석대로 `openssl rand -base64 32` 값을 넣는다 |
| `spring.data.redis.host` / `.port` | `MUPID_REDIS_HOST` / `MUPID_REDIS_PORT` | 기본값이 없어 없으면 기동 실패 |
| `gmail.*` | `MUPID_GMAIL_CLIENT_ID` / `_CLIENT_SECRET` / `_TOKEN` / `_SEND_ADDRESS` | `GmailProperties` 가 `@ConfigurationProperties` 로 바인딩 |
| `mupid-client.website` | `MUPID_CLIENT_WEBSITE` | 기본값 빈 문자열. 비면 메일에 인증 링크 버튼이 빠진다 |

`jwt.secret`, `gmail.client-id`, `gmail.client-secret` 은 `src/main/resources/META-INF/additional-spring-configuration-metadata.json` 에 등록돼 있다. `mupid-client.website` 는 아직 없다.

TLS 종단 위치도 알아 둔다. 실제 배포는 `infra/deploy/nginx.conf` 가 443 에서 인증서를 물고 80 은 308 리다이렉트한다. 반면 `api/nginx/templates/default.conf.template` 은 80 만 듣는다 — 리프레시 토큰 쿠키가 `Secure` 라서 `http://localhost` 에서는 브라우저·Bruno 의 쿠키 저장소에 남지 않는다(`api/docs/opencollection.yml` 에 명시). 그래서 Bruno 컬렉션은 `Cookie` 헤더를 직접 넣도록 작성돼 있다.

## 바꿀 때 지킬 규칙

- **비밀번호는 `passwordEncoder.encode(...)` / `matches(raw, encoded)` 로만 다룬다.** 평문을 엔티티에 넣는 경로를 만들지 않는다.
- **유저 조회는 `findByUserIdAndStatusNot(..., WITHDRAW)` / `findByEmailAndStatusNot(...)` 를 쓴다.** `findById` 를 쓰면 탈퇴 회원의 기존 토큰이 통과한다. 커밋 `70547a2` 가 탈퇴 차단을 조회 계층으로 내린 결과이고, `AuthProvider.auth`·`refresh`·`UserServiceImpl` 이 모두 이 규칙을 따른다.
- **로그인 실패는 항상 `WRONG_EMAIL_OR_PASSWORD` 하나로 응답한다.** 미가입/비밀번호 오류를 구분하면 커밋 `ffbac12` 의 의도가 깨진다.
- **permitAll 을 추가할 때는 HTTP 메서드까지 명시하고 한글 한 줄 주석을 단다.** 메서드를 생략하면 같은 경로의 다른 메서드까지 열린다(`/api/v1/user/reset` 이 그 사례다).
- **비즈니스 오류는 `throw new BusinessException(<도메인>ErrorCode.XXX)` 로만 던진다.** `GlobalExceptionHandler` 에 전용 핸들러가 있는 예외는 `BusinessException` 말고 셋뿐이다 — `NoResourceFoundException`(404), `HttpMessageNotReadableException`(404), `MethodArgumentNotValidException`(400). 나머지는 전부 `Exception` 핸들러가 잡아 500 으로 나간다. `valueOf`·`parseInt` 처럼 `IllegalArgumentException` 을 던지는 코드를 인증 경로에 넣을 때 특히 주의한다.
- **토큰이나 비밀번호를 예외 메시지에 넣지 않는다.** `GlobalExceptionHandler.serverError` 가 `log.error(e.getMessage())` 와 `e.printStackTrace()` 를 모두 호출한다.
- **Redis 키는 `CacheKind` 상수를 추가하고 `CacheService` 를 통해 접근한다.** 도메인 코드에 `StringRedisTemplate` 을 직접 주입하지 않는다.
- **`jwt.secret` 사용처는 두 곳(`JwtProvider`, `AuthProvider`)이다.** 키 생성 로직을 바꾸면 둘 다 고친다.
- **새 설정 키를 만들면** `.env.example` 의 해당 섹션과 `additional-spring-configuration-metadata.json` 을 함께 갱신한다. 환경변수는 예외 없이 `MUPID_` 접두사를 쓴다 (`api/AGENTS.md`).
- **엔드포인트를 추가하면 `api/docs/` Bruno 컬렉션에 같은 이름의 `.yml` 을 추가한다.** 인증이 필요한지, permitAll 인지를 `docs` 블록에 적는다.
- **인가 규칙은 `common/annotation` 의 `@SystemRole` · `@AdminRole` 로 붙인다.** 권한 문자열이 `ROLE_ADMIN` 이라 `hasRole("ADMIN")` 이 정상 매칭된다.

## 이 영역의 함정

상세와 재현 조건은 [함정과 실패 사례](../failure/auth-and-api.md) 에 있다. 여기서는 목록만 둔다.

| 함정 | 한 줄 요약 |
|---|---|
| `refresh_token` 쿠키 수명 | `maxAge` 가 `ACCESS_TOKEN_VALIDITY_MS`(1시간)라 JWT 수명(7일)과 어긋난다 |
| 재발급 API 가 permitAll 이 아님 | 액세스 토큰이 만료된 시점에는 `GET /api/v1/auth` 가 401 이다 |
| 만료 토큰으로 로그아웃 | `parseExpiration` 이 `ExpiredJwtException` 을 던져 500 이 된다 |
| 로그아웃이 쿠키를 안 지움 | `Set-Cookie maxAge(0)` 이 없어 브라우저에 사용 불가 쿠키가 남는다 |
| 401 / 403 만 camelCase | 두 핸들러가 `new ObjectMapper()` 를 써서 `errorDetails` 로 나간다 |
| CORS 미설정 | 다른 오리진에서 붙는 순간 프리플라이트가 막힌다 |
| `ROLE_` 접두사 없음 | `hasRole(...)` 이 동작하지 않는다 |
| `jwt.secret` 은 Base64 | 평문을 넣으면 기동 시 `WeakKeyException` |
| `EmailVerification.verifiedAt` | 엔티티는 `nullable = false` 인데 insert 시 채우지 않는다 |
| 본문 있는 GET | 인증 메일·재설정 메일 발송이 `@GetMapping` + `@RequestBody` 다 |
| 재설정 후 기존 토큰 유효 | 블록 목록이 토큰 단위라 유저 단위 일괄 무효화가 불가능하다 |
| `redis.key-prefix` | 프로퍼티만 있고 읽는 코드가 없다 |
