# API 응답·에러 규약

엔드포인트를 추가하거나 오류 하나를 새로 내보낼 때 읽는다. 이 저장소에서 API 응답 바디의 모양은 컨트롤러가 정하지 않고 `common/response` 의 래퍼 두 개가 정하며, HTTP 상태 코드는 예외 핸들러가 아니라 `ErrorCode` enum 상수가 들고 있다. 이 두 가지를 모르면 응답 형식이 어긋나거나, 고칠 필요 없는 `GlobalExceptionHandler` 를 건드리게 된다.

## 파일 위치

| 경로 | 역할 |
|---|---|
| `api/src/main/java/com/osundosun/mupid/api/common/response/BaseResponse.java` | 성공 응답 래퍼 |
| `api/src/main/java/com/osundosun/mupid/api/common/response/ErrorResponse.java` | 실패 응답 래퍼 |
| `api/src/main/java/com/osundosun/mupid/api/common/error/ErrorCode.java` | 에러 코드 인터페이스 (계약 전부) |
| `api/src/main/java/com/osundosun/mupid/api/common/error/GlobalErrorCode.java` | 공통 에러 코드 4종 |
| `api/src/main/java/com/osundosun/mupid/api/common/exception/BusinessException.java` | 오류를 던지는 유일한 예외 |
| `api/src/main/java/com/osundosun/mupid/api/common/exception/GlobalExceptionHandler.java` | 프로젝트에서 유일한 `@RestControllerAdvice` |
| `api/src/main/java/com/osundosun/mupid/api/auth/exception/AuthUnauthorizedHandler.java` | 401 (시큐리티 필터 단계) |
| `api/src/main/java/com/osundosun/mupid/api/auth/exception/AuthForbiddenHandler.java` | 403 (시큐리티 필터 단계) |
| `api/docs/` | Bruno 컬렉션. 규약 본문은 `api/docs/opencollection.yml` 의 `docs` 블록 |

도메인별 에러 코드는 `<domain>/error/<Domain>ErrorCode.java` 에 흩어져 있다 — `auth`, `user`(2개), `email`, `storage`.

## 성공 응답 — BaseResponse

```java
public record BaseResponse<T>(String message, T data) { ... }
```

정적 팩터리 3개만 쓴다. 생성자를 직접 부르지 않는다.

| 호출 | 응답 바디 |
|---|---|
| `BaseResponse.message("회원가입 되었습니다.")` | `{"message": "회원가입 되었습니다.", "data": null}` |
| `BaseResponse.of(data)` | `{"message": null, "data": { ... }}` |
| `BaseResponse.of("메시지", data)` | 둘 다 채워짐 |

컨트롤러 반환 타입은 항상 `ResponseEntity<BaseResponse<...>>` 다. 예외는 `UserImageController.getUserImage` 하나뿐이고, 이미지 바이트를 그대로 내보내야 해서 `ResponseEntity<byte[]>` 를 쓴다 (`api/src/main/java/com/osundosun/mupid/api/user/controller/UserImageController.java`).

Actuator 응답(`/api/actuator/health` → `{"status":"UP"}`)은 이 래퍼를 거치지 않는다. 스프링이 직접 만드는 응답이다.

## 실패 응답 — ErrorResponse

```java
public record ErrorResponse<T>(String code, String message, T errorDetails) { ... }
```

```json
{
  "code": "MUPID_USER_NOT_FOUND",
  "message": "요청한 유저를 찾을 수 없습니다.",
  "error_details": null
}
```

- `code` / `message` 는 `ErrorCode` 에서 그대로 온다. 팩터리가 채우므로 호출부가 문자열을 만들지 않는다.
- `errorDetails` 는 **검증 실패일 때만** 채워진다. `ErrorResponse.of(ErrorCode, T)` 를 쓰는 곳은 `GlobalExceptionHandler.invalidArgs` 하나뿐이다.
- 필드명이 `error_details` 로 나가는 것은 `application.yaml` 의 `spring.jackson.property-naming-strategy: SNAKE_CASE` 때문이다. 요청·응답의 모든 필드가 snake_case 로 오간다.

성공 래퍼와 실패 래퍼는 **스키마가 완전히 다르다.** `success` 같은 플래그가 없으므로 클라이언트는 HTTP 상태로 갈래를 판단한 뒤 바디 모양을 고른다.

