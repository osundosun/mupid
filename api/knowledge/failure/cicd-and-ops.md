# CI · 배포 · 운영

21건. 증상으로 찾고, **원인 / 해결 / 근거** 순으로 읽는다. 새로 밟은 함정은 이 파일에 같은 형식으로 추가한다.

[← 함정 목록](README.md) · [지식 저장소](../README.md)

---

## CI · 배포 · 운영

### CI 가 초록불인데 기능이 깨져 있다

- **원인**: `infra/ci/backend.yml` 첫 줄이 `# Build-only CI by team decision; application tests are not executed.` 이고 스크립트는 `bash gradlew --no-daemon --console=plain build -x test` 다. 커밋 `cbf0050`(chore(ci): skip backend tests and remove test-only services)에서 mysql/redis 서비스와 CI 전용 환경변수가 함께 제거됐다. 테스트 파일도 `ApiApplicationTests.contextLoads()` 하나뿐이고, `api/clickhouse/verify.sh` 도 CI 에서 돌지 않는다. 프런트도 `infra/ci/frontend.yml` 이 `npm ci` → `npm run build` → `test -f dist/client/index.html` 만 하고 테스트·oxlint·`tsc --noEmit` 을 돌리지 않는다.
- **해결**: **CI 초록불 = 동작 보장이 아니다.** 영속성·캐시·인증을 바꿨으면 로컬에서 직접 확인하고, ClickHouse/Kafka 를 바꿨으면 `docker compose up -d --wait` 후 `cd api && ./clickhouse/verify.sh` 로 25개 항목이 모두 PASS 인지 확인한다. 배포 성공은 `actuator/health` 가 UP 이라는 뜻일 뿐이다.
- **근거**: `infra/ci/backend.yml`, `infra/ci/frontend.yml`, `api/clickhouse/verify.sh`

### `api/compose.ci.yml` 과 `infra/ci/wait-services.sh` 를 파이프라인의 일부로 읽는다

- **원인**: 둘 다 저장소 전체에서 참조가 0건이다. `compose.ci.yml` 의 `test` 서비스는 `gradlew build`(테스트 포함)를 돌리지만 `MUPID_MYSQL_*`, `MUPID_REDIS_*`, `MUPID_JWT_SECRET` 만 주므로 `@SpringBootTest` 가 Gmail·S3·ClickHouse 프로퍼티 바인딩에서 막힌다. `wait-services.sh`(`for target in mysql:3306 redis:6379`)는 `cbf0050` 이전 `backend-verify` 가 호출하던 잔재다.
- **해결**: 현재 파이프라인의 일부로 읽지 않는다. 테스트를 되살릴 때 재사용할 자산으로만 본다 — 되살리려면 최소 `MUPID_GMAIL_*`, `MUPID_S3_*`, `MUPID_CLICKHOUSE_*` 를 채워야 한다.
- **근거**: `api/compose.ci.yml`, `infra/ci/wait-services.sh`, `infra/ci/backend.yml`

### 배포 SSH 키에서 `libcrypto` 오류가 난다

- **원인**: 두 가지다. (1) Windows 에서 붙여넣은 GitLab file 값이 CRLF 를 쓰거나 마지막 개행이 빠진다 — `deploy.sh:14` 주석이 그대로 적고 있다. (2) 키 본문만 넣고 `-----BEGIN/END-----` 줄을 빼먹었다.
- **해결**: `deploy.sh` 가 `tr -d '\r'` 로 정규화하고 개행을 덧붙인 뒤 `chmod 600`, `ssh-keygen -y -P ''` 로 사전 검증한다(커밋 `dc8f7d5`). 붙여넣을 때는 **시작/종료 문구를 포함한 개인키 전체**를 넣는다.
- **근거**: `infra/ci/deploy.sh:14-20`, `infra/OPERATIONS.md`

### 배포 변수를 넣었는데 배포 잡이 아예 생기지 않는다

- **원인**: 개인키는 여러 줄이라 GitLab masking 조건에 맞지 않으므로 **Visible + Protected File** 변수로 저장하고 `Expand variable reference` 를 꺼야 한다. 그리고 `MUPID_DEPLOY_ENABLED` 는 rules 에서 평가되므로 환경 범위를 demo 로 좁히면 rules 평가 시점에 값이 없어 잡이 만들어지지 않는다.
- **해결**: `MUPID_DEPLOY_ENABLED` 의 환경 범위는 All (`*`) 로 둔다. masking 이 안 되니 스크립트에서 키를 절대 echo 하지 않는 것이 유일한 방어다.
- **근거**: `infra/README.md`

