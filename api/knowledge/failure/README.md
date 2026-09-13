# 실패 사례 (failure)

**막혔으면 여기부터 본다.** 팀이 이미 밟은 함정 184건을, 회고·`infra/` 운영 문서·코드 주석에 흩어져 있던 기록에서 모아 증상별로 정리했다.

각 항목은 `### <증상>` 제목 아래 **원인 / 해결 / 근거**(파일 경로) 순이다. 제목은 실제로 겪을 때 검색해서 찾을 수 있는 말로 쓴다.

## 영역

| 문서 | 건수 | 무엇이 들어 있나 |
|---|---:|---|
| [build-and-spring.md](build-and-spring.md) | 14 | Gradle 빌드 실패, Spring Boot 4 스타터 이름 변경, SNAPSHOT 저장소, Lombok·빈 주입 |
| [database-and-jpa.md](database-and-jpa.md) | 19 | 트랜잭션 경계, 더티체킹, 지연로딩, 스키마 반영 |
| [auth-and-api.md](auth-and-api.md) | 30 | JWT·쿠키·필터, 이메일 인증, 오류 응답 규약 위반 |
| [config-and-env.md](config-and-env.md) | 11 | `MUPID_` 접두사, `.env` 주입 경로, 값이 컨테이너에서만 안 잡히는 경우 |
| [docker-and-nginx.md](docker-and-nginx.md) | 17 | 베이스 이미지 차이, 레이어 캐싱, compose 파일 혼동, envsubst 치환 |
| [clickhouse-and-kafka.md](clickhouse-and-kafka.md) | 24 | 클러스터·Keeper, 스키마 변경, Kafka 엔진 연동, 로컬 기동 |
| [integrations.md](integrations.md) | 13 | S3 호환 스토리지, Gmail API 인증, 메일 템플릿 |
| [windows-gitbash.md](windows-gitbash.md) | 5 | 경로 자동 변환, 포트 점유 등 Windows 개발 환경 |
| [cicd-and-ops.md](cicd-and-ops.md) | 21 | GitLab CI, 배포·롤백, 백업, 러너 |
| [git-and-convention.md](git-and-convention.md) | 9 | husky 훅, 브랜치·커밋 규약 위반 |
| [frontend.md](frontend.md) | 13 | vinext·RSC·Workers 빌드, 자산 파이프라인, 린트 |
| [doc-drift.md](doc-drift.md) | 8 | **문서가 코드와 어긋난 곳.** 문서를 계약으로 믿기 전에 본다 |

## 추가하는 법

새로 밟았으면 해당 영역 파일에 추가한다.

```markdown
### 증상을 한 줄로 (검색해서 찾을 수 있게)

- **원인**: 무엇이 왜 그렇게 되는지
- **해결**: 올바른 방법
- **근거**: `경로/파일.java:12`
```

- 해소된 항목은 **지우지 말고 "해소됨" 으로 표시한다.** 그 규칙이 왜 생겼는지 맥락이 사라지지 않게 한다.
- 근거 경로가 없으면 적지 않는다. 추측은 함정 기록이 아니다.
- **이 저장소(`api/`, `frontend/`, `infra/`)에서 실제로 겪은 것만 담는다.** 개인 스터디 기록은 대상이 아니다.
