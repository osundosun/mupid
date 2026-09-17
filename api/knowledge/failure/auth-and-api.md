# 인증 · 보안 · 오류 응답

33건. 증상으로 찾고, **원인 / 해결 / 근거** 순으로 읽는다. 새로 밟은 함정은 이 파일에 같은 형식으로 추가한다.

[← 함정 목록](README.md) · [지식 저장소](../README.md)

---

## 인증 · 보안

### 로그인 1시간 뒤부터 액세스 토큰 재발급이 안 된다

- **원인**: `AuthController.login` 이 refresh_token 쿠키를 만들 때 `.maxAge(Duration.ofMillis(JwtProvider.ACCESS_TOKEN_VALIDITY_MS))` — 즉 **1시간**을 쓴다. JWT 자체는 `REFRESH_TOKEN_VALIDITY_MS`(7일)로 서명되므로 토큰은 살아 있는데 브라우저 쿠키만 1시간 뒤 사라진다.
- **해결**: 쿠키에는 `JwtProvider.REFRESH_TOKEN_VALIDITY_MS` 를 쓴다. 참고로 `api/docs/Auth/로그인.yml` 예시에는 또 다른 값(`Max-Age=1800`)이 적혀 있어 셋이 전부 다르다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/auth/controller/AuthController.java`, `api/src/main/java/com/osundosun/mupid/api/auth/provider/JwtProvider.java:18-19`

### 액세스 토큰이 만료되면 재발급 API 도 401 이 온다

- **원인**: `GET /api/v1/auth`(재발급)는 permitAll 이 아니라 `anyRequest().authenticated()` 에 걸린다(permitAll 은 `POST /api/v1/auth` 뿐). 그런데 `AuthFilter` 는 만료·차단된 액세스 토큰에 인증을 세팅하지 않으므로, 정작 재발급이 필요한 시점에 401 이 된다. 로그아웃(`DELETE /api/v1/auth`)도 같다.
- **해결**: 재발급 경로를 permitAll 로 열거나 리프레시 토큰만으로 인증하도록 바꿔야 정상 동작한다. 현재는 유효한 액세스 토큰과 refresh_token 쿠키를 함께 보내야 한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/common/config/SecurityConfig.java`, `api/docs/Auth/액세스 토큰 재발급.yml`

### 만료된 토큰으로 로그아웃하면 500 이 난다

- **원인**: `AuthServiceImpl.logout`(53-60행)이 `authProvider.parseExpiration(token)` 을 호출하고, 그 안의 `parseClaims` 가 만료 토큰에 `ExpiredJwtException` 을 던진다. `BusinessException` 이 아니라서 `GlobalExceptionHandler` 의 `@ExceptionHandler(Exception.class)` 로 떨어져 `MUPID_INTERNAL_SERVER_ERROR`(500)가 나간다. 액세스 토큰은 필터가 먼저 검증하므로 안전하지만 쿠키에 남은 오래된 refresh_token 이 이 경로를 탄다.
- **해결**: 로그아웃 처리 전에 만료 여부를 확인하고 이미 만료된 토큰은 조용히 건너뛴다. 만료 직전 토큰이면 `Duration.between(now, expiration)` 이 0 이하가 되어 Redis TTL 설정에서 다시 실패할 수 있으므로 함께 처리한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/auth/service/AuthServiceImpl.java:53-60`

### 로그아웃했는데 브라우저에 refresh_token 쿠키가 남아 있다

- **원인**: `AuthController.logout` 은 Redis 블록 목록에만 등록하고 `Set-Cookie` 로 쿠키를 만료시키지 않는다.
- **해결**: 로그아웃 응답에 `maxAge(0)` 쿠키를 함께 실어야 클라이언트 상태가 서버와 맞는다. 지금은 쿠키는 잘 보내지는데 서버가 `MUPID_UNAUTHORIZED` 로 거절하는 형태가 된다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/auth/controller/AuthController.java`

### 블록 목록에 잘못 넣은 토큰을 코드로 풀 수 없다

- **원인**: `CacheService` 인터페이스에 `delete`/`evict` 가 없다. 한 번 `block:token:<JWT>` 로 등록하면 TTL 이 끝날 때까지(리프레시 토큰이면 최대 7일) 애플리케이션 코드로는 못 푼다.
- **해결**: `docker compose -f compose.prod.yml exec redis redis-cli` 로 직접 지운다. 삭제가 필요한 새 용도를 넣는다면 인터페이스부터 확장한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/cache/service/CacheService.java`

### 비밀번호를 재설정해도 기존에 발급된 토큰이 그대로 살아 있다

- **원인**: `UserPasswordResetController.submitResetCode` 는 비밀번호만 교체하고 해당 유저의 액세스/리프레시 토큰을 블록 목록에 넣지 않는다. Redis 블록 목록이 토큰 문자열 단위(`block:token:<jwt>`)라 유저 단위 일괄 무효화 수단 자체가 없다.
- **해결**: 탈취 세션은 액세스 1시간·리프레시 7일이 지나야 끊긴다는 것을 전제하거나, 유저 단위 무효화 수단(토큰 버전 클레임 등)을 새로 만든다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/user/controller/UserPasswordResetController.java`

### 회원정보 수정 시 비밀번호가 평문으로 저장됐다 — 해소됨

