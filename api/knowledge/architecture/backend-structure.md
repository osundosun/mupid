# 백엔드 구조와 코드 컨벤션

`api/` 의 Spring Boot 애플리케이션에 새 도메인이나 엔드포인트를 추가할 때, 또는 남이 만든 코드를 찾아 들어갈 때 읽는다. "무엇을 어느 패키지에 만드는가", "클래스 이름과 애너테이션을 어떻게 붙이는가", "레이어 사이에 무엇을 주고받는가"에 답한다. 빌드·환경변수·Docker 규칙은 [api/AGENTS.md](../../AGENTS.md) 에 있고, 이 구조를 건드릴 때 밟기 쉬운 지뢰는 [함정과 실패 사례](../failure/build-and-spring.md) 에 있다.

## 패키지 구조

베이스 패키지는 `com.osundosun.mupid.api` 다. **도메인을 먼저 나누고 그 안에서 레이어를 연다.** `controller/`, `service/` 를 최상위로 올리지 않는다.

```
api/src/main/java/com/osundosun/mupid/api/     # 88 파일
├── ApiApplication.java                         # 부트스트랩
├── analytics/   config, properties                                 (2)
├── auth/        controller, dto, error, exception, filter,
│                provider, request, response, service               (13)
├── cache/       enumerate, service                                 (3)
├── common/      config, error, exception, gmail, response          (12)
├── email/       controller, entity, enumerate, error, repository,
│                request, response, service                         (13)
├── event/       config, controller, dto, enumerate, error,
│                properties, request, response, service             (15)
├── storage/     config, dto, entity, error, properties,
│                repository, service                                (8)
├── user/        controller, entity, enumerate, error, repository,
│                request, response, service                         (20)
└── utils/       MultipartFileUtils.java                            (1)

api/src/test/java/com/osundosun/mupid/api/ApiApplicationTests.java   (1)
```

한 도메인의 변경이 한 디렉터리 안에서 끝나고, 도메인 간 결합은 import 로 드러난다. `AuthServiceImpl` → `UserService`·`CacheService`, `UserImageServiceImpl` → `StorageService`, `UserServiceImpl` → `EmailService` 처럼 **도메인을 넘을 때는 상대 도메인의 서비스 인터페이스만 호출한다.** 남의 리포지터리를 직접 주입하는 곳은 `auth/provider/AuthProvider`(`UserRepository`) 한 군데뿐이다.

## 도메인 패키지 내부 구성

레이어 이름은 도메인마다 재사용한다. **필요 없는 레이어는 만들지 않는다** — `cache` 는 `enumerate`+`service` 뿐이고, `analytics` 는 `config`+`properties` 뿐이며, `auth` 는 엔티티도 리포지터리도 없다.

| 레이어 | 담는 것 | 클래스 이름 |
|---|---|---|
| `controller` | `@RestController`. HTTP 진입점 | `<도메인><관심사>Controller` |
| `service` | 인터페이스 + 구현체 쌍 | `<도메인>Service` / `<도메인>ServiceImpl` |
| `repository` | Spring Data JPA 인터페이스 | `<엔티티>Repository` |
| `entity` | `@Entity` | 단수 명사 |
| `request` | 요청 바디 record + Bean Validation | `<도메인><동작>Request` |
| `response` | 응답 바디 record | `<도메인><동작>Response` |
| `dto` | HTTP 계약이 아닌 레이어 간 전달값 | 자유 (`AuthLoginResult`, `StorageFileData`) |
| `enumerate` | 도메인 enum | 단수 명사 |
| `error` | `ErrorCode` 구현 enum | `<도메인>ErrorCode` |
| `exception` | 예외 · 예외 처리기 | `<도메인>Exception`, `<역할>Handler` |
| `config` | `@Configuration` | `<기술>Config` |
| `properties` | `@ConfigurationProperties` record | `<기술>Properties` |
| `filter` / `provider` | 서블릿 필터 / 기술 어댑터 | `<도메인>Filter`, `<기술>Provider` |

도메인별 실제 구성:

| 도메인 | 특징 |
|---|---|
| `auth` | 엔티티·리포지터리 없음. 인증 상태는 `user` 엔티티와 Redis(`cache`)에 있다. `provider` 에 JWT 발급(`JwtProvider`)·검증(`AuthProvider`), `filter` 에 `AuthFilter`, `exception` 에 커스텀 예외가 아니라 Security 진입점 핸들러 2개(`AuthUnauthorizedHandler`, `AuthForbiddenHandler`) |
| `user` | 가장 큰 도메인. 컨트롤러 3개(`UserController` `/api/v1/user`, `UserImageController` `/api/v1/user/image`, `UserPasswordResetController` `/api/v1/user/reset`), 서비스 2쌍(`UserService`, `UserImageService`) |
| `email` | 인증 메일. 서비스 2쌍 — `EmailService`(인증 발급·검증), `EmailTemplateService`(Thymeleaf 렌더) |
| `storage` | S3 호환(SeaweedFS). `ObjectStorageFile` 로 메타데이터를 MySQL 에 남기고 바이트는 S3 에 둔다 |
| `cache` | Redis 래퍼. `CacheKind` enum 이 키 접두사(`block:token`)를 들고, `CacheServiceImpl.buildKey` 가 `"%s:%s".formatted(kind.getRedisKey(), key)` 로 조립 |
| `analytics` | ClickHouse. 아직 `config`+`properties` 뿐이고 컨트롤러·서비스가 없다 |
| `event` | 청취 이벤트 수집. 엔티티·리포지터리 없음 — MySQL 을 건드리지 않고 Redis(멱등)와 Kafka(발행)만 쓴다. `enumerate` 5개가 이벤트 값 집합을 들고 있고, `dto/ListenEventMessage` 가 Kafka 로 나가는 JSON 이다 |
| `common` | 여러 도메인이 공유하는 것만. `response`(`BaseResponse`, `ErrorResponse`), `error`(`ErrorCode`, `GlobalErrorCode`), `exception`(`BusinessException`, `GlobalExceptionHandler`), `config`(`SecurityConfig`, `JdbcTemplateConfig`), `gmail` |
| `utils` | `MultipartFileUtils` 하나. static 메서드만 있는 public 클래스 |

`common/gmail` 은 유일하게 레이어로 쪼개지 않은 패키지다. `GmailConfig`·`GmailProperties`·`GmailProvider`·`GmailException` 이 한 디렉터리에 평평하게 있다. 같은 성격의 S3·ClickHouse 는 `config`+`properties` 로 쪼개져 있으므로, 이 배치를 다른 외부 연동의 본보기로 삼기 전에 팀에 확인할 것.

## 새 기능을 추가하는 순서

### 새 도메인을 만들 때

1. `com.osundosun.mupid.api.<도메인>` 패키지를 만든다.
2. `entity/<Entity>.java` — Lombok 5종 세트 + `@EntityListeners(AuditingEntityListener.class)` + 복수형 스네이크 테이블명.
3. `repository/<Entity>Repository.java` — `extends JpaRepository<Entity, Integer>`.
4. `error/<도메인>ErrorCode.java` — `implements ErrorCode` enum.
5. `service/<도메인>Service.java` + `service/<도메인>ServiceImpl.java` — 인터페이스에는 애너테이션을 붙이지 않는다.
6. `request/`, `response/` record 를 만들고 `controller/<도메인>Controller.java` 에서 `@RequestMapping("/api/v1/<도메인>")` 으로 묶는다.
7. `permitAll` 이 필요하면 `common/config/SecurityConfig` 의 `authorizeHttpRequests` 에 경로를 추가한다. 기본값은 `anyRequest().authenticated()` 다.
8. `api/docs/` Bruno 컬렉션에 요청 파일을 추가한다 (AGENTS.md 92행). 기존 요청 파일은 `docs` 본문에 요청 본문·응답·오류 코드 표를 적어 두는 관행이 있다 (`docs/Auth/로그인.yml`).

MySQL 스키마의 기준은 엔티티 매핑이지만 `ddl-auto` 는 `none` 이다 ([decision/0011](../decision/0011-jpa-manages-schema.md)). 엔티티를 추가·변경하면 **DDL 을 직접 실행해야 한다** — [persistence.md](persistence.md) 참고.

### 기존 도메인에 엔드포인트를 추가할 때