## 오류를 내는 방법은 하나뿐이다

```java
throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
```

`BusinessException` 은 `RuntimeException` 을 상속하고 `ErrorCode` 하나만 들고 다닌다. 컨트롤러나 서비스가 `ResponseEntity` 로 오류 상태를 직접 만들지 않는다. 던지는 위치는 서비스 계층이 기본이지만 `UserPasswordResetController` 처럼 컨트롤러에서 바로 던지는 곳도 있다.

## 에러 코드 체계

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

설계 의도 세 가지.

- **응답 코드 문자열을 관리하지 않는다.** `getCode()` 가 enum 상수 이름에 `MUPID_` 를 붙여 만든다. 대신 **enum 상수 이름이 곧 공개 API 계약**이므로, 이름을 바꾸면 클라이언트가 깨진다.
- **공통 enum 을 상속하지 않는다.** Java enum 은 상속이 불가능해서 인터페이스로 넓혔다. `GlobalErrorCode` 와 도메인 enum 은 상속 관계가 아니라 같은 인터페이스를 각자 구현하는 병렬 관계다.
- **HTTP 상태를 enum 이 들고 있다.** `GlobalExceptionHandler.businessError` 는 `errorCode.getStatus()` 를 그대로 `ResponseEntity.status(...)` 에 넘긴다. 그래서 오류를 추가할 때 핸들러를 고칠 일이 없다.

### 전체 코드 표

| enum | 상수 | 응답 `code` | HttpStatus |
|---|---|---|---|
| `GlobalErrorCode` | `NOT_FOUND` | `MUPID_NOT_FOUND` | `NOT_FOUND` (404) |
| `GlobalErrorCode` | `NOT_READABLE_MESSAGE` | `MUPID_NOT_READABLE_MESSAGE` | **`NOT_FOUND` (404)** |
| `GlobalErrorCode` | `INVALID_REQUEST_BODY` | `MUPID_INVALID_REQUEST_BODY` | `BAD_REQUEST` (400) |
| `GlobalErrorCode` | `INTERNAL_SERVER_ERROR` | `MUPID_INTERNAL_SERVER_ERROR` | `INTERNAL_SERVER_ERROR` (500) |
| `AuthErrorCode` | `FORBIDDEN` | `MUPID_FORBIDDEN` | `FORBIDDEN` (403) |
| `AuthErrorCode` | `UNAUTHORIZED` | `MUPID_UNAUTHORIZED` | `UNAUTHORIZED` (401) |
| `AuthErrorCode` | `WRONG_EMAIL_OR_PASSWORD` | `MUPID_WRONG_EMAIL_OR_PASSWORD` | `UNAUTHORIZED` (401) |
| `UserErrorCode` | `EMAIL_VERIFICATION_REQUIRED` | `MUPID_EMAIL_VERIFICATION_REQUIRED` | `BAD_REQUEST` (400) |
| `UserErrorCode` | `DUPLICATE_EMAIL` | `MUPID_DUPLICATE_EMAIL` | `BAD_REQUEST` (400) |
| `UserErrorCode` | `USER_NOT_FOUND` | `MUPID_USER_NOT_FOUND` | `NOT_FOUND` (404) |
| `UserImageErrorCode` | `USER_IMAGE_FILE_SIZE_EXCEEDED` | `MUPID_USER_IMAGE_FILE_SIZE_EXCEEDED` | `CONTENT_TOO_LARGE` (413) |
| `UserImageErrorCode` | `INVALID_USER_IMAGE_FILE_FORMAT` | `MUPID_INVALID_USER_IMAGE_FILE_FORMAT` | `BAD_REQUEST` (400) |
| `EmailErrorCode` | `INVALID_VERIFICATION_ID` | `MUPID_INVALID_VERIFICATION_ID` | `NOT_FOUND` (404) |
| `EmailErrorCode` | `VERIFICATION_HAS_EXPIRED` | `MUPID_VERIFICATION_HAS_EXPIRED` | `BAD_REQUEST` (400) |
| `EmailErrorCode` | `INVALID_VERIFICATION_TOKEN` | `MUPID_INVALID_VERIFICATION_TOKEN` | `UNAUTHORIZED` (401) |
| `EmailErrorCode` | `ALREADY_VERIFIED_TOKEN` | `MUPID_ALREADY_VERIFIED_TOKEN` | `BAD_REQUEST` (400) |
| `StorageErrorCode` | `FILE_NOT_FOUND` | `MUPID_FILE_NOT_FOUND` | `NOT_FOUND` (404) |

