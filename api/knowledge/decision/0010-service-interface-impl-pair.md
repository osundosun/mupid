# 0010. 서비스를 인터페이스 + Impl 쌍으로 만든다

**상태** 채택 · **재검토 대상**

## 맥락

서비스 계층을 인터페이스로 열어 둘지, 클래스 하나로 둘지 정해야 했다.

## 결정

모든 서비스를 `XxxService` 인터페이스 + `XxxServiceImpl` 구현 쌍으로 만든다. 현재 7개 서비스 전부가 예외 없이 이 형태다.

## 결과

- 형태가 일관돼서 어디에 무엇이 있는지 예측 가능하다.
- 대신 **구현체가 하나뿐인 인터페이스가 7개**다. 테스트도 `ApiApplicationTests.contextLoads` 하나뿐이라, 인터페이스가 실제로 어떤 이득(교체·모킹)을 주는지 코드에서 확인되지 않는다.
- 파일 수가 두 배가 되고, 메서드를 추가할 때마다 두 곳을 고쳐야 한다.

## 재검토할 것

이 규칙을 계속 강제할지 정해지지 않았다. 테스트가 붙기 시작하면 판단 근거가 생긴다. 그때까지는 **기존 형태를 따른다** — 혼자만 다른 형태로 만들지 않는다.

## 근거

`api/src/main/java/com/osundosun/mupid/api/*/service/`, `api/src/test/`, [architecture/backend-structure.md](../architecture/backend-structure.md)