1. 요청 바디가 있으면 `request/` 에 record 를 만들고 제약 애너테이션에 한국어 `message` 를 단다.
2. 서비스 인터페이스에 메서드를 선언하고 `*Impl` 에 구현한다. 파라미터로 request record 를 그대로 받고 이름은 `dto` 로 쓴다.
3. 응답 바디가 있으면 `response/` 에 record 를 만든다. **서비스가 아니라 컨트롤러가 조립한다.**
4. 컨트롤러 메서드는 `ResponseEntity<BaseResponse<T>>` 를 반환한다.
5. 새 실패 상황이 있으면 `error/` enum 에 상수를 추가하고 `throw new BusinessException(...)` 으로 던진다. 핸들러는 고치지 않는다.

관심사가 커지면 컨트롤러 클래스를 쪼갠다. `user` 가 `UserController` / `UserImageController` / `UserPasswordResetController` 로 나뉜 것이 그 예다.

## 컨트롤러

```java
@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    public ResponseEntity<BaseResponse<Void>> createUser(
        @Valid @RequestBody UserCreateRequest request
    ) {
        userService.createUser(request);
        return ResponseEntity.ok(
            BaseResponse.message("회원가입 되었습니다.")
        );
    }
}
```

- URL 은 클래스 `@RequestMapping` 에 두고 CRUD 는 HTTP 메서드로 구분한다. 핸들러 15개 중 13개가 경로 없이 `@PostMapping` / `@GetMapping` 만 붙는다. 메서드 경로를 적는 건 경로 변수를 받을 때뿐이다 — `UserImageController` 의 `@GetMapping("/{userId}")`, `EmailVerificationController` 의 `@GetMapping("/{verificationId}")` 둘뿐이다.
- 클래스 경로는 `/api/v1/auth`, `/api/v1/user`, `/api/v1/user/image`, `/api/v1/user/reset`, `/api/v1/mail/verification`, `/api/v1/events` 6개다. `email` 도메인만 패키지 이름과 다른 `mail/verification` 을 쓰고, `event` 도메인만 복수형(`events`) 경로를 쓴다 — 한 요청이 이벤트 여러 건을 담기 때문이다.
- 반환 타입은 항상 `ResponseEntity<BaseResponse<T>>`. 예외는 파일 바이트를 내려주는 `UserImageController.getUserImage` 의 `ResponseEntity<byte[]>` 하나뿐이다.
- 인증된 사용자는 `Authentication auth` 파라미터로 받고 `(User)auth.getPrincipal()` 로 캐스팅한다. `@AuthenticationPrincipal` 이나 ArgumentResolver 는 쓰지 않는다. principal 은 `AuthProvider.auth` 가 넣은 `User` 엔티티다.
- 응답 record 는 컨트롤러가 직접 조립한다. `UserController.getUserInfo` 가 `User` 에서 9개 필드를 꺼내 `UserGetInfoResponse` 를 만든다.
- 파라미터는 한 줄에 하나씩, 여는 괄호 뒤 줄바꿈 + 4칸 들여쓰기 + 닫는 괄호를 별도 줄에 둔다.
- 컨트롤러에 `@Transactional` 을 붙이지 않는다.

## 서비스와 트랜잭션

서비스 7개(`AuthService`, `UserService`, `UserImageService`, `EmailService`, `EmailTemplateService`, `StorageService`, `CacheService`)가 예외 없이 **인터페이스 + `*Impl`** 쌍이다. 인터페이스는 순수 인터페이스로 두고 애너테이션은 전부 구현체에 붙인다.

```java
@Service
@Transactional
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
```

트랜잭션 경계는 **서비스 구현체 클래스 레벨**이다 (`org.springframework.transaction.annotation.Transactional`).

| 구현체 | 클래스 `@Transactional` | 비고 |
|---|---|---|
| `UserServiceImpl` | O | |
| `UserImageServiceImpl` | O | `getUserImageData` 만 `readOnly = true` |
| `EmailServiceImpl` | O | `isValid` 만 `readOnly = true` |
| `StorageServiceImpl` | O | `getFileData`·`getFile`·`isFileExists` 가 `readOnly = false` — 새 읽기 메서드는 `readOnly = true` 를 쓸 것 |
| `AuthServiceImpl` | X | Redis + JWT 만 다룬다 |
| `CacheServiceImpl` | X | |
| `EmailTemplateServiceImpl` | X | |

