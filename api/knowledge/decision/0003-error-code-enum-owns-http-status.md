# 0003. HTTP 상태를 에러 코드 enum 이 들고 있다

**상태** 채택

## 맥락

오류 응답에서 HTTP 상태를 정하는 주체가 필요했다. 흔한 방식은 예외 핸들러가 예외 타입별로 상태를 매핑하는 것이다.

## 결정

`ErrorCode` enum 상수가 자기 HTTP 상태를 직접 들고 있다. 컨트롤러도 예외 핸들러도 상태를 정하지 않는다. 오류를 내는 방법은 **에러 코드를 던지는 것 하나뿐**이다.

## 결과

- 새 오류를 추가할 때 고칠 파일이 enum 하나다. `GlobalExceptionHandler` 는 건드릴 필요가 없다 — 건드리려 한다면 방식을 잘못 이해한 것이다.
- 코드와 상태가 한자리에 있어 표로 뽑아 검증할 수 있다.
- 대신 **enum 에 잘못 박힌 상태가 조용히 계약이 된다.** 실제로 `GlobalErrorCode.NOT_READABLE_MESSAGE` 가 404 로 매핑돼 있었고, 의도인지 실수인지 판단할 근거가 코드·주석·커밋 어디에도 없는 채 Bruno 문서에까지 404 로 기록됐다. **2026-09-18 에 400 으로 정정했다** — 그때까지 이 잘못된 상태가 문서·계약으로 굳어 있었다는 것이 이 위험의 실례다.

## 근거

`api/src/main/java/com/osundosun/mupid/api/common/error/`, [architecture/api-conventions.md](../architecture/api-conventions.md), [failure/auth-and-api.md](../failure/auth-and-api.md)
