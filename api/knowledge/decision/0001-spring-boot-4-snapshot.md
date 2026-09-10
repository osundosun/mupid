# 0001. Spring Boot 4 SNAPSHOT 을 쓴다

**상태** 채택 · 되돌리기 어려움

## 맥락

프로젝트 시작 시점에 Spring Boot 4 는 정식 릴리스 전이었다. 3.x 안정 버전과 4.x SNAPSHOT 중 하나를 골라야 했다.

## 결정

`org.springframework.boot` **4.1.2-SNAPSHOT** 을 쓴다. 이를 위해 `repo.spring.io/snapshot` 저장소를 `build.gradle` 과 `settings.gradle` 의 `pluginManagement` **양쪽에** 등록한다.

## 결과

받아들인 비용:

- **스타터 이름이 3.x 와 다르다.** `spring-boot-starter-web` 이 아니라 `spring-boot-starter-webmvc`, 테스트는 `spring-boot-starter-test` 하나가 아니라 `spring-boot-starter-<모듈>-test` 로 모듈별로 쪼개져 있다. 3.x 문서·블로그·LLM 답변을 그대로 옮기면 빌드가 깨진다.
- 스냅샷 저장소 두 곳 중 하나라도 지우면 빌드가 깨진다.
- 버전이 언제든 바뀔 수 있어 "어제는 됐는데 오늘 안 되는" 상황이 가능하다.
- 프레임워크 기본 동작이 3.x 와 다를 수 있어, 설정을 비워 뒀을 때의 기본값을 3.x 기준으로 추정하면 안 된다.

## 근거

`api/build.gradle`, `api/settings.gradle`, [failure/build-and-spring.md](../failure/build-and-spring.md)
