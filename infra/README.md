# Mupid CI/CD 구축

## 2026-09-11 백엔드 직접 배포 변경 (로컬 구현, 서버 미적용)

백엔드는 JAR 아티팩트와 Generic Packages를 거치지 않고, 배포 job에서 멀티스테이지 이미지 빌드 후 SSH로 전송하도록 변경했다. MR 빌드 검증과 `build -x test`는 유지한다. 재시도는 서버에 보관된 같은 SHA의 원본을 우선 사용한다. 프런트 경로는 기존 방식이다.

**CI 병합 전 서버 수신기 두 파일을 먼저 설치해야 한다.** 설치·재시도·검증 범위는 [직접 배포 전환 절차](DIRECT-DEPLOY-MIGRATION.md)를 따른다. 아래 이전 문서의 백엔드 Generic Packages 경로는 변경 전 기록이다.

## 현재 기준 (2026-09-09)

- 백엔드 CI는 팀 결정으로 `build -x test`만 실행한다. 애플리케이션 테스트와 테스트 전용 서비스는 재도입하지 않았다.
- 프런트 빌드 경로는 `frontend/`, 산출물은 `frontend/dist/client/`이다.
- MR에서 관련 변경의 빌드를 검증하고, protected develop에서 배포 허용 변수가 true일 때 관련 서비스를 배포한다. API 배포는 API 코드/이미지/배포 입력 변경으로 제한된다.
- 서버의 Compose·수신 스크립트는 CI가 자동 갱신하지 않는다. 새 환경변수 전달 및 배포 전 `config --quiet` 검사는 아래 적용 문서를 따라 설치해야 한다.
- API 배포 후 상태 검사·실패 롤백과 프런트 HTTPS/버전 검사·링크 복구를 유지한다. API 교체에는 짧은 중단이 있다.
- MySQL 백업은 격리 복원 후 추가 EC2에도 보관한다. 실패 알림, 보관 기간별 정리, AI 통합 및 무중단 배포는 후속 작업이다.

문서: [운영 절차](OPERATIONS.md), [배포 규약·서버 적용](DEPLOYMENT-CONTRACT.md), [09/09 작업 보고](WORKLOG-2026-09-09.md), [진행 상태](PROGRESS.md).

로컬 인프라 검증은 `python -B -m unittest discover -s infra/tests -v`로 실행한다. Docker Compose CLI가 필요하며 Docker 데몬·애플리케이션·네트워크는 사용하지 않는다. CI에 추가한 job은 없다.

## 09/07 구축 당시 기록

아래 내용은 당시 단계별 기록이며, 테스트 실행·경로·미완료 상태 표기는 위 현재 기준과 OPERATIONS.md를 우선한다.

### 당시 구성

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

## DB 백업

`backup.py`를 서버 `/usr/local/sbin/mupid-backup`에 설치했다. `mupid-backup.timer`는 매일 한국시간 03:00부터 최대 5분 이내에 실행한다. 백업마다 SHA256을 기록하고 네트워크/운영 볼륨 연결이 없는 임시 MySQL 컨테이너에서 SQL 복원 및 CHECK TABLE을 수행한다. 한글·바이너리·NULL 테스트 데이터 왕복 검증도 해당 임시 컨테이너 안에서 수행한다.

- 저장 위치: `/srv/mupid/backups`, root 전용 권한. SQL에는 앱 데이터가 포함될 수 있으므로 저장소에 올리지 않는다.
- `sudo systemctl start mupid-backup.service`: 즉시 백업 및 복원 검증.
- `sudo journalctl -u mupid-backup.service`: 결과/실패 확인.
- `sudo /usr/local/sbin/mupid-backup verify /srv/mupid/backups/<파일>.sql.gz`: 기존 백업 재검증.
- `.verified.json`이 있어야 복원 검증까지 통과한 백업이다.
- 현재 DB는 테이블 0개다. 실제 앱 스키마가 추가되면 그 테이블도 자동으로 복원 검사한다.
- 임시 복원 DB는 메모리 1GB, tmpfs 768MB 제한이다. 데이터 증가 시 조정한다.
- 현재 백업은 기본 EC2 내부에만 저장한다. 서버 손실 대비 외부 보관, 보관 기간별 정리, 실패 알림은 후속 과제다. 자동 삭제는 아직 하지 않는다.
- MySQL만 대상이며 Redis AOF, 서버 설정/비밀값, 인증서는 별도 백업 대상이다. 스키마 변경 작업과 백업 시간을 겹치지 않게 한다.

## EC2 프런트 배포

기존 Sites 설정은 보존한다. MUPID_DEPLOY_TARGET=ec2 환경에서 npm run build를 실행하면 dist/client에 정적 HTML을 생성한다. 기존 npm run build는 Sites 구성을 유지한다.

frontend-verify는 npm ci와 정적 빌드를 실행하고 산출물을 보관한다. develop 병합 후 frontend-package가 Generic Packages/mupid-web에 저장하며 frontend-deploy가 기존 제한 배포 키로 deploy-front 명령을 실행한다. 서버는 /srv/mupid/web/<SHA>에 검증한 파일만 풀고 current 심볼릭 링크를 교체한다. 같은 도메인의 /api/는 기존 백엔드로 전달한다. 현재 프런트는 샘플 데이터이며 실제 업무 API 연결은 아직 없다.

서버 설치: receive-front.py → /usr/local/sbin/mupid-receive-front (root 755), ssh-entrypoint.sh → /usr/local/bin/mupid-ssh-entrypoint. sudoers에 mupid-deploy의 mupid-receive-front 실행만 추가한다. Nginx는 /srv/mupid/web을 읽기 전용 마운트한다. 기존 서버 config는 별도 백업 후 갱신한다.

아카이브 체크섬/상대경로/파일 종류/용량을 검사하며 심볼릭 링크 항목은 거부한다. HTTPS deployment.txt의 버전이 다르거나 홈 응답이 실패하면 직전 프런트 링크로 복구한다. DB/API 컨테이너를 교체하지 않는다. current 전환에 필요한 새 파일만 배포하고 Sites 프로젝트는 변경하지 않는다.

## 운영 완료 상태 (2026-09-07 갱신)

프런트·백엔드 통합 자동배포와 GitLab 배포 변수 연결은 완료했다. 백업은 추가 EC2 /srv/mupid-backup-vault에도 자동 보관하며 수신 체크섬 및 독립 복원 검증을 마쳤다. 상세 절차와 현재 상태는 OPERATIONS.md, PROGRESS.md를 기준으로 한다. 이 갱신은 위 초기 단계의 미완료 표기를 대체한다. 실패 알림은 사용자 요청으로 후속 작업으로 미룬다.