**수정은 `save()` 없이 더티 체킹에 의존한다.** `UserServiceImpl.updateUser` 는 setter 만 호출하고 `repository.save` 를 부르지 않는다. `UserImageServiceImpl` 의 `user.setImageFile(...)`, `EmailServiceImpl.validateToken` 의 `verification.setVerifiedAt(now)`, `StorageServiceImpl.uploadFile` 의 메타데이터 갱신도 같다. 클래스 레벨 `@Transactional` 은 장식이 아니라 동작 조건이므로 떼면 예외 없이 저장만 사라진다 → [함정과 실패 사례](../failure/build-and-spring.md).

## 엔티티 · 리포지터리 · enum

엔티티는 Lombok 5종 세트를 고정으로 쓴다.

```java
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "users")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    /** 유저 번호 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer userId;
```

- 테이블명은 복수형 스네이크: `users`, `email_verifications`, `object_storage_files`.
- PK 타입은 `Integer`, 전략은 `IDENTITY`.
- 감사 필드는 `@CreatedDate` + `@Column(nullable = false, updatable = false)`, `@LastModifiedDate` + `@Column(nullable = false)` 다. `updatable = false` 는 생성 일시에만 붙인다 — 수정 일시에 따라 붙이면 UPDATE 문에서 빠져 갱신되지 않는다. `ApiApplication` 의 `@EnableJpaAuditing` 이 동작시킨다.
- enum 필드는 `@Enumerated(EnumType.STRING)`.
- 기본값이 필요하면 `@Builder.Default` (`ObjectStorageFile.isDeleted`).
- **필드마다 한국어 Javadoc** 을 붙인다.

리포지터리는 `JpaRepository` 를 상속한 인터페이스 하나뿐이고 쿼리 메서드 이름으로 조건을 표현한다 — `findByUserIdAndStatusNot`, `findByEmailAndStatusNot`, `findByStorageObjectKeyAndIsDeletedFalse`. `@Query` 를 쓴 곳은 없다. 조회는 `UserStatus.WITHDRAW` 를 제외하지만 `UserServiceImpl.deleteUser` 는 `repository.delete(user)` 로 실제 행을 지운다. 삭제 정책을 바꿀 때 이 두 가지가 어긋나 있다는 점을 먼저 확인할 것.

enum 은 `enumerate` 패키지에 두고 한국어 `displayName` 필드를 갖는다.

```java
@Getter
@AllArgsConstructor
public enum UserStatus {

    PENDING("인증 필요"),
    ACTIVE("활성화"),
    WITHDRAW("삭제된 사용자");

    private final String displayName;
}
```

`UserRole` 은 `level`(Integer) 과 `displayName` 두 필드, `EmailVerificationPurpose` 는 `displayName`·`mailTitle`·`mailTemplate`·`mailUrl` 네 필드를 갖는다. 메일 템플릿 경로 문자열(`"mail/email-verification"`)을 이 enum 이 관리한다. 도메인 enum 은 `@AllArgsConstructor`, `CacheKind` 와 `*ErrorCode` 는 `@RequiredArgsConstructor` 를 쓴다 — 둘 다 `final` 필드만 있어 결과는 같다.

## request · response · dto

세 패키지 다 `record` 이고 본문은 비어 있다. 기준은 **"요청 바디면 request, 응답 바디면 response, 둘 다 아니면 dto"** 다.

**request** — 요청 바디 바인딩 + Bean Validation 전용. 검증 애너테이션(`@Email`, `@NotNull`, `@NotBlank`, `@Size`, `@Min`, `@Pattern`)은 여기에만 있고, `message` 를 전부 한국어로 직접 지정한다. 기본 메시지에 의존하지 않는다 — 이 문자열이 그대로 오류 응답의 `error_details` 값이 된다. 그리고 **서비스 파라미터로 그대로 넘어간다.**

```java
User createUser(UserCreateRequest dto);
User updateUser(int userId, UserUpdateRequest dto);
```

별도 커맨드 객체를 만들지 않는다. `UserPasswordResetController.submitResetCode` 는 컨트롤러에서 `new UserUpdateRequest(request.password(), null, null, null)` 을 조립해 서비스에 넘긴다.

**response** — 응답 바디 전용. **서비스 시그니처에 `*Response` 타입이 들어가지 않는다.** 도메인 `response` 패키지를 import 하는 건 컨트롤러뿐이다 (`AuthController`, `EmailVerificationController`, `UserController`, `UserPasswordResetController`). `common/response/ErrorResponse` 만 예외로 예외 처리기 3개(`GlobalExceptionHandler`, `AuthUnauthorizedHandler`, `AuthForbiddenHandler`)가 import 한다. 이름은 `*Response` 접미사를 쓰되 `email/response/EmailCheckAddressVerification` 만 접미사가 빠져 있다.

