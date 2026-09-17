# Windows · Git Bash 환경

7건. 증상으로 찾고, **원인 / 해결 / 근거** 순으로 읽는다. 새로 밟은 함정은 이 파일에 같은 형식으로 추가한다.

[← 함정 목록](README.md) · [지식 저장소](../README.md)

---

## Windows · Git Bash 환경

### `NODE_ENV=production` 이 켜져 있으면 `npm ci` 가 devDependency 를 통째로 건너뛴다

- **원인**: npm 은 `NODE_ENV=production` 일 때 `--omit=dev` 를 기본으로 건다. 그래서 `npm ci` 가
  **`devDependencies` 를 하나도 설치하지 않는다.** 경고도 없고 exit code 도 0 이라 성공한 것처럼 보인다.
- **증상**: 개발 도구가 없는데 "설치했다" 고 믿게 된다. `frontend/` 에서 실제로 겪은 것 —
  `npx tsc` 가 `TS2688: Cannot find type definition file for '@cloudflare/workers-types'` 로 죽었고
  (`tsconfig.json` 의 `types` 에 그 이름이 있는데 패키지가 없으니까), `node --test` 는
  `sharp`·`typescript` 가 없어 일부 테스트 파일을 통째로 못 돌렸다. **테스트 개수 자체가 달라진다** —
  실측으로 `194 tests / 31 fail` 이 `206 tests / 33 fail` 이 됐다. 앞의 숫자로 "회귀 없다" 고
  판단하면 안 되는 이유다.
- **해결**: 설치할 때 명시적으로 끈다.
  ```bash
  NODE_ENV=development npm ci --include=dev
  ```
  `npm ls <패키지>` 가 `(empty)` 인데 `package-lock.json` 에는 항목이 있으면 이 경우다.
- **주의**: `npm warn install-scripts` 로 `sharp`·`esbuild`·`workerd` 의 postinstall 이 차단될 수 있다.
  `sharp` 는 그래도 동작하는 것을 확인했다(프리빌트 바이너리를 require 시점에 찾는다). 다른 패키지가
  조용히 깨질 수 있으니 실패가 이상하면 `node -e "require('<패키지>')"` 로 먼저 확인한다.
- **근거**: `frontend/tsconfig.json:12`, `frontend/package.json` devDependencies

### 회귀 판정은 실패 「개수」가 아니라 「이름 집합」으로 한다

- **원인**: 위처럼 환경이 달라지면 테스트 개수가 변한다. 개수만 비교하면 회귀를 놓치거나 없는 회귀를 만든다.
- **해결**: 같은 환경에서 기준선을 뜨고 **이름 집합의 차집합**을 본다. 테스트 이름 끝의 `(N.Nms)` 는
  실행마다 달라지므로 반드시 떼고 비교한다.
  ```bash
  node --experimental-strip-types --test 2>&1 | grep '^✖' | sed -E 's/ \([0-9.]+ms\)$//' | sort -u > after.txt
  git stash push -- <바꾼 경로>     # 기준선뜨기 (내가 새로 만든 테스트 파일은 남겨 둔다)
  node --experimental-strip-types --test 2>&1 | grep '^✖' | sed -E 's/ \([0-9.]+ms\)$//' | sort -u > before.txt
  git stash pop
  comm -13 before.txt after.txt     # 내가 깨뜨린 것 (비어야 한다)
  comm -23 before.txt after.txt     # 내가 고친 것
  ```
- **근거**: 이 판정으로 MuBTI 변경의 회귀 0 을 확인했다

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

### 배치가 다 끝나고 마지막 줄에서 `UnicodeEncodeError` 로 죽는다 — 해소됨

- **원인**: Windows 기본 콘솔이 cp949 다. `print("처리 완료")` 의 이모지를 인코딩하지 못해 `'cp949' codec can't encode character` 로 프로세스가 죽는다. **청킹 작업 자체는 이미 끝난 뒤**라 결과물은 남아 있고 종료 코드만 실패로 보인다. `logging.basicConfig` 의 스트림 핸들러도 같은 인코딩을 써서 로그 한글이 깨진다.
- **해결**: 진입점에서 `sys.stdout.reconfigure(encoding="utf-8", errors="replace")` 로 맞춘다.
- **근거**: `music_preprocessing/src/music_preprocessing/__main__.py`

---