### 같은 커밋을 다시 배포하려는데 exit 65 로 거부된다

- **원인**: `receive.sh:14-15` 가 `releases/<SHA>/image.sha256` 과 다르면 "An immutable release already exists with a different checksum" 으로 거부한다. 프런트(`.archive-sha256`)와 백업 볼트(`os.link` FileExistsError 분기)도 같다. 굽는 환경이 달라 바이트가 달라지면 막힌다.
- **해결**: 일시적 전송 오류는 **실패한 deploy job 만** 재시도한다. package 를 다시 굽지 않는다.
- **근거**: `infra/deploy/receive.sh:12-22`, `infra/README.md`

### 배포할 때마다 짧은 서비스 중단이 두 번 생긴다

- **원인**: 무중단 배포는 현재 범위 밖이다. 중단이 한 번이 아닌 이유는 `receive.sh` 의 `apply_image()` 가 API `up -d` 직후 nginx 를 `--no-deps --force-recreate` 로 다시 만들기 때문이다(50-51행 주석: "Resolve API's new container address after each replacement"). `nginx.conf` 의 `upstream mupid_api { server api:8800; }` 가 기동 시 이름을 한 번만 해석해서, 재생성하지 않으면 사라진 컨테이너 IP 를 계속 붙든다. 롤백 경로도 같은 함수를 타므로 실패 시 중단이 두 배가 된다.
- **해결**: 정상 동작이다. 배포 중 짧은 502 를 장애로 오인하지 않는다.
- **근거**: `infra/deploy/receive.sh:44-52`, `infra/README.md`

### 롤백했는데 구버전 API 가 계속 깨진다

- **원인**: 이미지 롤백은 **DB 스키마와 데이터를 되돌리지 않는다.** `receive.sh` 의 자동 롤백은 API 컨테이너 이미지만 이전 태그로 바꾼다.
- **해결**: 파괴적 마이그레이션은 앞뒤 버전 모두에서 동작하도록 나눠 배포한다. migration 은 이전 버전과의 호환성을 전제로 작성한다.
- **근거**: `infra/OPERATIONS.md`, `infra/deploy/receive.sh`

### `test-rollback.sh` 를 돌렸더니 운영 API 가 내려갔다

- **원인**: 이건 실제 장애를 재현하는 테스트다. 스크립트 2행 주석이 "Run as root during a maintenance window: deliberately replaces the API briefly." 이고, `[[ $EUID == 0 && ${1:-} == --allow-temporary-outage ]] || exit 64` 안전장치를 통과하면 `HEALTHCHECK … CMD exit 1` 인 가짜 이미지를 진짜 `mupid-receive` 에 밀어 넣어 배포 실패를 유발하고 롤백을 기다린다.
- **해결**: 점검 시간에만 root 로 실행한다. 데모나 평가 중에 실행하지 않는다.
- **근거**: `infra/deploy/test-rollback.sh:2-4`, `infra/README.md`

### 백업 복원 검증이 어느 날부터 실패한다

- **원인**: `backup.py` 의 verify 컨테이너가 `--memory 1g --cpus 1 --tmpfs /var/lib/mysql:rw,size=768m` 로 고정이다. 복원 데이터가 tmpfs 768MB 를 넘으면 그대로 실패한다. 기동 대기도 90회 × 2초(최대 3분) 고정이다.
- **해결**: 실제 스키마·데이터가 들어가기 전에 한도를 올린다. 09/07 기준 운영 DB 테이블이 0개라 아직 드러나지 않은 상태다.
- **근거**: `infra/deploy/backup.py:35`, `infra/OPERATIONS.md`

### 수동 백업을 돌렸더니 파이썬 트레이스백만 남고 죽는다

- **원인**: `backup.py:128` 이 `fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)` 로 `/srv/mupid/backup.lock` 을 **비블로킹**으로 잡는다(배포 잠금 `flock -w 300` 과 다르다). 03:00~03:05 타이머 실행 중에 `systemctl start mupid-backup.service` 나 `mupid-backup verify` 를 돌리면 대기 없이 `BlockingIOError` 로 끝난다.
- **해결**: 타이머 시간대를 피해서 실행한다.
- **근거**: `infra/deploy/backup.py:128`