**dto** — HTTP 계약이 아닌 레이어 간 전달값. 두 개뿐이다.

- `auth/dto/AuthLoginResult` — 서비스가 액세스 토큰·리프레시 토큰·`User` 를 한 번에 돌려주기 위한 다중 반환값. 컨트롤러가 이걸 쿠키와 `AuthLoginResponse` 로 쪼갠다.
- `storage/dto/StorageFileData` — 파일명 + `MediaType` + 바이트 묶음. record 지만 `getEncodedFilename()` 같은 동작을 갖는다.

클라이언트와 주고받는 JSON 은 snake_case 다. 자바에서는 camelCase 로 쓰고 `application.yaml` 의 `spring.jackson.property-naming-strategy: SNAKE_CASE` 에 맡긴다 (`accessToken` → `access_token`). 필드에 `@JsonProperty` 를 붙인 곳은 없다.

## 에러 코드와 예외

`common/error/ErrorCode` 인터페이스가 계약 전부다.

```java
public interface ErrorCode {

    HttpStatus getStatus();

    String name();

    default String getCode() {
        return "MUPID_%s".formatted(name());
    }

    String getMessage();
}
```

`name()` 은 enum 이 이미 갖고 있으므로 구현체는 enum 이어야 한다. 응답 코드는 `MUPID_<enum 상수 이름>` 으로 자동 생성되니 코드 문자열을 손으로 관리하지 않는다. 뒤집으면 **enum 상수 이름이 곧 공개 API 계약**이라 이름을 바꾸면 클라이언트가 깨진다.

```java
@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {

    /** 이메일 인증이 필요합니다. */
    EMAIL_VERIFICATION_REQUIRED(HttpStatus.BAD_REQUEST, "이메일 인증이 필요합니다."),
    ...

    private final HttpStatus status;
    private final String message;
}
```

- 필드는 `HttpStatus status`, `String message` 두 개. 상수마다 한국어 Javadoc 을 붙인다.
- 오류는 항상 `throw new BusinessException(<도메인>ErrorCode.XXX)` 로 낸다. 컨트롤러·서비스가 `ResponseEntity` 로 오류를 직접 만들지 않는다.
- HTTP 상태는 enum 이 들고 있고 `GlobalExceptionHandler.businessError` 가 `errorCode.getStatus()` 를 그대로 쓴다. 새 오류를 추가할 때 핸들러를 고칠 일이 없다.
- 공통 코드가 맞으면 그대로 쓴다 — `UserImageServiceImpl` 은 S3 실패 시 `GlobalErrorCode.INTERNAL_SERVER_ERROR` 를 던진다.

성공 응답은 전부 `BaseResponse<T>(message, data)` 로 감싼다. 정적 팩터리 3종이 용도를 강제한다.

| 팩터리 | 결과 |
|---|---|
| `BaseResponse.message(msg)` | `{"message": …, "data": null}` |
| `BaseResponse.of(data)` | `{"message": null, "data": …}` |
| `BaseResponse.of(msg, data)` | 둘 다 |

실패 응답은 `ErrorResponse<T>(code, message, errorDetails)` 로 스키마가 아예 다르다. `errorDetails` 는 Bean Validation 실패일 때만 채워진다.

`@RestControllerAdvice` 는 `common/exception/GlobalExceptionHandler` 하나뿐이다. Security 필터는 `DispatcherServlet` 보다 앞이라 advice 가 잡지 못하므로, `auth/exception` 의 두 핸들러가 같은 `ErrorResponse` 를 직접 직렬화해 형태를 맞춘다. 이 우회 때문에 401/403 만 필드 표기가 다르다 → [함정과 실패 사례](../failure/build-and-spring.md).

## 주입과 Lombok

**`@RequiredArgsConstructor` + `private final`** 이 기본이다. 컨트롤러·서비스·필터·설정 클래스가 모두 같다. `@Autowired` 는 소스 어디에도 없다.

예외는 `@Value` 를 생성자 파라미터로 받아야 할 때뿐이고, `JwtProvider` 와 `AuthProvider` 두 클래스만 명시적 생성자를 직접 쓴다 (둘 다 `@Value("${jwt.secret}")` 을 받아 `SecretKey` 로 파생시킨다). 필드 `@Value` 는 `EmailServiceImpl.mupidClientWebsite` 한 군데뿐이다.