- **원인**: `updateUser` 가 `user.setPassword(dto.password())` 로 인코딩 없이 저장했다. 가입 경로에만 `passwordEncoder` 를 적용하고 수정 경로를 빠뜨린 전형적 누락이다.
- **해결**: 커밋 `054cfb5` 에서 `passwordEncoder.encode(dto.password())` 로 수정. 현재 코드(70행)는 올바르다. **`updateUser` 는 회원정보 수정(`PATCH /api/v1/user`)과 비밀번호 재설정(`POST /api/v1/user/reset`) 두 경로가 모두 지나가는 지점**이므로, 여기서 인코딩이 빠지면 재설정한 계정 전부가 평문이 된다. 비밀번호를 엔티티에 넣는 코드는 반드시 `passwordEncoder.encode` 를 통과시킨다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/user/service/UserServiceImpl.java:70`, 커밋 `054cfb5`

### 로그인 실패 응답이 이메일 존재 여부를 노출했다 — 해소됨

- **원인**: 가입되지 않은 이메일에 `USER_NOT_FOUND` 를 돌려주어 계정 열거(enumeration)가 가능했다.
- **해결**: 커밋 `ffbac12` 에서 존재하지 않는 이메일도 `WRONG_EMAIL_OR_PASSWORD` 로 통일. 현재 `AuthServiceImpl.login`(34-37행)은 `orElse(null)` 후 한 종류만 던진다. `api/docs/Auth/로그인.yml` 의 오류 표에는 아직 `MUPID_USER_NOT_FOUND | 404` 가 남아 있으므로 문서를 믿지 말 것.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/auth/service/AuthServiceImpl.java:32-43`

### 잘못된 gender / role 값이 400 이 아니라 500 을 만든다

- **원인**: `UserServiceImpl` 은 `UserGender.valueOf(dto.gender())`(39, 73행), `AuthProvider.auth` 는 `UserRole.valueOf(claims.get("role", String.class))`(67행)를 쓴다. `valueOf` 는 매칭 실패 시 `IllegalArgumentException` 을 던지고, `BusinessException` 이 아니라서 500 으로 나간다. `gender` 는 요청 DTO 에서 값 검증이 없어 그대로 서비스까지 들어온다. 허용값은 ERD 에 맞춰 `M` / `F` / `ETC` 로 바뀌었다.
- **해결**: enum 값 검증을 요청 DTO 단계에 넣어야 400 으로 응답한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/user/service/UserServiceImpl.java`, `api/src/main/java/com/osundosun/mupid/api/auth/provider/AuthProvider.java:67`

### `hasRole("ADMIN")` 이 매칭되지 않았다 — 해소됨

- **원인**: `AuthProvider.auth` 가 `new SimpleGrantedAuthority(userRole.name())` 으로 `USER`/`ADMIN`/`GUEST` 를 접두사 없이 넣었다(커밋 `70547a2` 에서 `getCode()` → `name()` 으로 변경). Spring 의 `hasRole` 은 `ROLE_ADMIN` 을 찾는다.
- **해결**: ERD 의 `users.role` 에 맞춰 `UserRole` 상수명을 `ROLE_USER` / `ROLE_SYSTEM` / `ROLE_ADMIN` 으로 바꿨다. `name()` 이 그대로 접두사를 포함하므로 `hasRole("ADMIN")` 이 매칭된다. 단 **기존에 발급된 액세스 토큰의 `role` 클레임은 옛 값(`USER` 등)이라 `UserRole.valueOf` 에서 깨진다** — 재로그인이 필요하다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/auth/provider/AuthProvider.java:69-70`

### 403 경로가 한 번도 실제로 동작한 적이 없다

- **원인**: `SecurityConfig` 가 `AuthForbiddenHandler` 를 `accessDeniedHandler` 로 등록해 두었지만, 인가 규칙은 permitAll 과 `anyRequest().authenticated()` 뿐이고 코드베이스 전체에 `@PreAuthorize`/`hasRole`/`hasAuthority`/`@EnableMethodSecurity` 가 없다. 권한 부족 상황 자체가 만들어지지 않는다.
- **해결**: 권한 기반 제어를 추가할 때 이 핸들러가 처음 실제로 동작하게 된다는 걸 전제하고 검증한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/common/config/SecurityConfig.java`

### 프론트엔드를 다른 오리진에서 붙이면 프리플라이트가 막힌다

- **원인**: `SecurityConfig` 에 `.cors(...)` 가 없고, 저장소 전체에서 `cors` / `@CrossOrigin` / `Access-Control` 이 0건이다. `api/nginx/templates/default.conf.template` 에도 CORS 헤더가 없다.
- **해결**: CORS 설정을 새로 만든다. 리프레시 토큰 쿠키를 쓰려면 `allowCredentials(true)` 와 와일드카드가 아닌 오리진 지정이 필요하다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/common/config/SecurityConfig.java`, `api/nginx/templates/default.conf.template`

### `MUPID_JWT_SECRET` 을 넣었는데 기동 시 `WeakKeyException` 이 난다

- **원인**: `JwtProvider`(26행)와 `AuthProvider`(43행)가 모두 `Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret))` 를 쓴다. 평문 문자열을 넣으면 Base64 디코딩 결과가 32바이트 미만이 된다.
- **해결**: `.env.example` 과 README 가 안내하는 `openssl rand -base64 32` 결과를 그대로 넣는다. 두 클래스가 같은 시크릿으로 `SecretKey` 를 따로 만들므로 생성 방식을 바꾸면 두 곳을 함께 고친다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/auth/provider/JwtProvider.java:26`, `api/src/main/java/com/osundosun/mupid/api/auth/provider/AuthProvider.java:43`

### `http://localhost` 에서 refresh_token 쿠키가 저장되지 않는다

