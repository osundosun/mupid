# 0002. 모든 환경변수에 `MUPID_` 접두사를 붙인다

**상태** 채택 · 강한 규칙

## 맥락

설정 값이 `application.yaml`, `.env`, compose 세 곳을 거쳐 주입된다. 스프링 관례 변수(`SPRING_DATA_REDIS_HOST` 등)를 그대로 쓰면 어느 값이 이 프로젝트 것인지, 어디서 덮어써졌는지 추적하기 어렵다.

## 결정

- `application.yaml` 은 값을 직접 적지 않고 `${MUPID_...}` 로만 참조한다.
- 실제 값은 `.env` 에 두고 `spring.config.import: optional:file:.env[.properties]` 로 주입한다.
- compose 에서 덮어쓸 때도 `MUPID_REDIS_HOST` 처럼 프로젝트 변수명을 쓴다. 스프링 관례 변수로 우회하지 않는다.
- 새 설정을 추가하면 `.env.example` 도 같이 갱신한다 (값은 비우되 키와 주석 섹션은 유지).
- 커스텀 프로퍼티는 `META-INF/additional-spring-configuration-metadata.json` 에 메타데이터를 넣는다.

## 결과

- 어떤 값이 프로젝트 설정인지 이름만 보고 안다. grep 으로 전수 조사가 가능하다.
- 대신 **스프링 관례를 아는 사람이 관례대로 했다가 값이 안 잡히는** 실패가 생긴다. 이게 이 규칙의 주된 비용이다.
- 접두사가 일관되게 지켜지지 않는 지점이 일부 있다. 예외 목록은 [architecture/configuration.md](../architecture/configuration.md) 참고.

## 근거

`api/src/main/resources/application.yaml`, `api/.env.example`, [failure/config-and-env.md](../failure/config-and-env.md)