`NOT_READABLE_MESSAGE` 가 400 이 아니라 404 인 것은 실수처럼 보이지만 이미 문서화된 동작이다 — [함정과 실패 사례](../failure/auth-and-api.md) 참고.

메시지 문구는 각 enum 파일에서 확인한다. 여기 옮겨 적으면 곧 어긋난다.

## 예외 처리 경로는 두 갈래다

### (1) DispatcherServlet 안 — GlobalExceptionHandler

`@RestControllerAdvice` 는 프로젝트 전체에서 이 클래스 하나다.

| 핸들러 | 대상 예외 | 결과 |
|---|---|---|
| `businessError` | `BusinessException` | `errorCode.getStatus()` + `ErrorResponse.of(errorCode)` |
| `notFound` | `NoResourceFoundException` | `GlobalErrorCode.NOT_FOUND` 로 위임 |
| `noRequestBody` | `HttpMessageNotReadableException` | `GlobalErrorCode.NOT_READABLE_MESSAGE` 로 위임 |
| `invalidArgs` | `MethodArgumentNotValidException` | `INVALID_REQUEST_BODY` + 필드 오류 Map |
| `serverError` | `Exception` | 로깅 후 `INTERNAL_SERVER_ERROR` |

`serverError` 가 최종 폴백이다. `BusinessException` 이 아닌 예외(`IllegalArgumentException`, `ExpiredJwtException` 등)는 전부 여기로 떨어져 500 `MUPID_INTERNAL_SERVER_ERROR` 가 된다. 400 으로 응답해야 하는 입력 오류라면 `BusinessException` 으로 바꾸거나 요청 DTO 검증으로 막아야 한다.

### (2) 시큐리티 필터 단계 — auth/exception 의 두 핸들러

Spring Security 필터는 `DispatcherServlet` 보다 앞에서 동작하므로 `@RestControllerAdvice` 가 잡지 못한다. 그래서 `AuthUnauthorizedHandler`(401) / `AuthForbiddenHandler`(403) 가 `HttpServletResponse` 에 직접 JSON 을 쓴다. `SecurityConfig.exceptionHandling` 에 등록되어 있다.

```java
.exceptionHandling(it -> it
    .authenticationEntryPoint(authUnauthorizedHandler) // 401
    .accessDeniedHandler(authForbiddenHandler) // 403
)
```

두 핸들러는 `ErrorResponse.of(...)` 로 같은 바디 모양을 만들지만 `new ObjectMapper()` 를 자체 생성한다. 스프링이 구성한 매퍼가 아니라서 **이 두 경로만 snake_case 가 적용되지 않는다.** 401/403 을 건드릴 때 반드시 알아야 하는 함정이다 — [함정과 실패 사례](../failure/auth-and-api.md).

## 검증 실패 응답

의존성은 `spring-boot-starter-validation`(테스트는 Boot 4 규칙대로 `spring-boot-starter-validation-test`). 사용 형태는 컨트롤러의 `@Valid @RequestBody` 하나뿐이다. `@Validated`·메서드 파라미터 검증·`ConstraintViolationException` 은 코드베이스에 없다.

요청 DTO 는 전부 `<domain>/request/` 의 `record` 이고 컴포넌트에 제약 애너테이션을 붙인다. 쓰이는 애너테이션은 `@Email`, `@NotNull`, `@NotBlank`, `@Size`, `@Min`, `@Pattern` 이고 **전부 `message` 를 한국어로 직접 지정한다.** 기본 메시지에 의존하지 않는 이유는 아래 응답을 보면 분명하다.

```json
{
  "code": "MUPID_INVALID_REQUEST_BODY",
  "message": "데이터가 잘못 입력되었습니다.",
  "error_details": {
    "birthYear": "출생 연도가 입력되지 않았습니다.",
    "emailVerificationId": "이메일 인증이 필요합니다."
  }
}
```