Lombok 사용 패턴:

| 대상 | 애너테이션 |
|---|---|
| 엔티티 | `@Getter @Setter @Builder @AllArgsConstructor @NoArgsConstructor(access = AccessLevel.PROTECTED)` |
| 서비스 구현체 · 컨트롤러 · 필터 · 설정 | `@RequiredArgsConstructor` |
| `*ErrorCode` enum | `@Getter @RequiredArgsConstructor` |
| 도메인 enum | `@Getter @AllArgsConstructor` |
| 로깅이 필요한 클래스 | `@Slf4j` — `GlobalExceptionHandler`, `UserImageServiceImpl`, `GmailProvider` 셋뿐. 로거를 직접 선언한 곳은 없다 |
| record | Lombok 을 쓰지 않는다 |

`api/lombok.config` 는 13줄이다. 1~11행은 이유를 설명하는 `#` 주석이고, 설정은 아래 두 줄(12~13행)뿐이다. **지우거나 옮기면 안 된다.**

```
config.stopBubbling = true
lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier
```

이게 없으면 `@RequiredArgsConstructor` 가 만든 생성자 파라미터에 `@Qualifier` 가 복사되지 않아 `@Primary` 인 MySQL `JdbcTemplate` 이 조용히 주입된다 → [함정과 실패 사례](../failure/build-and-spring.md).

## 설정 클래스와 프로퍼티

**기술 통합은 그 기술을 쓰는 도메인 아래** `config` + `properties` 로 둔다 — `storage/config/StorageConfig`(S3), `analytics/config/ClickHouseConfig`(ClickHouse). 여러 도메인이 공유하는 것만 `common/config` 로 올린다.

| 클래스 | 역할 |
|---|---|
| `common/config/SecurityConfig` | `@EnableWebSecurity`. `PasswordEncoder`(BCrypt), `SecurityFilterChain`. CSRF·httpBasic·formLogin 비활성, 세션 `STATELESS`, permitAll 경로 목록, `AuthFilter` 를 `UsernamePasswordAuthenticationFilter` 앞에 삽입, 401/403 핸들러 등록 |
| `common/config/JdbcTemplateConfig` | MySQL `JdbcTemplate` 을 `@Bean @Primary` 로 되살린다. `clickHouseJdbcTemplate` 이 등록되면 `JdbcTemplateAutoConfiguration` 이 물러나기 때문 |
| `storage/config/StorageConfig` | `S3Client` 빈. `endpoint` 가 있으면 `endpointOverride` + `pathStyleAccessEnabled` |
| `analytics/config/ClickHouseConfig` | `clickHouseJdbcTemplate` 만 빈으로 노출. 커넥션 풀은 필드로 들고 `DisposableBean` 으로 닫는다. 클래스 상단 Javadoc 에 이유가 적혀 있으니 리팩터링 전에 읽을 것 |
| `common/gmail/GmailConfig` | `Gmail` 클라이언트 빈 |

프로퍼티는 전부 `record` + `@ConfigurationProperties(prefix = …)` 다.

```java
@ConfigurationProperties(prefix="aws.s3")
public record StorageProperties(
    String endpoint, String region, String accessKey, String secretKey, String bucketName
) {}
```

`ApiApplication` 의 `@ConfigurationPropertiesScan` 이 등록을 대신하므로 `@Component` 나 `@EnableConfigurationProperties` 를 붙이지 않는다. 기본값은 `@DefaultValue` 로 준다 (`ClickHouseProperties` 의 `@DefaultValue("8")`, `@DefaultValue("3s")`). 현재 prefix 는 `aws.s3`, `clickhouse`, `gmail` 세 개다.

`application.yaml` 에서 접속 정보와 비밀은 `${MUPID_...}` 로 참조하고, 환경과 무관한 값은 리터럴로 적는다 — `region: "SeaweedFS"`, `timeout: 3000ms`, `max-pool-size: 8`, `min-idle: 1`, `connection-timeout: 3s`, `validation-timeout: 3s`. 기본값을 함께 적는 것도 있다 (`${MUPID_PORT:8800}`, `${MUPID_CLIENT_WEBSITE:}`). 새 설정을 추가할 때의 `.env.example` · 메타데이터 갱신 규칙은 [api/AGENTS.md](../../AGENTS.md) 에 있다.