### 오프호스트 백업이 며칠째 갱신되지 않았다

- **원인**: `backup-sync.py:13` 이 `(root/'backups').glob('mysql-*.sql.verified.json')` 만 훑고, 없으면 `No verified backup available` 로 끝난다. 즉 `verify()` 가 실패한 날은 전송 자체가 일어나지 않는다.
- **해결**: verify 실패를 먼저 확인한다. 범위도 좁다 — **현재 MySQL 만 백업한다.** Redis AOF, 서버 설정·비밀값, 인증서, ClickHouse, Kafka 는 백업 대상이 아니다.
- **근거**: `infra/deploy/backup-sync.py`, `infra/OPERATIONS.md`

### 백업이 며칠 조용히 실패해도 아무도 모른다

- **원인**: 실패 알림 채널이 없다. `infra/PROGRESS.md`: "실패 알림 채널 연결은 사용자 요청으로 추후 진행. 현재 실패는 systemd/GitLab에서 확인한다."
- **해결**: `sudo journalctl -u mupid-backup.service -n 30 --no-pager` 와 GitLab job 상태를 주기적으로 본다. `systemctl list-timers mupid-backup.timer certbot.timer --no-pager` 가 점검 명령 목록에 있는 이유다.
- **근거**: `infra/PROGRESS.md`, `infra/OPERATIONS.md`

### 디스크가 계속 찬다

- **원인**: `receive.sh` 는 `releases/<SHA>/image.tar.gz` 를 배포마다 남기고 지우지 않고, `receive-front.py` 도 `web/<SHA>` 를 쌓는다. 보관 기간별 자동 삭제 코드가 어디에도 없고 볼트 쪽(`backup-receive.py`)도 삭제 명령을 제공하지 않는다. 09/07 확인: 서버 이미지 릴리스 525MB, 프런트 8.3MB, DB 백업 36KB.
- **해결**: 관리자 SSH 로만 정리할 수 있다. 다만 `previous-image` 수동 복구가 `releases/<SHA>` 에 의존하므로 무작정 지우면 롤백 경로가 끊긴다. 두 EC2 동시 손실 대비 제3의 저장소와 장기 보관·삭제 정책은 후속 과제로 남아 있다.
- **근거**: `infra/OPERATIONS.md`, `infra/PROGRESS.md`

### 프런트 배포 SSH 세션이 잡 타임아웃까지 매달린다

- **원인**: `receive.sh` 는 `flock -w 300 9 || exit 75` 로 5분 후 포기하지만 `receive-front.py:20` 은 `fcntl.flock(lock, fcntl.LOCK_EX)` 로 **무기한 대기**한다. 같은 `/srv/mupid/deploy.lock` 을 공유하므로 백엔드 배포가 오래 걸리면 프런트가 끝까지 기다린다.
- **해결**: 평소에는 `resource_group: mupid-demo` 가 CI 레벨에서 직렬화해 드러나지 않는다. 수동 실행을 섞을 때 주의한다.
- **근거**: `infra/deploy/receive-front.py:20`, `infra/deploy/receive.sh:10`

### CI 정의만 고쳐 develop 에 올렸는데 서버가 그대로다

- **원인**: `backend-package` 의 `changes` 목록은 `infra/ci/backend.yml`, `infra/ci/release.yml`, `infra/ci/package-image.sh`, `.gitlab-ci.yml` 까지 포함하지만 `backend-deploy` 목록은 `api/**/*`, `infra/image/**/*`, `infra/ci/deploy.sh`, `infra/deploy/compose.yml`, `infra/deploy/receive.sh` 뿐이다. 이미지가 새로 만들어져도 배포 잡이 생기지 않는다. (deploy 목록이 package 목록의 부분집합이라 `needs` 가 끊기는 일은 없다.)
- **해결**: 배포가 필요하면 `api/**` 변경을 함께 올리거나 수동 실행한다. 프런트는 세 잡의 목록이 동일하지만 `frontend-package` 에는 `$CI_PIPELINE_SOURCE == "web"` rule 이 없어, 웹에서 수동 실행한 파이프라인은 backend-package 만 돌고 frontend-package 는 생성되지 않는다.
- **근거**: `infra/ci/release.yml`, `infra/ci/backend.yml`

### 프런트와 API 버전이 달라 보이는데 정상이다