`invalidArgs` 는 `FieldError.getField()` 를 키로, `getDefaultMessage()` 를 값으로 `Map<String, String>` 을 만든다. 즉 **DTO 애너테이션의 `message` 속성이 그대로 사용자에게 보이는 문구**가 된다.

키가 `birthYear` 처럼 camelCase 인 것은 의도가 아니라 Jackson 의 동작이다. `Map` 의 키는 property-naming-strategy 적용 대상이 아니어서, 래퍼 필드 `errorDetails` 는 `error_details` 로 바뀌지만 그 안의 키는 Java 필드명 그대로 남는다. 같은 응답 안에서 표기가 섞인다 — [함정과 실패 사례](../failure/auth-and-api.md).

## 새 에러 코드를 추가하는 절차

1. 대상 도메인의 `<domain>/error/<Domain>ErrorCode.java` 를 연다. 없으면 아래 "새 도메인 error 패키지" 를 먼저 한다.
2. enum 상수를 추가한다. **상수 위에 Javadoc 블록으로 메시지를 한 번 더 적는다** — 모든 `ErrorCode` enum 이 이 형태다.

   ```java
   /**
    * 요청한 유저를 찾을 수 없습니다.
    */
   USER_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 유저를 찾을 수 없습니다."),
   ```

3. 상수 이름은 SCREAMING_SNAKE 로 짓는다. 이 이름이 그대로 `MUPID_<이름>` 응답 코드가 되므로, 클라이언트가 보기에 자연스러운 이름인지 확인한다. **코드 문자열은 어디에도 적지 않는다.**
4. `HttpStatus` 는 enum 선언부에 적는다. `GlobalExceptionHandler` 는 건드리지 않는다.
5. 던진다 — `throw new BusinessException(<Domain>ErrorCode.XXX)`.
6. `api/docs/` 의 해당 요청 `.yml` 에 반영한다 (아래 참고). 이걸 빼면 컬렉션이 코드와 어긋난다.

이미 있는 공통 코드를 재사용해도 된다. `UserImageServiceImpl` 은 기본 프로필 이미지 읽기에 실패했을 때 `GlobalErrorCode.INTERNAL_SERVER_ERROR` 를 그대로 던진다.

## 새 도메인 error 패키지 추가

새 도메인은 파일 **하나**만 추가하면 된다. `BusinessException` 이 구체 enum 을 모르고 `ErrorCode` 만 받으므로 공통 계층은 손대지 않는다.

`api/src/main/java/com/osundosun/mupid/api/<domain>/error/<Domain>ErrorCode.java`:

```java
package com.osundosun.mupid.api.<domain>.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum <Domain>ErrorCode implements ErrorCode {

    /**
     * 사람이 읽을 메시지.
     */
    SOME_ERROR(HttpStatus.BAD_REQUEST, "사람이 읽을 메시지.");

    private final HttpStatus status;
    private final String message;

}
```

- 필드는 `HttpStatus status`, `String message` 두 개. `@Getter @RequiredArgsConstructor` 가 `getStatus()`/`getMessage()` 를 만들고, `name()` 은 enum 이 이미 갖고 있으며, `getCode()` 는 인터페이스 default 다. 그래서 메서드를 직접 구현할 일이 없다.
- 들여쓰기는 스페이스 4칸. 기존 `*ErrorCode.java` 7개 전부 탭이 없다. `api/AGENTS.md` 규정은 "들여쓰기는 기존 파일을 따른다 — Gradle 스크립트와 부트스트랩 클래스는 탭, 새로 쓴 설정 클래스는 스페이스 4칸" 이므로, 저장소 전체가 스페이스인 것은 아니다 (`api/build.gradle` 은 탭).
- 도메인 하나에 enum 이 여럿일 수 있다. `user` 는 `UserErrorCode` 와 `UserImageErrorCode` 로 나눠 두었다.

## Bruno 컬렉션 갱신 의무

`api/AGENTS.md` 가 "엔드포인트를 추가하면 컬렉션에도 추가한다" 고 못박고 있다. 이 저장소에는 별도 API 문서 도구가 없어서 `api/docs/` Bruno 컬렉션이 규약 문서 역할을 겸한다.

