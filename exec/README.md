# 뮤피드(mupid) 포팅 매뉴얼

> **GitHub 문서 사본**: 운영 SQL 덤프는 회원 정보와 재생 이력을 포함하여 이 저장소에 복사하지 않았습니다. 덤프 링크는 접근 권한이 필요한 GitLab 원본으로 연결됩니다. 복원 명령을 사용할 때는 권한을 확인하고 파일을 별도로 준비하세요. 새 개발 환경은 `api/knowledge/domain/erd.sql`의 빈 스키마로 시작할 수 있습니다.

운영 서비스: https://j15a204.p.ssafy.io

GitLab 에서 소스를 클론한 뒤 빌드하고 배포하는 데 필요한 내용을 정리한 폴더다. 소스 코드는
따로 담지 않고 저장소의 원본 파일을 상대 링크로 가리킨다.

## 제출 항목과 문서

| 안내문 항목 | 문서 |
|---|---|
| 1. 빌드·배포 문서 — JVM·웹서버·WAS 종류와 버전, 빌드 환경 변수, 배포 특이사항, 계정·프로퍼티 정의 파일 | 모듈별 README (아래 표) |
| 2. 외부 서비스 정보 | [외부_서비스.md](외부_서비스.md) |
| 3. DB 덤프 파일 최신본 | [db/mupid_mysql_dump.sql](https://lab.ssafy.com/s15-bigdata-dist-sub1/S15P21A204/-/blob/master/exec/db/mupid_mysql_dump.sql) · 설명 [db/README.md](db/README.md) |
| 4. 시연 시나리오 | [시연_시나리오.md](시연_시나리오.md) |

## 모듈별 문서

각 문서는 같은 순서로 쓰였다: 기술 스택·버전 → 로컬 빌드·실행 → 환경 변수 → 배포 특이사항 →
설정 파일 목록 → 테스트.

| 폴더 | 소스 | 내용 |
|---|---|---|
| [backend/](backend/README.md) | `api/` | Spring Boot API. Java 21, MySQL·Redis·ClickHouse·Kafka·SeaweedFS 연결, ERD |
| [frontend/](frontend/README.md) | `frontend/` | 웹 프론트엔드(vinext·Vite·React). 정적 빌드가 edge nginx 이미지에 들어간다 |
| [app/](app/README.md) | `app/` | Tauri 안드로이드 앱. 서명 키, APK 빌드·배포 |
| [ai/](ai/README.md) | `ai_server/` | FastAPI 추천 서버. 모델·색인, Qdrant, 라벨러 CronJob |
| [data/](data/README.md) | `music_preprocessing/`, `flow_music_automation/` | 음원 수집·전처리·임베딩, 빈 DB 에 스키마·시드 적재 |
| [infra/](infra/README.md) | `infra_k8s/`, `infra/`, `.gitlab-ci.yml` | k3s 2노드 클러스터, CI/CD, 시크릿 키, 처음부터 구축하는 절차 |
| [db/](db/README.md) | — | 운영 MySQL 덤프와 복원 방법 |

## 한눈에 보는 구성

| 구성 요소 | 제품·버전 | 자세히 |
|---|---|---|
| 웹서버 | nginx 1.27 (edge, 정적 프론트엔드 + 리버스 프록시) | [infra](infra/README.md) |
| WAS | Spring Boot 4.1 내장 Tomcat, Java 21 | [backend](backend/README.md) |
| 추천 서버 | FastAPI, Python 3.12 | [ai](ai/README.md) |
| RDB | MySQL 8.4 | [backend](backend/README.md), [db](db/README.md) |
| 분석 DB | ClickHouse 25.3 (2샤드) | [data](data/README.md) |
| 메시지 | Kafka 3.9.1 | [infra](infra/README.md) |
| 캐시 | Redis 7.4 | [backend](backend/README.md) |
| 객체 저장소 | SeaweedFS (S3 호환) | [infra](infra/README.md) |
| 벡터 DB | Qdrant 1.19.1 (클러스터 안 자체 운영) | [ai](ai/README.md) |
| 오케스트레이션 | k3s v1.36.4, Ubuntu 24.04 두 노드 | [infra](infra/README.md) |

버전의 근거(매니페스트 이미지 태그, `build.gradle`, `package.json` 등)는 각 문서에 링크로 달았다.

## 처음부터 올리는 순서

자세한 절차는 [infra/README.md](infra/README.md) 3장이다. 큰 순서만 적으면 다음과 같다.

1. Ansible 로 k3s 클러스터를 구축하고 배포 수신기를 설치한다.
2. 시크릿(`mupid-secrets` 등)을 만들고 매니페스트를 적용한다.
3. MySQL 에 [db 덤프](db/README.md)(또는 빈 스키마 `erd.sql`)를 넣는다. **앱은 테이블을 만들지 않는다.**
4. SeaweedFS 버킷, Kafka 토픽, ClickHouse 스키마, Qdrant 컬렉션을 만든다.
5. CI 로 api·web·ai 이미지를 배포한다.

## 비밀값

이 폴더의 문서에는 비밀번호·API 키·토큰 값을 적지 않았다. 키 이름과, 값이 들어 있는 곳
(로컬 `.env`, k8s Secret, GitLab CI 변수)만 적었다. 값은 팀에 따로 요청한다.

**DB 덤프에는 운영 데이터가 그대로 들어 있다** — 회원 정보(비밀번호는 해시)와 재생 이력을 포함한다.

## 확인이 남은 것

저장소만으로는 알 수 없어 각 문서에 `> TODO: 팀 확인` 으로 남긴 항목이 있다.

- 팀이 사용한 IDE 와 버전 (backend·frontend·app·ai)
- 앱 서명 키(`.jks`) 원본 백업 위치, Firebase 프로젝트 소유 계정
- 외부 서비스 계정 정보 — Gmail OAuth 발급 방식, GMS 키 발급, Flow Music 계정
- 시연용 계정과 모바일 화면 미러링 방법
- 인증서 갱신 뒤 edge nginx reload, 호스트 백업 타이머, GitLab Runner 가 도는 호스트

```bash
grep -rn "TODO: 팀 확인" exec
```