- **원인**: 둘은 독립적으로 배포되고 `changes:` 규칙 때문에 한쪽만 변경된 커밋에서는 다른 쪽 deploy 잡이 아예 생성되지 않는다.
- **해결**: 각각 마지막 성공 job SHA 와 비교한다. API 는 `sudo cat /srv/mupid/current-image`, 프런트는 `curl https://j15a204.p.ssafy.io/deployment.txt` 또는 `sudo readlink /srv/mupid/web/current`.
- **근거**: `infra/OPERATIONS.md`

### AI 코드 리뷰가 내 MR 에는 안 달린다

- **원인**: 고장이 아니라 설계다. `.gitlab-ci.yml` 의 `gemini-code-review` 는 rules 가 `$CI_PIPELINE_SOURCE == "merge_request_event" && $CI_MERGE_REQUEST_SOURCE_BRANCH_NAME =~ /^study\/이규원\//` 하나뿐이고 그 다음이 `when: never` 다. `back/`·`front/`·`infra/` MR 에는 붙지 않는다.
- **해결**: 팀 전체 리뷰 자동화라고 오해하지 않는다. `allow_failure: true` 라 API 키 누락이나 Gemini 오류로 실패해도 파이프라인은 초록이므로, 리뷰가 안 달렸다고 파이프라인만 봐서는 알 수 없다(예외 메시지는 job 로그에만 남는다).
- **근거**: `.gitlab-ci.yml:12-25`

### bootstrap 스크립트를 운영 서버에서 다시 돌렸더니 다른 스택이 떴다

- **원인**: `bootstrap-*.sh` 는 **최초 구축 전용**이다. `bootstrap-backend.sh` 는 `api/compose.prod.yml` 을 쓰고 nginx 를 `127.0.0.1:8080` 에만 묶으며 `MUPID_MYSQL_USER=root` 로 시작한다. 운영은 `/srv/mupid/config/compose.yml` + `/srv/mupid/.env` + `mupid_app` 계정이다.
- **해결**: 운영 전환 이후 현재 Compose 는 `infra/deploy/compose.yml` 이다. `prepare-env.py` 도 `/srv/mupid/.env` 가 있으면 "Existing deployment environment preserved" 로 즉시 종료하는 1회성 스크립트다.
- **근거**: `infra/README.md`, `infra/bootstrap-backend.sh`, `infra/deploy/prepare-env.py`

### UFW 만 믿고 포트를 열어 두면 인터넷에 노출된다

- **원인**: Docker 가 게시한 포트는 UFW 규칙을 우회한다. `infra/deploy/compose.yml` 에서 mysql/redis 는 `ports` 가 없지만 clickhouse(9000/9009), keeper(9181), kafka(9092)는 호스트에 포트를 게시한다(Docker bridge 네트워크가 호스트를 넘지 못해 서버 간 통신에 필요).
- **해결**: 보안그룹에서 상대 서버 IP 에만 연다. 파일 주석도 "인터넷에 노출하면 안 된다" 고 적는다.
- **근거**: `infra/OPERATIONS.md`, `infra/deploy/compose.yml`

### 전용 Runner 에서 프로젝트 외 작업을 돌리면 안 된다

- **원인**: `infra/runner/configure.py` 주석 — Rootless BuildKit 때문에 job 컨테이너의 seccomp/AppArmor 가 unconfined 다("This applies to this dedicated runner's job containers, not host-wide policy").
- **해결**: `infra/README.md` 가 운영 규칙으로 못 박는다 — "프로젝트 외부 작업을 이 Runner에서 실행하지 않는다." privileged 와 docker.sock 마운트는 스크립트가 재파싱 후 assert 로 금지하고, socket 은 러너 매니저 컨테이너에만 붙는다.
- **근거**: `infra/runner/configure.py`, `infra/README.md`, `infra/runner/compose.yml`

### `configure.py` 가 `KeyError` 로 죽는다

- **원인**: 이 스크립트는 `concurrent`, `check_interval`, `limit`, `request_concurrency`, `security_opt` 는 직접 쓰지만 `memory`/`cpus`/`service_memory`/`service_cpus` 는 `assert docker['memory'] == '4g' and docker['cpus'] == '2'` 처럼 **검증만** 한다. 갓 등록한 러너의 `config.toml` 에는 이 키들이 없다.
- **해결**: docstring 대로("Set limits on the already registered dedicated runner") 러너 등록과 `[runners.docker]` 자원 한도 기입이 선행 수동 작업이다.
- **근거**: `infra/runner/configure.py`

---
