# 백엔드·AI 통합 전 배포 규약

작성일: 2026-09-09. 현재 코드로 확인한 내용과 담당자 결정이 필요한 내용을 구분한다.
AI 컨테이너, 큐, GPU 구성은 실행 방식이 확정되기 전에 운영에 추가하지 않는다.

2026-09-11 갱신: AI 담당자가 세 번째 칸을 채웠다. 실측 근거와 남은 결정은
[AI-INTEGRATION-2026-09-11.md](AI-INTEGRATION-2026-09-11.md) 에 있다.
**추천 서빙에 GPU 가 필요 없다** — CPU 전용 RAM 1GB 로 실측됐다.

| 항목 | 현재 백엔드 | AI 담당자와 확정할 내용 |
|---|---|---|
| 실행 산출물 | Java 21 JAR, SHA 태그 Docker 이미지 | Python 3.12 + FastAPI 이미지. **코드와 별개로 모델·데이터 버전이 필요하다** — 곡이 늘면 코드가 그대로여도 산출물이 바뀐다. 산출물 4파일은 `manifest.json` 과 한 묶음으로만 배포 (근거 §4-H) |
| 실행 형태 | HTTP API, 내부 8800 포트 | **상시 HTTP.** 곡 분석(GPU 필요)은 오프라인이라 운영에 올리지 않는다 |
| 네트워크 | Nginx에서 API 연결, DB·Redis 호스트 포트 비공개 | 내부 8100. 호출 주체는 API 하나. Nginx 노출 안 함. **외부 통신 없음.** 음원 서빙은 백엔드 `streams`/`chunks` 가 하므로 AI 는 관여하지 않는다 |
| 설정 | infra/deploy/.env.example의 명시적 변수 전달 | `MUPID_POOL`(기본 `flow`) · `MUPID_POOL_EXTRA`(기본 `jamendo,own`) · `MUPID_COMBINER`. **비밀값 없음** |
| 스토리지 | S3 클라이언트, endpoint 선택 가능 | 서빙용 약 515MB(읽기 전용, 2026-09-14 실측 — 이전 120MB 표기는 오기). 이미지 포함 / SeaweedFS 중 **미결정** — SeaweedFS 운영 가동 확인이 선행 |
| 준비 상태 | /api/actuator/health 및 이미지 HEALTHCHECK | `GET /health` 가 `status:ok`. 기동 약 2초(실측). 제한시간 30초 제안. 기동 시 산출물 행 수 일치를 확인하고 다르면 **기동 거부** |
| 자원 | API 메모리 상한 2GB, JVM MaxRAMPercentage=75 | **GPU·VRAM 불필요.** RAM 상한 2GB(실측 1,036MB) · 디스크 1GB · CPU 2코어. 요청당 0.30초(이력 없음) ~ 2.22초(이력 보유) 순차 처리 — 2026-09-14 실측 |
| 종료 | 이미지에서 exec java 사용. 실제 요청 종료 보장은 미검증 | **미검증.** 백그라운드 학습 스레드가 도는 중 종료 신호를 받으면 어떻게 되는지 시험하지 않았다. 중단돼도 이벤트 로그에서 재계산 가능하나 확인이 필요하다 |
| 중복 실행 | 스케줄러·작업 여부 통합 시 확인 | **현재는 인스턴스 1개만 가능.** 상태를 파일에 쓰기 때문이다. 이벤트를 Kafka/ClickHouse 로 옮기면 무상태가 되어 다중화 가능 (근거 §4-C) |
| 복구 | 이전 API 이미지 복구, DB 스키마 복구는 별도 | 산출물 4파일은 **행 번호로 서로를 가리켜** 버전이 섞이면 엉뚱한 곡을 추천한다. 그 상태로도 기동되므로 즉시 드러나지 않는다(9/9~10 실제 2회 발생). 묶음 단위 롤백 + 기동 시 검증이 필수 |

## 통합 전 운영 설정 반영

