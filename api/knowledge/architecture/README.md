# 아키텍처 (architecture)

**무엇이 어디에 있고, 어떻게 연결돼 있는가.** 코드를 찾아 들어가기 전에 읽는다.

여기는 *현재 상태*를 기술한다. 그렇게 정한 *이유*는 [decision/](../decision/README.md), 건드리다 밟는 지뢰는 [failure/](../failure/README.md) 에 있다.

| 문서 | 다루는 것 |
|---|---|
| [overview.md](overview.md) | 전체 구성요소, 요청·데이터 흐름, 네트워크 토폴로지, 개발/운영 환경 차이 |
| [backend-structure.md](backend-structure.md) | 패키지·레이어 구조, 새 도메인/엔드포인트 추가 순서, 네이밍·Lombok·트랜잭션 |
| [api-conventions.md](api-conventions.md) | 공통 응답 래퍼, 에러 코드↔HTTP 매핑, 에러 추가 절차, Bruno 컬렉션 갱신 |
| [persistence.md](persistence.md) | 엔티티·관계·작성 규약·삭제 정책, 스키마 관리 실태 |
| [auth-security.md](auth-security.md) | 로그인·재발급·로그아웃 흐름, JWT 수명, SecurityConfig, 이메일 인증 |
| [admin-console.md](admin-console.md) | 관리자 화면 — 메타모델 기반 자원 CRUD, `/api/admin` 경로, 화면이 열려 있는 이유 |
| [cache-redis.md](cache-redis.md) | 캐시 키 규약과 TTL — 범용 캐시가 아니라 JWT 차단 목록 전용 |
| [analytics-clickhouse.md](analytics-clickhouse.md) | 클러스터 토폴로지, 스키마 설계, Kafka 연동, 로컬 기동·검증 |
| [integrations.md](integrations.md) | S3 호환 스토리지, Gmail API, 메일 템플릿, 자격증명 취급 |
| [configuration.md](configuration.md) | `MUPID_` 규약, `application.yaml`↔`.env`↔compose 3중 구조, 환경변수 목록 |
| [deployment.md](deployment.md) | compose·Dockerfile 용도 구분, nginx, CI 파이프라인, 배포·롤백·백업 |
| [frontend.md](frontend.md) | `frontend/`↔`prototype/` 구분, vinext + React 19 RSC + Workers, 린트·테스트 |

## 읽는 순서

처음이면 [overview.md](overview.md) → 담당 영역 문서 → [failure/](../failure/README.md) 순으로 본다.

## 갱신

구조를 바꾸면 해당 문서를 **같은 MR 에서** 고친다. 모든 서술에 근거 경로를 남기고, 확인하지 못한 것은 단정하지 않는다.
