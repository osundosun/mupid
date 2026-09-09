# 백엔드·AI 통합 전 배포 규약

작성일: 2026-09-09. 현재 코드로 확인한 내용과 담당자 결정이 필요한 내용을 구분한다.
AI 컨테이너, 큐, GPU 구성은 실행 방식이 확정되기 전에 운영에 추가하지 않는다.

| 항목 | 현재 백엔드 | AI 담당자와 확정할 내용 |
|---|---|---|
| 실행 산출물 | Java 21 JAR, SHA 태그 Docker 이미지 | 이미지/모델 버전, 빌드·실행 명령 |
| 실행 형태 | HTTP API, 내부 8800 포트 | 상시 HTTP / 큐 워커 / 배치 중 선택 |
| 네트워크 | Nginx에서 API 연결, DB·Redis 호스트 포트 비공개 | 호출 주체, 내부 포트, 외부 통신 필요 여부 |
| 설정 | infra/deploy/.env.example의 명시적 변수 전달 | 필수 변수 이름, 기본값, 비밀값 발급 담당자 |
| 스토리지 | S3 클라이언트, endpoint 선택 가능 | 모델·결과 저장 위치, 볼륨, 접근 권한 |
| 준비 상태 | /api/actuator/health 및 이미지 HEALTHCHECK | 모델 로딩 완료 판정, 시작 제한시간 |
| 자원 | API 메모리 상한 2GB, JVM MaxRAMPercentage=75 | RAM/VRAM/GPU/디스크, 동시 작업 수 |
| 종료 | 이미지에서 exec java 사용. 실제 요청 종료 보장은 미검증 | SIGTERM 처리, 작업 완료 대기, 중단 작업 재시도 |
| 중복 실행 | 스케줄러·작업 여부 통합 시 확인 | 멱등성, 중복 작업 방지, 큐 처리 보장 |
| 복구 | 이전 API 이미지 복구, DB 스키마 복구는 별도 | 모델·데이터 호환성, 작업 재처리 방법 |

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
