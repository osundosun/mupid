# 0011. 스키마 기준은 JPA 매핑, `ddl-auto` 는 `none` (Flyway 미도입)

**상태** 채택

## 맥락

MySQL 테이블(`users`, `email_verifications`, `object_storage_files`)의 정의를 무엇을 기준으로 삼고, 실제 반영을 무엇이 할지 정해야 했다. 선택지는 마이그레이션 도구(Flyway·Liquibase), Hibernate 자동 DDL, 앱 밖에서의 수동 관리였다.

## 결정

- **스키마의 기준은 JPA 엔티티 매핑이다.** 컬럼·타입·제약을 알고 싶으면 엔티티를 본다.
- **`spring.jpa.hibernate.ddl-auto` 는 로컬·운영 모두 `none` 으로 둔다.** 애플리케이션은 테이블을 만들지도 바꾸지도 않는다.
- **Flyway 는 도입하지 않는다.** 팀 규모와 프로젝트 기간에 비해 마이그레이션 파일을 따로 관리하는 비용이 이득보다 크다고 판단했다.

## 결과

- **앱이 스키마를 건드릴 일이 없다.** 기동만으로 운영 테이블이 변경되거나 삭제되는 사고가 구조적으로 불가능하다. 로컬도 같은 값이라 "로컬에서만 자동 생성" 으로 인한 환경 차이가 없다.
- 저장소에 MySQL DDL 파일이 없는 것은 누락이 아니라 이 결정의 결과다.
- 대신 **테이블 생성·변경은 애플리케이션 밖에서 사람이 한다.** 엔티티를 고쳐도 실제 컬럼은 따라오지 않는다 — 엔티티 변경과 DDL 실행은 별개의 작업이고, 순서를 틀리면 기동 후 조회 시점에 깨진다.
- 마이그레이션 이력이 남지 않는다. 어떤 배포에 어떤 컬럼이 추가됐는지 추적하려면 별도 수단이 필요하다.
- `infra/OPERATIONS.md` 가 경고하듯 **이미지 롤백은 DB 스키마를 되돌리지 않는다.** 스키마 변경은 이전 버전과의 호환을 고려해야 한다.

`ddl-auto` 를 명시적으로 적어 둘지는 선택이다. MySQL 은 비내장 DB 라 Spring Boot 기본값이 이미 `none` 이므로 동작은 같다. 다만 적어 두면 "정해서 none" 임이 드러난다.

## 남은 것

테이블 생성·변경 **절차를 어디에 기록할지** 정해지지 않았다. 지금은 DDL 산출물도, 실행 이력도 저장소에 없어서 새 팀원이 로컬 DB 를 세팅할 방법이 어디에도 없다. → [open-questions.md](../open-questions.md)

## 엔티티를 고칠 때

1. 엔티티를 고친다 (스키마의 기준).
2. **대응 DDL 을 직접 실행한다.** 자동 반영 경로가 없다.
3. 배포 스택의 앱 계정은 DDL 을 실행할 수 있다 — `infra/deploy/prepare-env.py:16` 이 `GRANT ALL PRIVILEGES ON mupid.*` 를 준다.
4. MySQL 백업이 매일 한국시간 03:00~03:05 에 돈다. `infra/README.md` 는 스키마 변경과 백업 시간을 겹치지 않게 하라고 적는다.

## 근거

`api/src/main/resources/application.yaml`, `api/build.gradle` (Flyway·Liquibase 의존성 없음), `api/src/main/java/.../entity/`, `infra/OPERATIONS.md`, [architecture/persistence.md](../architecture/persistence.md)