- **원인**: 쿠키가 `HttpOnly` + `Secure` + `SameSite=Strict` 로 발급되므로 로컬 http 환경의 브라우저·Bruno 쿠키 저장소에 남지 않는다.
- **해결**: 재발급·로그아웃 요청에는 `Cookie` 헤더를 직접 넣는다. 쿠키가 없으면 `@CookieValue`(required 기본 true) 때문에 400 이다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/auth/controller/AuthController.java`, `api/docs/opencollection.yml`

### 가입은 되는데 아무도 ACTIVE 가 되지 않는다

- **원인**: `UserServiceImpl.createUser` 가 `status(UserStatus.PENDING)` 으로 저장하는데, `setStatus` 를 호출하거나 `onboardingSubmittedAt` 을 채우는 코드가 `src/main/java` 전체에 없다. 온보딩 API 가 아직 없다.
- **해결**: `SecurityConfig`·`AuthProvider` 가 `PENDING` 을 막지는 않으므로 로그인과 API 사용은 된다. 상태 기반 분기를 추가하려면 이 전제를 알고 시작한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/user/service/UserServiceImpl.java:40`

### 401 / 403 응답만 필드명이 snake_case 가 아니다

- **원인**: `AuthUnauthorizedHandler` 와 `AuthForbiddenHandler` 가 `private final ObjectMapper objectMapper = new ObjectMapper();` 로 매퍼를 직접 만든다. `spring.jackson.property-naming-strategy: SNAKE_CASE` 는 Spring 이 관리하는 ObjectMapper 에만 적용되므로 이 두 경로만 `error_details` 가 아니라 `errorDetails` 로 나간다.
- **해결**: 클라이언트가 두 키를 모두 처리하거나, 두 핸들러가 컨텍스트의 ObjectMapper 를 주입받도록 고친다. `api/docs/Error/NotFound.yml` 의 두 예시(404 는 `error_details`, 401 은 `errorDetails`)가 이 차이를 그대로 기록하고 있다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/auth/exception/AuthUnauthorizedHandler.java`, `api/src/main/resources/application.yaml:19-20`

### `error_details` 안쪽 키만 camelCase 로 나온다

- **원인**: 검증 실패 응답의 `error_details` 는 `Map<String,String>` 이고 키는 `FieldError.getField()` 즉 Java 필드명이다. Map 의 **키**는 Jackson property-naming-strategy 적용 대상이 아니다. 래퍼 필드는 `error_details` 로 바뀌는데 안쪽 키는 `emailVerificationId`, `birthYear` 처럼 남는다.
- **해결**: 클라이언트가 요청 필드명(`email_verification_id`)으로 매칭하려 하면 실패한다는 걸 전제한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/common/exception/GlobalExceptionHandler.java:44-57`, `api/docs/opencollection.yml`

### JSON 파싱 실패가 400 이 아니라 404 로 나간다

- **원인**: `GlobalErrorCode.NOT_READABLE_MESSAGE` 가 `HttpStatus.NOT_FOUND` 로 선언되어 있다. `HttpMessageNotReadableException` 이 이 코드로 매핑된다.
- **해결**: 의도인지 실수인지는 코드에 근거가 없다. docs 의 오류 표들도 `MUPID_NOT_READABLE_MESSAGE | 404` 로 이 동작을 그대로 적어 두었으므로, 400 으로 바꾸면 이미 404 를 기대하는 클라이언트·문서가 깨진다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/common/error/GlobalErrorCode.java:20`

### 오타 난 경로를 호출했는데 404 가 아니라 401 이 온다

- **원인**: Spring Security 필터가 DispatcherServlet 보다 먼저 동작하므로 `NoResourceFoundException` 까지 가지 못하고 `AuthUnauthorizedHandler` 가 401 을 먼저 반환한다.
- **해결**: 라우팅 문제를 401 로 오해해 토큰을 의심하지 않는다. 404 동작을 확인하려면 permitAll 범위 안의 없는 경로(`/api/actuator/no-such-endpoint`)를 호출한다.
- **근거**: `api/docs/Error/NotFound.yml`, `api/src/main/java/com/osundosun/mupid/api/common/config/SecurityConfig.java`

### 500 원인을 로그 파일에서 못 찾는다

- **원인**: `GlobalExceptionHandler.serverError` 가 `log.error(e.getMessage())` 로 **메시지만** 남기고 `e.printStackTrace()` 를 따로 호출한다. 로거에는 스택트레이스가 없고, 스택트레이스는 stdout 으로 나간다. 메시지가 null 인 예외는 로그에 `null` 만 찍힌다. `UserImageServiceImpl:94` 에도 같은 패턴이 있다.
- **해결**: 컨테이너 stdout(`docker compose logs api`)을 함께 본다. 고칠 때는 `log.error(e.getMessage(), e)` 로 바꾼다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/common/exception/GlobalExceptionHandler.java:59-64`, `api/src/main/java/com/osundosun/mupid/api/user/service/UserImageServiceImpl.java:94`

### 외부 연동이 실패하면 전부 500 이고 재시도가 없다

