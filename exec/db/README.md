# DB 덤프

> **GitHub 문서 사본**: 운영 SQL 덤프는 회원 정보와 재생 이력을 포함하여 이 저장소에 복사하지 않았습니다. 덤프 링크는 접근 권한이 필요한 GitLab 원본으로 연결됩니다. 복원 명령을 사용할 때는 권한을 확인하고 파일을 별도로 준비하세요. 새 개발 환경은 `api/knowledge/domain/erd.sql`의 빈 스키마로 시작할 수 있습니다.

[mupid_mysql_dump.sql](https://lab.ssafy.com/s15-bigdata-dist-sub1/S15P21A204/-/blob/master/exec/db/mupid_mysql_dump.sql) 은 운영 MySQL(`mupid` 데이터베이스)의 전체
덤프다. 2026-09-28 08:59(KST)에 떴다. 스키마·루틴·트리거·이벤트와 전체 데이터가 들어 있고,
**회원·재생 이력 같은 운영 데이터도 포함한다.**

| | |
|---|---|
| 원본 | k3s 네임스페이스 `mupid` 의 `mysql-0` Pod (MySQL Community 8.4.11) |
| 데이터베이스 | `mupid` (문자셋 `utf8mb4`, 콜레이션 `utf8mb4_unicode_ci`) |
| 테이블 | 33개 |
| 크기 | 약 10MB |

행 수가 많은 테이블은 다음과 같다(`information_schema` 추정값).

| 테이블 | 행 수 |
|---|---|
| `object_storage_files` | 31,981 |
| `chunks` | 29,347 |
| `music_analysis_instruments` | 28,895 |
| `lyrics` | 23,039 |
| `music_section_analysis` | 8,963 |
| `play_histories` | 2,590 |
| `music` | 1,060 |

## 뜬 방법

```bash
kubectl -n mupid exec mysql-0 -- sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD";
  mysqldump -uroot --single-transaction --routines --triggers --events --hex-blob \
    --set-gtid-purged=OFF --no-tablespaces --default-character-set=utf8mb4 \
    --databases mupid' > exec/db/mupid_mysql_dump.sql
```

`--single-transaction` 이라 서비스를 멈추지 않고 일관된 시점을 뜬다.

## 복원

`--databases` 로 떴기 때문에 덤프가 `CREATE DATABASE IF NOT EXISTS mupid` 와 `USE mupid` 를
직접 한다. 빈 MySQL 8.4 에 그대로 넣으면 된다.

```bash
mysql -uroot -p < exec/db/mupid_mysql_dump.sql
```

빈 `mysql:8.4` 컨테이너에 넣어 오류 없이 33개 테이블이 모두 생기는 것을 확인했다.

앱 계정(`mupid_app`)은 덤프에 들어 있지 않다. 복원 뒤 따로 만들고 권한을 준다 — 계정 이름과
비밀번호가 어디서 오는지는 [backend/README.md](../backend/README.md) 와
[infra/README.md](../infra/README.md) 에 있다.

Spring 은 `ddl-auto` 가 `none` 이라 테이블을 만들지 않는다. 이 덤프나
[erd.sql](../../api/knowledge/domain/erd.sql)(스키마만)을 넣지 않고 앱을 띄우면 테이블이 없어서
실패한다. 운영 데이터 없이 빈 스키마로 시작하려면 `erd.sql` 을 쓴다.

## 덤프에 없는 것

MySQL 밖의 저장소는 이 덤프에 들어 있지 않다.

| 저장소 | 내용 | 복구 방법 |
|---|---|---|
| ClickHouse | 청취 이벤트·구간 신호 분석 테이블 | 스키마는 [api/clickhouse](../../api/clickhouse) 에 있다. 데이터는 Kafka 이벤트로 다시 쌓인다 |
| SeaweedFS (S3) | 음원·이미지 파일. `object_storage_files` 가 이 파일들의 메타데이터다 | [data/README.md](../data/README.md) |
| Qdrant | 구간 임베딩 벡터 | [data/README.md](../data/README.md), [ai/README.md](../ai/README.md) |
| Redis | 캐시 | 비워 두고 시작해도 된다 |