| 무엇을 바꿨나 | 어디를 고치나 |
|---|---|
| 엔드포인트 추가 | `api/docs/<폴더>/<한글 이름>.yml` 새로 만들고, `api/docs/<폴더>/folder.yml` 에 표가 있으면 행 추가 |
| 새 오류 코드 | 해당 요청 `.yml` 의 `docs` 안 오류 표에 행 추가 |
| 응답 바디 변경 | 해당 요청 `.yml` 의 `examples` 갱신 |
| 검증 규칙 변경 | 해당 요청 `.yml` 의 요청 본문 표 + `folder.yml` 의 검증 규칙 목록 |
| 응답 규약 자체 변경 | `api/docs/opencollection.yml` 의 `docs` 블록 |

`folder.yml` 의 표 형태는 폴더마다 다르다. 엔드포인트 표를 가진 것은 `User`(`| 엔드포인트 | 동작 | 인증 |`)와 `Email`(`| 엔드포인트 | 동작 |`) 둘뿐이다. `Auth` 는 경로가 `/api/v1/auth` 하나여서 `| 메서드 | 동작 | 인증 |` 로 구분하고, `Actuator` 는 docs 블록에 표가 없으며, `Error` 는 `info` 와 `request` 만 있고 docs 블록 자체가 없다.

요청 `.yml` 한 벌의 구성 (`api/docs/User/회원가입.yml` 기준):

- `info` — `name`, `type: http`, `seq`
- `http` — `method`, `url`(`{{BASE_URL}}` 사용), `headers`, `body`
- `settings` — `encodeUrl`, `timeout`, `followRedirects`, `maxRedirects`, `forwardAuthorizationHeader`. 요청 17개 중 `forwardAuthorizationHeader: false` 는 `api/docs/Error/NotFound.yml` 하나뿐이고, 인증 필요 여부와는 무관하다 (회원가입·로그인 같은 permitAll 요청도 `true`). NotFound 만 토큰 없이 호출해 404 를 확인하려고 꺼 둔 것이다
- `examples` — 성공/실패 각각. 각 예시에 `Content-Type: application/json` 헤더를 넣어 둔다 (없으면 Bruno 가 Raw 로 표시한다)
- `docs` — 마크다운. 선행 조건, 요청 본문 표, **오류 코드 표**

컬렉션 본문은 손으로 관리되므로 코드와 드리프트가 있다. 실제 계약은 항상 `SecurityConfig` 와 `ErrorCode` enum 이 기준이다 — 어긋난 사례는 [함정과 실패 사례](../failure/auth-and-api.md) 에 정리돼 있다.

## 이 영역의 함정

상세는 [함정과 실패 사례](../failure/auth-and-api.md) 에 있다. 여기서는 목록만.

- 401/403 응답만 `error_details` 가 아니라 `errorDetails` 로 나간다 (자체 `ObjectMapper`).
- `error_details` 의 **키**는 snake_case 로 변환되지 않는다 (Jackson 의 Map 키 동작).
- `NOT_READABLE_MESSAGE` 가 400 이 아니라 404 로 매핑돼 있다.
- permitAll 이 아닌 경로는 존재하지 않아도 404 가 아니라 401 이 먼저 온다.
- `BusinessException` 이 아닌 예외는 전부 500 이 된다 (`valueOf` 실패, 만료 토큰 파싱 등).
- 프로필 이미지 413 은 애플리케이션 검사만 있고 multipart 제한 설정이 없다.
- 최종 폴백 핸들러가 스택트레이스를 로거가 아니라 stdout 으로 흘린다.
- `AuthErrorCode.FORBIDDEN`(403) 은 현재 도달하는 경로가 없다.
- `api/docs` 와 `api/README.md` 의 일부 서술이 코드와 어긋나 있다.

## 검증

공통 계층을 고친 뒤 컴파일 확인은 호스트가 아니라 컨테이너에서 한다 (`api/AGENTS.md`).

```bash
docker compose -f compose.prod.yml up -d --build
curl http://localhost:8080/api/actuator/health   # {"status":"UP"}
docker compose -f compose.prod.yml down -v
```

응답 형식은 `api/docs/` Bruno 컬렉션으로 수동 확인한다. `api/src/test/java` 에는 컨텍스트 로딩 테스트 하나뿐이라 응답 규약을 지켜 주는 자동 테스트가 없다.