- **원인**: `GmailException` 과 AWS SDK 예외(`S3Exception` 등)를 처리하는 `@ExceptionHandler` 가 없어 `@ExceptionHandler(Exception.class)` 로 잡히고 `MUPID_INTERNAL_SERVER_ERROR` 가 나간다. 스토리지 도메인 에러는 `StorageErrorCode.FILE_NOT_FOUND` 하나뿐이고 이건 DB 메타데이터가 없을 때만 쓰이므로, **S3 에서 오브젝트가 사라진 경우는 404 가 아니라 500** 이다. Spring Retry, `@Async`, 아웃박스 테이블은 저장소 어디에도 없다.
- **해결**: 외부 연동 예외를 도메인 에러로 매핑할지 먼저 정하고 핸들러를 추가한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/common/exception/GlobalExceptionHandler.java`

### 미인증 건 조회에서 NPE 가 났다 — 해소됨

- **원인**: `EmailServiceImpl` 에 남아 있던 디버그 코드 `System.out.println("debug : " + Duration.between(verifiedAt, now).toHours())` 가 원인이었다. 아직 인증되지 않은 건은 `verifiedAt` 이 null 이다. 정상 로직은 그 아래에서 `verifiedAt != null` 을 검사하고 있었으므로 순수하게 디버그 코드 탓이었다.
- **해결**: 커밋 `d6309af` 에서 제거. 현재 `isValid`(100-115행)는 null 검사를 먼저 한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/email/service/EmailServiceImpl.java:98-115`

### `@SystemRole` 이 붙은 API 가 전부 500 을 뱉는다 — 해소됨

- **원인**: `@PreAuthorize("hasRole('SYSTEM', 'ADMIN')")`. `hasRole` 은 인자를 **하나만** 받는다. SpEL 평가 단계에서 `EL1004E: Method call: Method hasRole(java.lang.String,java.lang.String) cannot be found` 로 실패하고 `IllegalArgumentException` 이 `@ExceptionHandler(Exception.class)` 에 잡혀 `MUPID_INTERNAL_SERVER_ERROR` 가 나간다. **403 이 아니라 500 이라** 권한 문제로 보이지 않는다.
- **해결**: 여러 역할은 `hasAnyRole('SYSTEM', 'ADMIN')` 이다. 메서드 시큐리티는 컨트롤러 메서드 진입 시점에 평가되므로 잘못된 본문으로 호출하면 `@Valid` 검증이 먼저 돌아 400 이 나간다 — 그래서 검증 실패 케이스만 테스트하면 이 버그가 안 보인다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/common/annotation/SystemRole.java:15`

### 액세스 토큰이 만료되면 재발급도 못 받는다 — 해소됨

- **원인**: `SecurityConfig` 가 `POST /api/v1/auth`(로그인)만 `permitAll` 이라, 재발급인 `GET /api/v1/auth` 가 `anyRequest().authenticated()` 에 걸렸다. **액세스 토큰이 만료된 뒤에 부르는 엔드포인트인데 액세스 토큰을 요구**하니 재발급 경로 자체가 죽어 있었다.
- **해결**: `GET /api/v1/auth` 도 `permitAll` 로 둔다. 자격증명은 쿠키의 리프레시 토큰이고 `AuthService.reissueToken` 이 서명·만료·차단목록을 검증한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/common/config/SecurityConfig.java`

### `@PathVariable` 이름이 URL 템플릿과 다르면 500 이 난다 — 해소됨

- **원인**: `@RequestMapping("/api/v1/music/{musicID}/stream")` + `@PathVariable int musicId`. 대소문자가 달라 Spring 이 값을 못 찾는다. 기동 시점에는 안 걸리고 **그 엔드포인트를 처음 호출할 때** 터진다.
- **해결**: 템플릿 이름과 파라미터명을 맞추거나 `@PathVariable("musicID")` 로 명시한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/stream/controller/MusicStreamController.java:26`

### 멀티파트 폼 필드를 snake_case 로 보내면 400 이 난다

- **원인**: `spring.jackson.property-naming-strategy: SNAKE_CASE` 는 **JSON 본문 역직렬화에만** 적용된다. `@RequestParam` 이름은 Jackson 을 거치지 않으므로 Java 파라미터명 그대로 camelCase 다.
- **해결**: 멀티파트 필드는 `imageFile` 처럼 camelCase 로 보낸다. JSON 본문만 snake_case 다. (청크 업로드는 멀티파트를 쓰지 않는다 — 본문에 바이너리를 그대로 싣는 PUT 이고 nginx 가 처리한다.)
- **근거**: `api/src/main/java/com/osundosun/mupid/api/storage/controller/StorageImageController.java`, `api/src/main/resources/application.yaml`

### `@NotBlank` 를 숫자 필드에 붙이면 400 이 아니라 500 이 난다 — 해소됨

- **원인**: `@NotBlank(message = "아티스트가 입력되지 않았습니다.") Integer artistId`. `@NotBlank` 는 `CharSequence` 전용이라 `jakarta.validation.UnexpectedTypeException: HV000030: No validator could be found for constraint '@NotBlank' validating type 'java.lang.Integer'` 가 난다.
- **해결**: 숫자·객체는 `@NotNull`, 문자열은 `@NotBlank`.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/music/request/AlbumCreateRequest.java`

### `@SystemRole` / `@AdminRole` 이 거부하면 403 이 아니라 500 이 나온다 — 해소됨

