# Mupid CI/CD 구축

## 현재 구성

- 기본 EC2: API / MySQL / Redis / Nginx HTTPS. `/srv/mupid/config`, 비밀값 `/srv/mupid/.env` (root 600).
- 추가 EC2: 전용 GitLab Runner #2083, job 동시성 1, CPU 2 / 메모리 4GB. 향후 데이터 처리 작업과 자원 배분 필요.
- 서버 역할 분리는 다중 노드 데이터 분산 처리 구현과 별개다.
- MR: 백엔드 빌드·테스트 자동 실행. 이미지 패키징은 수동 선택이므로 MR 검증 시간을 늘리지 않는다.
- protected develop: 빌드·테스트 → 검증된 JAR 이미지 패키징 → 배포. 배포는 `MUPID_DEPLOY_ENABLED=true`일 때만 실행.
- Container Registry 대신 GitLab Generic Packages에 커밋 SHA별 Docker 이미지 gzip과 SHA256 저장. CI_JOB_TOKEN으로 업로드/다운로드.
- rootless BuildKit 사용. 전용 Runner job에는 Docker socket/privileged를 제공하지 않지만 seccomp/AppArmor 프로파일을 unconfined로 설정했다. 프로젝트 외부 작업을 이 Runner에서 실행하지 않는다.

## 배포

`infra/ci/deploy.sh`가 패키지 검증 후 제한 SSH 계정으로 전송한다. 서버 `receive.sh`는 배포 잠금, 크기/체크섬/태그 검증, 버전 기록, 상태 검사와 이전 이미지 복구를 수행한다.

- `/usr/local/sbin/mupid-receive`: root 소유 755. stdin으로 이미지 압축물 입력.
- `/usr/local/bin/mupid-ssh-entrypoint`: `deploy <40자리 SHA> <64자리 체크섬>`만 허용.
- `mupid-deploy`의 authorized_keys는 restrict + forced command 적용. sudo는 수신 스크립트에 한정.
- `/srv/mupid/current-image`, `previous-image`, `releases/<SHA>`에 버전/로그 기록.
- Compose와 nginx 설정은 root가 관리한다. CI는 API 이미지 버전을 배포하며 서버 설정 파일 자체를 자동 교체하지 않는다.
- 동일 SHA의 서로 다른 압축물은 거부한다. 재시도 시 기존 패키지를 사용한다.
- API 교체 중 짧은 중단이 발생한다. 무중단 배포는 현재 범위 밖이다.
- DB/Redis 볼륨을 보존한다. `down -v` 금지. 이미지 롤백은 DB 스키마/데이터를 되돌리지 않는다.
- 애플리케이션 DB 계정은 mupid DB에만 권한을 부여한다. root 비밀번호는 API에 전달하지 않는다.

## GitLab 변수 연결 (남은 작업)

| Key | Type | Protected | Environment | 값 |
|---|---|---|---|---|
| MUPID_DEPLOY_SSH_KEY | File | Yes | demo | 전용 배포 개인키 전체, 마지막 개행 포함 |
| MUPID_DEPLOY_KNOWN_HOSTS | File | Yes | demo | 검증된 서버 SSH 호스트 키 |
| MUPID_DEPLOY_ENABLED | Variable | Yes | All (*) | true |

개인키는 여러 줄이므로 GitLab masking 조건에 맞지 않는다. Visible + Protected로 저장하고 출력하지 않는다. Expand variable reference는 끈다. ENABLED는 rules에서 읽으므로 환경 범위를 All로 둔다. develop 보호 설정 및 MR 검토 후 병합해야 최초 자동배포를 검증할 수 있다.

## HTTPS

`https://j15a204.p.ssafy.io/api/actuator/health`에서 UP 확인. HTTP는 HTTPS로 이동하며 ACME 경로는 예외다. Certbot webroot `/srv/mupid/acme`, 갱신 후 nginx reload hook, timer 활성화 및 갱신 시뮬레이션 통과. 프런트엔드는 아직 배포되지 않아 `/`는 404다.

## 검증 및 운영

- 캐시 적용 MR 검증 job 약 1분, Gradle 약 25초. 현재 자동 테스트는 1개이므로 기능 전체 검증을 의미하지 않는다.
- Pipeline #181405: backend-verify #490007 및 backend-package #490008 성공, 패키지 업로드 201 확인.
- 2026-09-07: 서버 배포 성공, immutable checksum 거부, 의도적 unhealthy 이미지 자동 롤백 및 HTTPS UP 확인.
- `test-rollback.sh --allow-temporary-outage`는 점검 시간에만 root로 실행하는 실제 장애 재현 테스트다. 운영 API가 일시 중단된다.
- bootstrap 스크립트는 최초 구축용이다. 운영 전환 이후 현재 Compose는 `infra/deploy/compose.yml`이다.
- 백업·복원, 프런트 배포, CI에서 서버까지 전체 자동배포는 아직 미검증.

작업 브랜치 `back/chore/cicd-setup`, MR !23. master/develop 직접 push 및 자동 병합은 하지 않는다.