부트스트랩 클래스는 세 애너테이션이 전부다.

```java
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableJpaAuditing
public class ApiApplication { ... }
```

리소스 배치:

```
src/main/resources/
├── application.yaml
├── img/default-user-profile.png                # UserImageServiceImpl 이 ClassPathResource 로 읽는다
├── META-INF/additional-spring-configuration-metadata.json
└── templates/mail/
    ├── email-verification.html
    ├── password-reset.html
    └── fragments/{layout,button,code-box}.html # 공용 조각
```

메일 템플릿은 `templates/mail/` 아래, 공용 조각은 `templates/mail/fragments/` 프래그먼트로 뺀다. 렌더는 `EmailTemplateService.buildHtml(templateName, variables)` 를 통해서만 한다.

## 포맷과 파일 규칙

- **새 자바 파일은 스페이스 4칸.** 선행 탭이 있는 자바 파일은 `ApiApplication.java` 와 `ApiApplicationTests.java`(둘 다 Spring Initializr 생성물) 둘뿐이고, 나머지 72개는 전부 스페이스다. Gradle 스크립트(`build.gradle`, `settings.gradle`)는 탭.
- import 순서는 `java.*` → `javax.*` → `org.*` → `com.*` → 나머지(`jakarta.*`, `io.jsonwebtoken.*`, `lombok.*`, `software.amazon.*`) 로 전 파일 일관적이다. 와일드카드 import 는 없다.
- record 는 컴포넌트 사이에 빈 줄을 넣고 본문을 비워 둔다.
- 주석은 한국어. 엔티티 필드와 에러 enum 상수에는 Javadoc(`/** 유저 번호 */`), 로직 설명에는 `//` 또는 `/* … */`.
- 자바 소스는 작업 트리에서 CRLF 다. `.editorconfig` 가 없고 `.gitattributes` 도 `.java` 를 다루지 않으므로, 에디터가 LF 로 저장하면 파일 전체가 diff 로 뜬다.
- `ResponseEntity.ok(` 다음 줄 들여쓰기는 12/16/20칸으로 제각각이다. 인자를 줄바꿈할 때는 12칸, `.header`/`.body` 체이닝은 `AuthController` 가 16칸, `UserImageController.getUserImage` 가 20칸이다. 통일된 규칙이 없으니 주변 코드를 따른다.

## 함정

이 영역에서 밟기 쉬운 것들이다. 상세는 [함정과 실패 사례](../failure/build-and-spring.md) 를 볼 것.

- `@RequiredArgsConstructor` 가 `@Qualifier` 를 먹어 엉뚱한 `JdbcTemplate` 이 주입된다 (`api/lombok.config` 가 막고 있다).
- ClickHouse `JdbcTemplate` 을 등록하면 MySQL `JdbcTemplate` 자동 설정이 사라진다. ClickHouse 풀을 `DataSource` 빈으로 올리면 헬스체크가 서비스 전체를 내린다.
- 수정 로직에 `save()` 가 없다. `@Transactional` 을 떼면 조용히 저장이 안 된다.
- 401/403 응답만 필드명이 camelCase 로 나가고, `error_details` 의 키는 snake_case 로 변환되지 않는다.
- `GlobalErrorCode.NOT_READABLE_MESSAGE` 가 400 이 아니라 404 로 매핑돼 있다.
- permitAll 이 아닌 경로는 존재하지 않아도 404 가 아니라 401 이 먼저 온다.
- `api/README.md` 의 디렉터리 구조 절은 초기 상태 그대로라 도메인 패키지가 없다. 구조 파악은 소스 트리로 한다.

## 확인해 둘 것

- 테스트는 `ApiApplicationTests.contextLoads` 하나뿐이다. 위 규약을 지켜 주는 자동 검증 장치가 없으므로 변경 후에는 [api/AGENTS.md](../../AGENTS.md) 의 컨테이너 기동 + 헬스체크 절차로 확인한다. 호스트에 JDK 21 이 없어 `./gradlew` 는 조용히 실패한다.
- `build.gradle` 에 `spring-boot-starter-batch` · `spring-boot-starter-batch-jdbc` 가 있지만 배치 코드(`@EnableBatchProcessing`, Job, Step, Tasklet)는 아직 없다. 배치를 처음 붙이는 사람이 패키지 배치를 정하게 된다.