- **원인**: `@PreAuthorize` 거부는 컨트롤러 **메서드 호출 지점**에서 `AuthorizationDeniedException`(= `AccessDeniedException`) 으로 터진다. 필터에서 나는 게 아니라 `SecurityConfig` 의 `AuthForbiddenHandler` 를 타지 않고, `GlobalExceptionHandler` 의 `@ExceptionHandler(Exception.class)` 에 먹혀 `MUPID_INTERNAL_SERVER_ERROR` 가 나갔다. `authorizeHttpRequests` 가 직접 막는 경로만 403 이 제대로 나왔다. Bruno 문서는 줄곧 403 이라고 적어 두고 있었다.
- **해결**: `GlobalExceptionHandler` 에 `@ExceptionHandler(AccessDeniedException.class)` 를 추가해 `AuthErrorCode.FORBIDDEN` 으로 매핑한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/common/exception/GlobalExceptionHandler.java`

### 업로드가 거절됐는데 응답이 500 이고 본문이 비어 있다

- **원인**: 청크 업로드(`PUT /upload/chunk/...`)는 nginx `auth_request` 를 거친다. nginx 는 인증 서브요청의 **401·403 만** 클라이언트로 넘기고, 그 밖의 4xx(404·409·413 등)는 전부 500 으로 바꾸며 본문도 버린다. 스프링이 `MUPID_INVALID_CHUNK_INDEX` 를 400 으로 돌려줘도 클라이언트는 빈 500 을 본다.
- **해결**: `GlobalExceptionHandler` 가 오류 코드를 `X-Mupid-Error` 헤더로도 내보내고, nginx 가 `auth_request_set` + `add_header ... always` 로 그 헤더를 살려 보낸다. **상태 코드가 아니라 이 헤더를 보고 사유를 판단한다.**
- **근거**: `api/nginx/templates/default.conf.template`, `api/src/main/java/com/osundosun/mupid/api/common/exception/GlobalExceptionHandler.java`

### 업로드도 X-Accel-Redirect 로 되겠거니 하고 짠다

- **원인**: `X-Accel-Redirect` 는 **응답** 헤더를 보고 nginx 가 내부 리다이렉트를 거는 기능이다. 내부 리다이렉트에는 원 요청의 본문이 실리지 않으므로 업로드에는 쓸 수 없다. 스프링이 응답을 만들 시점엔 이미 본문이 스프링 쪽으로 넘어온 뒤이기도 하다.
- **해결**: 업로드는 `auth_request` 다. nginx 가 본문 없이 인증만 먼저 묻고(`proxy_pass_request_body off`), 200 과 함께 받은 경로로 본문을 스토리지에 흘린다(`proxy_request_buffering off`).
- **근거**: `api/nginx/templates/default.conf.template`, [decision/0016](../decision/0016-audio-upload-three-step.md)

### 브라우저에서 인증 메일 발송·비밀번호 재설정을 호출할 수 없다 — 해소됨

- **원인**: `GET /api/v1/mail/verification` 과 `GET /api/v1/user/reset` 이 `@RequestBody` 로 이메일을 받았다. **fetch 는 GET 에 본문을 실을 수 없다**(`TypeError: Request with GET/HEAD method cannot have body`). curl 이나 Bruno 로는 되기 때문에 서버 테스트만으로는 드러나지 않는다.
- **해결**: `@Valid @ModelAttribute` 로 바꿔 쿼리 파라미터(`?email=...`)로 받는다. 검증 실패가 `BindException` 으로 오므로 `GlobalExceptionHandler` 의 `@ExceptionHandler(MethodArgumentNotValidException.class)` 도 상위 타입인 `BindException` 으로 넓혔다. 안 넓히면 쿼리 파라미터 검증 실패만 500 이 된다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/email/controller/EmailVerificationController.java`, `api/src/main/java/com/osundosun/mupid/api/user/controller/UserPasswordResetController.java`

---

### 쿼리 파라미터 타입이 안 맞으면 500 이 난다 — 해소됨

- **원인**: `?page=abc`, `?type=LOVE`(enum 에 없는 값), `/api/v1/music/abc` 같은 요청은 `MethodArgumentTypeMismatchException` 을 던지는데 `GlobalExceptionHandler` 에 핸들러가 없어 `Exception` 핸들러가 먹고 500 으로 나갔다. 보낸 쪽 잘못인데 서버가 고장 난 것처럼 보인다.
- **해결**: `@ExceptionHandler(MethodArgumentTypeMismatchException.class)` 를 추가해 400(`MUPID_INVALID_REQUEST_BODY`)으로 돌린다. enum 이면 고를 수 있는 값을 `error_details` 에 적어 준다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/common/exception/GlobalExceptionHandler.java`

### `UserGetInfoResponse.imageUrl` 이 부를 수 없는 주소다 — 해소됨

- **원인**: `getProfileImageUrl` 이 `/api/v1/storage/image/profile/{저장 키 전체}` 를 만들었다. 저장 키는 이미 `image/profile/15` 라 결과가 `/api/v1/storage/image/profile/image/profile/15` 였다. 경로 마디 수가 달라 컨트롤러에 잡히지도 않고 401 이 됐다. 앨범·아티스트 쪽은 `MusicServiceImpl` 이 따로 만들고 있어 규약이 두 벌로 갈려 있었다.
- **해결**: `StorageImageService.imageUrl(...)` 정적 메서드 하나로 모으고 둘 다 그것을 쓴다.
- **이후**: 조회가 **파일 번호**(`/api/v1/storage/image/{fileId}`)로 바뀌면서 저장 키 모양이 주소에 관여하지 않게 됐다. 카테고리 **대문자** 규약은 저장 키(`image/{소문자}/{키}`)와 기본 프로필 주소(`/PROFILE/default`)에 남아 있다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/storage/service/StorageImageService.java`

