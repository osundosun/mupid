# Mupid 지식 저장소

이 프로젝트가 **무엇을 만들고, 어떻게 생겼고, 왜 그렇게 됐고, 어디서 넘어졌는지**를 모아 둔 곳이다.
저장소를 전수 조사해 작성했고, 모든 서술은 `api/`·`frontend/`·`infra/` 의 실제 파일을 근거로 한다.

## 여섯 갈래

| | 답하는 질문 | 언제 읽나 |
|---|---|---|
| 🎵 **[domain/](domain/README.md)** | 무엇을 만드는가 | 기능을 설계하거나 스키마를 바꾸기 전 |
| 🏗 **[architecture/](architecture/README.md)** | 무엇이 어디에 있는가 | 코드를 찾아 들어가기 전 |
| 🧭 **[decision/](decision/README.md)** | 왜 그렇게 정했는가 | 구조를 바꾸려 할 때, "이건 왜 이렇지?" 싶을 때 |
| 📐 **[convention/](convention/README.md)** | 어떤 규칙을 지키는가 | 커밋·브랜치·MR·문서를 남기기 전 |
| 🔥 **[failure/](failure/README.md)** | 어디서 넘어졌는가 | **막혔을 때 가장 먼저** (229건) |
| 🌱 **[lesson/](lesson/README.md)** | 해 보니 어떻더라 | 회고를 쓰거나 일하는 방식을 점검할 때 |

빌드·실행 명령과 백엔드 작업 규칙 자체는 [api/AGENTS.md](../AGENTS.md) 에 있다. 이 저장소는 그 배경과 근거를 다룬다.

## 처음이라면

1. [domain/README.md](domain/README.md) — 무엇을 만드는 서비스인지, **무엇이 아직 구현 안 됐는지**
2. [architecture/overview.md](architecture/overview.md) — 구성요소와 데이터 흐름
3. 담당 영역 문서 → [failure/](failure/README.md) 의 해당 영역

## 먼저 알아야 할 것

문서만 읽어서는 알 수 없는, 조사 중 확인된 사실들이다.

- **스키마 기준은 JPA 엔티티 매핑, `ddl-auto` 는 로컬·운영 모두 `none`.** 앱이 테이블을 만들지 않으므로 **엔티티를 고치면 DDL 을 직접 실행해야 한다.** Flyway 는 도입하지 않는다. → [decision/0011](decision/0011-jpa-manages-schema.md)
- **실제 배포되는 이미지와 nginx 설정은 `api/` 것이 아니다.** `infra/image/Dockerfile` 과 `infra/deploy/nginx.conf` 다. → [decision/0008](decision/0008-deploy-image-from-infra.md)
- **프론트엔드는 Cloudflare Workers 에 배포되지 않는다.** wrangler 의존성은 템플릿 잔재이고 실제로는 정적 빌드가 EC2 로 간다. → [decision/0012](decision/0012-frontend-static-to-ec2.md)
- **CI 는 백엔드 테스트를 돌리지 않는다.** build-only 이고 테스트는 `contextLoads` 하나뿐이다. 파이프라인 초록불을 테스트 통과로 읽지 않는다.
- **문서 여러 곳이 코드와 어긋나 있다.** → [failure/doc-drift.md](failure/doc-drift.md)

### 앞으로 만들 API

[api-todo.md](api-todo.md) — 프론트엔드가 실제로 쓰는 데이터·동작과 현재
컨트롤러를 대조해 **아직 없는 API** 를 우선순위별로 모아 뒀다. 곡 목록 조회가
없어 화면이 곡 번호를 하드코딩하고 있는 것부터, 사용자 데이터가 브라우저에만
남는 것까지.

### 결정이 필요한 것

[open-questions.md](open-questions.md) 에 사람이 정해야 답이 나오는 항목을 모아 뒀다. 보안·정책 5건이 먼저다.

### 고쳐야 할 것으로 확인된 결함

| | |
|---|---|
| 회원 탈퇴가 하드 삭제 | 정책은 soft delete → [decision/0013](decision/0013-soft-delete-policy.md) |

## 갱신 규칙

| 무엇을 했나 | 어디에 남기나 |
|---|---|
| 함정을 밟았다 | [failure/](failure/README.md) 의 해당 영역에 **증상 / 원인 / 해결 / 근거** |
| 되돌리기 어려운 선택을 했다 | [decision/](decision/README.md) 에 새 ADR (번호는 이어서) |
| 지킬 규칙을 정했다 | [convention/](convention/README.md) |
| 구조를 바꿨다 | [architecture/](architecture/README.md) 해당 문서를 **같은 MR 에서** |
| 도메인 개념이 바뀌었다 | [domain/](domain/README.md), 용어는 `glossary.md` |
| 회고에서 결론이 나왔다 | [lesson/](lesson/README.md) |

공통:

- **모든 서술에 근거 경로를 남긴다.** 확인 못 한 것은 단정하지 않는다.
- **해소된 항목은 지우지 말고 "해소됨" 으로 표시한다.** 규칙이 왜 생겼는지 맥락이 사라지지 않게 한다.
- ADR 은 고쳐 쓰지 않는다. 결정이 바뀌면 새로 쓰고 이전 것을 `대체됨` 으로 표시한다.
- **범위는 이 저장소(`api/`, `frontend/`, `infra/`)다.** `진행사항/` 의 개인 스터디 기록은 반영하지 않는다.
