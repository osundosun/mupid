# Windows · Git Bash 환경

4건. 증상으로 찾고, **원인 / 해결 / 근거** 순으로 읽는다. 새로 밟은 함정은 이 파일에 같은 형식으로 추가한다.

[← 함정 목록](README.md) · [지식 저장소](../README.md)

---

## Windows · Git Bash 환경

### `docker exec` 에 컨테이너 절대경로를 넘기면 `no such file or directory` 가 난다

- **원인**: MSYS 가 `/` 로 시작하는 인자를 Windows 경로로 변환한다. `docker exec mupid-dev-kafka /opt/kafka/bin/kafka-topics.sh --list` 가 `exec: "C:/Program Files/Git/opt/kafka/bin/kafka-topics.sh"` 로 죽는다. `apache/kafka` 이미지는 kafka 툴을 `PATH` 에 넣지 않아 절대경로가 필수라 피할 수 없다.
- **해결**: `export MSYS_NO_PATHCONV=1` 과 `export MSYS2_ARG_CONV_EXCL='*'` 를 먼저 내보낸다(`verify.sh` 가 상단에서 이미 한다). PowerShell 에서는 해당 없다.
- **근거**: `api/clickhouse/verify.sh`, `api/AGENTS.md`

### `docker exec -i … clickhouse-client` 로 INSERT 하면 영원히 멈춘다

- **원인**: clickhouse-client 는 INSERT 일 때 추가 데이터를 stdin 에서 읽으려 한다. 터미널에서 직접 실행하면 stdin 이 키보드라 EOF 가 오지 않는다. 파이프로 실행하면 우연히 동작해서 놓치기 쉽다.
- **해결**: 쿼리 실행에는 `-i` 를 붙이지 않는다(`verify.sh` 의 `ch()` 헬퍼). heredoc 이 필요한 Kafka producer 호출에서만 쓴다.
- **근거**: `api/clickhouse/verify.sh`, `api/clickhouse/README.md`

### 새로 만든 셸 스크립트가 서버에서 `Permission denied` 로 죽는다

- **원인**: Windows 체크아웃에서는 실행 비트가 유지되지 않는다. 현재 실행 비트(100755)가 붙은 추적 파일은 `api/clickhouse/init/apply-ddl.sh`, `api/clickhouse/kafka/create-topics.sh`, `api/clickhouse/verify.sh` 3개뿐이고 `infra/ci/*.sh`, `infra/deploy/*.sh`, `infra/bootstrap-*.sh` 는 전부 100644 다.
- **해결**: `git update-index --chmod=+x` 로 비트를 넣거나 호출부에서 인터프리터를 명시한다. CI 가 `sh infra/ci/deploy.sh`, `bash gradlew` 를 쓰는 이유다.
- **근거**: `git ls-files -s -- '*.sh'`, `.gitattributes`

### 포트가 이미 점유되어 개발 스택이 안 뜬다

- **원인**: 호스트에서 MySQL 등이 직접 돌고 있다(로컬 3306 은 호스트 MySQL 이고 compose 컨테이너는 13306 이다).
- **해결**: `.env` 의 포트 변수를 바꿔서 피한다. 단 SeaweedFS 세 포트는 하드코딩이라 compose 파일을 고쳐야 한다(위 Docker 섹션 참고).
- **근거**: `api/AGENTS.md`, `api/compose.yml`

---