### 카탈로그 이미지는 로그인만 하면 누구나 덮어쓰고 지울 수 있었다 — 해소됨

- **원인**: 소유권 검사가 `PROFILE` 에만 있었다(`UserImageServiceImpl.requireOwnKey`). `ALBUM`·`ARTIST`·`TRACK`·`MUBTI` 는 콜백 구현체가 없어 업로드 후 할 일이 없다는 이유로 검사 자체가 없었고, 그 결과 **로그인한 아무 사용자나** `/api/v1/storage/image/ALBUM/1` 같은 키로 남의 표지를 덮어쓰거나 지울 수 있었다. 삭제는 `deleteFile` 이 스토리지 오브젝트까지 지우므로 피해가 더 컸다.
- **해결**: 검사를 카테고리가 들고 있는 정책 하나로 모은다 — `FileCategory.accessPolicy` → `ImageAccessPolicy`(`OWNER`/`CATALOG`/`MACHINE`). `check(actor, storageKey)` 를 **업로드와 삭제 양쪽에서** 부른다. 한쪽만 잠그면 잠그지 않은 쪽으로 우회되기 때문이다.
- **업로드 검사를 먼저 두는 것도 중요하다.** 뒤에 두면 권한 없는 요청이 스토리지에 바이트를 남기고 끝난다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/storage/enumerate/ImageAccessPolicy.java`, `StorageImageServiceImpl.java`, `StorageImageAccessTest.java`

### 익명 요청에서 `SecurityUtils.getAuthUser()` 가 500 을 냈다 — 해소됨

- **원인**: 스프링 시큐리티는 인증이 없을 때 컨텍스트를 비워 두지 않고 **익명 인증**을 넣는다(principal 이 `"anonymousUser"` 문자열). `getAuthUser` 는 `isAuthenticated()` 만 보고 `(User)` 로 캐스팅해서 익명 요청마다 `ClassCastException` 이 났고, 그것이 최종 폴백 핸들러에 먹혀 **401 이어야 할 응답이 500** 으로 나갔다. 인증이 필수인 경로에서만 쓰이던 동안은 잠복해 있었다.
- **해결**: `auth.getPrincipal() instanceof User user ? user : null` — "인증이 있는가" 가 아니라 **principal 이 우리 `User` 인가**를 본다. 공개 이미지 조회가 생기면서 비로그인 요청이 이 자리를 지나게 되어 드러났다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/utils/SecurityUtils.java`

### 프로필 사진 삭제가 404 로 실패한다 — 해소됨

- **원인**: `StorageImageServiceImpl.deleteImage` 가 콜백을 부른 뒤 파일을 지우는데, `UserImageServiceImpl.handleDeleteImageBefore` 도 같은 파일을 지우고 있었다. 두 번째 삭제가 `FILE_NOT_FOUND` 로 터져 트랜잭션 전체가 롤백됐다.
- **해결**: 콜백은 **연결만 끊는다.** 파일 삭제는 호출부가 한 번만 한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/user/service/UserImageServiceImpl.java`

### 남의 번호를 키로 프로필 사진을 올릴 수 있었다 — 해소됨

- **원인**: 저장 키가 경로 변수(`/api/v1/storage/image/PROFILE/{storageKey}`)라 아무 값이나 넣을 수 있었다. `image/profile/{남의 번호}` 로 올리면 그 사람 프로필 파일을 덮어쓰고, 업로드한 사람에게 연결됐다.
- **해결**: `ImageAccessPolicy.OWNER` 가 저장 키의 마지막 마디를 본인 유저 번호와 대조한다. 어긋나면 `MUPID_STORAGE_KEY_MISMATCH`(403) 다. 이 검사는 업로드 입구(`StorageImageServiceImpl.uploadImage`)에서 한다 — 콜백(`handleUploadImageAfter`)에서 하던 것을 정책으로 옮긴 것은 검사 규칙이 업로드·삭제 두 자리에서 필요해졌기 때문이다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/storage/enumerate/ImageAccessPolicy.java`

### 로그인한 적 없는 첫 방문자에게 `GET /api/v1/auth` 가 500 을 준다 — 해소됨

- **원인**: `@CookieValue(name = "refresh_token")` 이 필수라 쿠키가 없으면 `MissingRequestCookieException` 이 나고, 핸들러가 없어 `Exception` 핸들러가 먹고 500 이 됐다. 세션 복원은 **첫 방문자가 늘 밟는 길**이라 열 때마다 서버 오류가 찍혔다.
- **해결**: `required = false` 로 받고 없으면 `AuthErrorCode.UNAUTHORIZED`(401)를 던진다. "재발급할 세션이 없다" 는 오류가 아니라 상태다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/auth/controller/AuthController.java`

### 로그인이 1시간 만에 풀린다 — 해소됨

- **원인**: 리프레시 토큰 쿠키의 `maxAge` 가 `ACCESS_TOKEN_VALIDITY_MS`(1시간)였다. 토큰 자체는 7일짜리인데 쿠키가 먼저 사라져 재발급을 못 한다. 로그인과 재발급 **두 곳 모두** 같은 값을 쓰고 있어서 재발급을 해도 수명이 늘지 않았다.
- **해결**: 두 곳 다 `REFRESH_TOKEN_VALIDITY_MS`. 이미 `failure` 목록에 올라와 있던 건인데 재발급 경로가 빠져 있었다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/auth/controller/AuthController.java`

### 로그인 전에는 공개 API 도 실패한다 — 해소됨

