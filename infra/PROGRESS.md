# 구축 진행 기록 — 2026-09-07

진행도: 40/100% (서버·Runner 연결 완료, 실제 GitLab CI 및 자동배포 미완료)

## 검증 완료

- `back/chore/cicd-setup` 브랜치 생성. 사용자 요청으로 Jira 번호 생략.
- 두 EC2 SSH 접속 및 기존 서비스/자원/UFW 점검.
- 두 EC2 Docker Engine 29.8.0 / Compose 5.5.1 설치, hello-world 성공.
- 기본 EC2에서 원본 Dockerfile 이미지 빌드 성공: `mupid-api:bootstrap`.
- 별도 Compose 프로젝트 `mupid-ci-smoke`에서 Gradle build/test 성공: 첫 실행 1분 52초.
- 테스트용 MySQL/Redis 컨테이너와 네트워크 정리, Gradle 캐시는 유지.
- 기본 EC2 API/MySQL/Redis/Nginx 모두 healthy.
- `http://127.0.0.1:8080/api/actuator/health` 응답 status UP.
- nginx wget localhost 연결 실패를 확인하고 `127.0.0.1`로 수정하여 재검증 성공.
- 저장소 `git diff --check` 통과.
- GitLab Runner #2083 등록 및 GitLab Online/Idle 확인 (19.3.1).
- 추가 EC2 `/srv/mupid-runner/compose.yml`에서 Runner 상시 실행, 재시작 정책 적용.
- 전체 job 동시성 1, 빌드 컨테이너 CPU 2/메모리 4GB, 서비스별 CPU 1/메모리 1GB.
- Runner 관리 컨테이너 CPU 0.5/메모리 512MB. job 컨테이너는 privileged=false, Docker 소켓 미제공.
- Runner 설정은 `/srv/mupid-runner/config/config.toml`에 root 소유/600 권한으로 보관.

## 현재 상태

- API 외부 공개 전 단계: nginx는 loopback 8080에만 바인딩.
- 서버 작업 경로: `/home/ubuntu/mupid-bootstrap/api`.
- 비밀값은 서버 `.env`에만 생성, 파일 권한 600. 로그/저장소에 비밀값을 출력하지 않음.
- bootstrap DB 연결은 원래 구성대로 root이며, 운영 공개 전에 제한 계정으로 전환 예정.
- GitLab 프로젝트 ID: 1440925. 로그인 및 CI 설정 접근 가능.
- 그룹/인스턴스 Runner 없음. 기존 AI review Runner #2015는 stale.
- 전용 Runner #2083 Online: `mupid-ci`, 프로젝트 제한, 최대 1200초, untagged 비활성.
- Container Registry 직접 접근은 404이며 Deploy 메뉴에도 항목 없음. 제공 여부 추가 확인 필요.
- CI 검증 파일은 로컬 작성 완료, GitLab에서 실제 실행하지 않음.
- 커밋/push/MR 생성·병합은 미실행.

## 다음 작업

1. 현재 변경을 작업 브랜치에 커밋/push하고 MR 생성 (사용자 요청 필요).
2. 실제 GitLab CI job에서 빌드·테스트·JUnit 보고서·캐시 검증.
3. 이미지 저장소 결정 및 build/publish/deploy 구현.
4. 운영 계정/HTTPS/배포 잠금/버전 기록/롤백 검증.
5. 백업·복원·프런트 배포 검증.