1. 백엔드 담당자가 SeaweedFS 설정 유지로 확정했다. Gmail/S3/Redis 접두사는 서버 .env 등록을 확인했으며, 실제 인증·통신과 운영 SeaweedFS 연결 대상은 미검증이다. 클라이언트 URL은 아직 비어 있으므로 https://j15a204.p.ssafy.io를 등록해야 한다.
2. 관리자가 서버 /srv/mupid/.env에 실제 값을 추가한다. 기존 파일을 예시 파일로 덮어쓰지 않는다.
3. 새 Compose는 필수값 누락/빈 값을 거부한다. S3 endpoint는 빈 값이 허용된다.
4. 현재 application.yaml의 S3 region은 담당자 확인에 따라 SeaweedFS를 유지한다. api/compose.yml의 storage는 개발용이며 api/compose.prod.yml에는 storage 서비스가 없다. 운영에서 접근할 SeaweedFS가 실제 실행 중인지 확인한 뒤 연결한다.
5. 서버 /srv/mupid/config/compose.yml과 /usr/local/sbin/mupid-receive는 관리자가 따로 설치한다. CI는 이 파일들을 자동 교체하지 않는다.

api/compose.prod.yml은 env_file로 API에 .env를 전달하지만 실제 EC2는 /srv/mupid/config/compose.yml을 사용한다. 백엔드 Compose 업로드만으로 운영 설정이 반영되지는 않는다. 운영 .env 전체를 API에 전달하면 DB root 비밀값까지 포함되므로 준비한 명시적 매핑을 사용한다.

### 서버 적용 절차 (아직 실행하지 않음)

점검 시간에 자동배포를 멈추고 진행 중 job이 없는지 확인한다. 검토한 compose.yml과 receive.sh를 서버 /tmp/mupid-infra-0909/에 전달한다. 비밀값은 전송 폴더에 넣지 않는다.
관리자 root 셸에서 다음을 실행한다. config --quiet는 보간 결과의 비밀값을 출력하지 않는다.

```bash
set -euo pipefail
umask 077
exec 9>/srv/mupid/deploy.lock
flock -w 300 9
export MUPID_API_IMAGE="$(cat /srv/mupid/current-image)"
bash -n /tmp/mupid-infra-0909/receive.sh
docker compose --env-file /srv/mupid/.env -f /tmp/mupid-infra-0909/compose.yml config --quiet
backup_dir=$(mktemp -d /srv/mupid/config-before-0909.XXXXXX)
cp -a /srv/mupid/config/compose.yml "$backup_dir/compose.yml"
cp -a /usr/local/sbin/mupid-receive "$backup_dir/receive.sh"
install -o root -g root -m 600 /tmp/mupid-infra-0909/compose.yml /srv/mupid/config/compose.yml
install -o root -g root -m 755 /tmp/mupid-infra-0909/receive.sh /usr/local/sbin/mupid-receive
printf 'Previous configuration: %s\n' "$backup_dir"
```

이 단계는 컨테이너를 재시작하지 않는다. 셸을 종료해 잠금을 해제한 뒤 자동배포를 재개한다. 설정은 다음 새 SHA 배포 때 적용된다. 같은 SHA는 ALREADY_DEPLOYED로 종료하므로 설정 반영용으로 재배포하지 않는다.
설정 자체 복구는 위 백업의 두 파일을 같은 잠금 아래 원래 경로·권한으로 복원한다. 실행 중 컨테이너의 환경은 파일 복원만으로 바뀌지 않으므로 이미 배포한 경우 API 이미지 복구 절차도 따로 수행한다.

## 통합 이후 완료 기준

- 프런트 → 인증 API → 저장소의 실제 흐름 검증.
- AI 호출 또는 작업 제출 → 처리 → 결과 조회, 실패·시간초과·재시도 검증.
- 실제 부하에서 응답시간/오류율/메모리 측정 후 자원 튜닝.
- 구·신 버전 동시 실행 자원과 DB 호환성 확보 후 무중단 전환·롤백 검증.

CI의 build -x test는 유지한다. 이 규약은 테스트 job 재도입이나 무중단 배포 완료를 뜻하지 않는다.