- **원인**: `ApiClient.request` 가 재발급이 도는 동안 들어온 요청을 **익명 요청까지** 줄 세웠다. 첫 방문자는 세션 복원(재발급)이 항상 실패하므로, 그 사이 나간 곡 목록·온보딩 선택지 요청이 큐와 함께 401 로 실패했다. 서버는 200 을 주는데 화면에는 곡이 하나도 없었다.
- **해결**: `if (this.refreshing && !options.anonymous)`. 토큰이 필요 없는 요청은 기다릴 이유가 없다.
- **근거**: `frontend/lib/api/client.ts`

### 낡은 쿠키 하나로 `GET /api/v1/auth` 가 500 이 된다 — 해소됨

- **원인**: 재발급은 필터가 아니라 **컨트롤러에서** 토큰을 푼다. 모양이 깨졌거나 서명이 안 맞거나 만료된 토큰은 JJWT 가 `JwtException` 을 던지고, 탈퇴·삭제된 사람을 가리키는 토큰은 `BadCredentialsException` 이 난다. 둘 다 핸들러가 없어 `@ExceptionHandler(Exception.class)` 에 먹혀 **500** 으로 나갔다. 쿠키가 아예 없는 경우만 401 로 고쳐 두었던 터라, 브라우저에 낡은 쿠키가 남아 있으면 첫 화면부터 서버가 고장 난 것처럼 보였다.
- **해결**: `GlobalExceptionHandler` 에 `JwtException`·`BadCredentialsException` → `MUPID_UNAUTHORIZED`(401) 를 더한다. 401 이어야 프런트가 로그인으로 보낸다.
- **확인**: 쿠키 없음·`garbage`·`aaa.bbb.ccc` 모두 401.
- **근거**: `api/.../common/exception/GlobalExceptionHandler.java`

### 곡 카탈로그를 비로그인에게 열어 두었다 — 해소됨

- **원인**: `SecurityConfig` 에 곡 목록·상세·아티스트·앨범·가사·분석을 `permitAll` 로 두었다. 근거는 "첫 방문자가 온보딩 전에 곡을 둘러본다" 였는데, 그 뒤 **로그인해야만 서비스를 쓸 수 있게** 바뀌면서(`frontend/lib/routes.ts` 의 publicPaths 는 `/`·`/login`·`/signup` 뿐) 비로그인이 곡을 보는 화면 자체가 없어졌다. 데모 곡도 없앴다. 근거가 사라졌는데 규칙만 남아 곡·가사·분석이 통째로 공개돼 있었다.
- **해결**: 카탈로그 조회를 전부 닫는다. 프런트의 해당 호출에서 `anonymous: true` 를 걷어내 토큰을 싣는다.
- **함께 고쳐야 하는 것**: `MupidApp` 이 **세션 복원보다 먼저** 카탈로그를 받고 있었다. 그대로 닫으면 첫 방문자의 요청이 401 로 떨어져 로그인 화면에 "음악을 불러오지 못했어요" 가 뜬다. 세션을 먼저 복원하고, 비로그인이면 빈 목록으로 연 뒤, 로그인해서 계정이 잡히면 그때 다시 받는다. 뒤늦게 들어온 목록에서 곡을 고르는 일은 `useMupid` 가 맡는다.
- **열어 둔 것**: `/api/v1/storage/image/ALBUM|ARTIST/*` 는 **닫으면 안 된다.** `<img src>` 에는 Authorization 헤더를 붙일 수 없어 목록의 표지가 전부 깨진다. 키가 앨범·아티스트 이름이라 개인 정보도 아니다. 프로필 사진(PROFILE)은 개인 정보라 닫힌 채로 두고 토큰을 붙여 바이트를 받는다.
- **확인**: 비로그인 `curl` 로 목록·상세·가사·분석·아티스트·앨범 모두 401, 앨범 이미지 200. 쿠키를 지운 첫 방문은 `/login` 이 뜨고 오류 문구가 없다. 폼으로 로그인하면 새로고침 없이 `/home` 에 곡과 표지 15장이 그대로 뜬다.
- **근거**: `api/.../common/config/SecurityConfig.java`, `frontend/components/mupid-app.tsx`, `frontend/lib/api/endpoints.ts`

## 관리자 화면

### 관리자 화면을 `/admin` 에 만들면 배포하면 사라진다

- **원인**: 운영 nginx(`infra/deploy/nginx.conf`)가 스프링으로 넘기는 것은 `location /api/` 하나뿐이다. 나머지는 `root /srv/web/current` 의 정적 프런트엔드로 떨어지므로 `/admin` 은 `try_files` 에 걸려 **404** 가 된다. 로컬 `bootRun` 에서는 nginx 를 거치지 않아 멀쩡히 열리기 때문에 배포하기 전까지 드러나지 않는다.
- **해결**: 스프링이 직접 서빙해야 하는 것은 `/api/` 아래에 둔다 — 관리자 화면은 `/api/admin`. 액추에이터가 `management.endpoints.web.base-path: /api/actuator` 로 옮겨져 있는 것이 같은 대응이다. nginx 에 `location /admin/` 을 새로 뚫는 방법도 있지만, 배포 설정은 `infra/` 소관이라 손이 더 간다 ([decision/0008](../decision/0008-deploy-image-from-infra.md)).
- **근거**: `infra/deploy/nginx.conf`, `api/src/main/resources/application.yaml`, `api/src/main/java/com/osundosun/mupid/api/admin/controller/AdminController.java`

### 관리자 화면 경로를 `hasRole("ADMIN")` 으로 닫으면 관리자도 못 들어온다

- **원인**: `SecurityConfig` 에 `.requestMatchers("/admin/**").hasRole("ADMIN")` 이 있었지만 **아무도 통과할 수 없었다.** `AuthFilter.resolveAccessToken` 은 `Authorization` 헤더의 Bearer 토큰만 보는데, 브라우저 주소창으로 들어오는 최초 요청에는 그 헤더를 붙일 방법이 없다. 인증이 세팅되지 않은 채 인가에 도달하므로 늘 401 이다. 쿠키의 리프레시 토큰은 필터가 보지 않는다.
- **해결**: HTML 골격만 `permitAll` 로 열고(`GET /api/admin`, `GET /api/admin/login`), 데이터는 전부 `fetch` 로 받게 한다. 화면이 부르는 `/api/v1/admin/**` 는 `@AdminRole` 로 막혀 있고 실제 방어선은 그쪽 하나다. **그 HTML 에 `th:` 로 서버 데이터를 심는 순간 이 구조가 깨진다** — 로그인하지 않은 사람이 데이터를 보게 된다.
- **대안이 아닌 것**: 세션 로그인을 따로 붙이는 것. `SessionCreationPolicy.STATELESS` 를 깨야 하고 인증 경로가 둘로 갈린다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/auth/filter/AuthFilter.java`, `api/src/main/java/com/osundosun/mupid/api/common/config/SecurityConfig.java`, [architecture/admin-console.md](../architecture/admin-console.md)

### `MusicGenre` · `MusicMood` · `MusicAnalysisInstrument` · `MusicSectionAnalysisMood` 는 JPA 로 INSERT 할 수 없다

- **증상**: `persist` 하면 `could not execute statement [No value specified for parameter 3]` 과 함께 컬럼이 중복된 SQL 이 찍힌다.

  ```sql
  insert into music_genres (genre_code_id, music_id, genre_code_id, music_id) values (?,?,?,?)
  ```

- **원인**: 네 엔티티가 **같은 컬럼을 두 번 매핑**한다. `@EmbeddedId` 안의 구성 요소가 `@Column(name = "music_id", insertable = false, updatable = false)` 이고, 같은 컬럼을 `@ManyToOne @JoinColumn(name = "music_id")` 가 또 가리킨다. Hibernate 가 INSERT 컬럼 목록에 둘 다 넣으면서 바인딩할 파라미터는 두 개만 만든다. 파생 식별자(derived identity)를 `@MapsId` 없이 흉내 낸 매핑이다.
- **드러나지 않았던 이유**: 애플리케이션 코드에 이 네 엔티티를 **저장하는 곳이 한 군데도 없다.** `MusicServiceImpl`·`MusicSummaryAssembler` 는 조회만 하고, 행은 분석 파이프라인이 SQL 로 직접 넣는다. 관리자 화면의 제네릭 생성이 이 경로를 처음 밟았다.
- **해결(미적용)**: 정석은 `@MapsId` 다. 구성 요소의 `insertable = false, updatable = false` 를 지우고 연관에 `@MapsId("musicId")` 를 붙인다. DDL 은 바뀌지 않는다. **엔티티 매핑 변경이라 팀 합의가 필요해 손대지 않았다.**
- **지금 동작**: 생성만 실패하고(`MUPID_RECORD_WRITE_FAILED`, 400) 조회·수정·삭제는 정상이다. 실패 사유는 서버 로그에 남는다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/music/mapping/entity/MusicGenre.java`, `api/src/main/java/com/osundosun/mupid/api/admin/service/AdminResourceServiceImpl.java`

### 필드 이름이 대문자 한 글자로 끝나면 컬럼명에 밑줄이 안 붙는다

- **증상**: `Mubti` 조회가 `Unknown column 'm1_0.classa' in 'field list'` 로 500 이었다. 실제 컬럼은 `class_a` 다.
- **원인**: 하이버네이트의 기본 네이밍 전략(`CamelCaseToUnderscoresNamingStrategy`)은 **대문자의 앞뒤가 모두 소문자일 때만** 밑줄을 넣는다. `classA` 는 대문자가 **맨 끝**이라 규칙에 걸리지 않아 `class_a` 가 아니라 `classa` 가 된다. `imageFileId`(`image_file_id`)처럼 대문자 뒤에 소문자가 오는 이름은 멀쩡해서, 같은 엔티티 안에서도 일부 컬럼만 깨진다.
- **해결**: 그런 필드는 `@Column(name = "class_a")` 로 **이름을 적는다.** 전략을 바꾸면 다른 30개 엔티티의 컬럼명이 전부 흔들리므로 건드리지 않는다.
- **드러나지 않았던 이유**: `MubtiRepository` 를 쓰는 앱 코드가 없고 행도 0건이었다. `COUNT(*)` 는 컬럼을 안 읽어서 화면 왼쪽의 행 수는 정상으로 보였다. 관리자 화면의 목록 조회가 이 경로를 처음 밟았다.
- **찾는 법**: `grep -rnE "private [A-Za-z.<>]+ [a-z][A-Za-z]*[a-z][A-Z];" src/main/java` — 현재 걸리는 것은 `Mubti` 의 넷뿐이다.
- **함께 드러난 것**: `knowledge/domain/erd.sql` 은 `classa` 라고 적어 실제 DB(`class_a`)와 다르다 → [open-questions B7](../open-questions.md).
- **근거**: `api/src/main/java/com/osundosun/mupid/api/report/entity/Mubti.java`
