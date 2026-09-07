# MUPID 운영 인수 안내

기준일: 2026-09-07. 서비스 https://j15a204.p.ssafy.io/ 및 /mupid, API 상태 /api/actuator/health.

## 현재 확인된 상태

- MR !23, !24, !25 병합 완료. 통합 pipeline #181591의 6개 job 모두 성공(3분 20초).
- 프런트와 API 배포 버전: 670fd08a734f87e044b385b99b8ef9a9df812c56.
- 기본 EC2: API, MySQL, Redis, Nginx. 추가 EC2: GitLab Runner, 향후 데이터 처리 자원.
- 프런트는 샘플 데이터 기반이며 업무 API는 아직 미구현. 현재 테스트는 1개이고 운영 DB 테이블은 0개다.
- 서버 2대의 역할 분리가 데이터 분산 처리 구현을 뜻하지 않는다. 분산 처리 엔진/작업은 별도 개발 대상이다.

## 개발자 작업 흐름

1. 작업 브랜치에서 변경 후 develop 대상 MR 생성.
2. CI build/test 통과 및 변경 검토. 프런트는 npm ci와 정적 export 빌드, 백엔드는 Gradle build/test.
3. MR 병합 후 관련 이미지/정적 파일 패키징과 demo 배포 자동 실행.
4. pipeline의 deploy job 성공과 서비스 확인. MR의 수동 package job은 일반 MR 병합 필수 단계가 아니다.

배포 경합은 resource_group=mupid-demo 및 서버 flock으로 직렬화한다. 추가 EC2 Runner 동시성은 1이다. API 교체 중 짧은 중단이 발생하며 프런트는 current 링크만 교체한다. GitLab master 기본 규칙은 Maintainer push를 허용하므로 팀 규칙상 직접 push하지 않는다. develop은 서버 설정에서도 직접 push 금지다.

## 접속과 비밀값

관리자 SSH: ubuntu@j15a204.p.ssafy.io / ubuntu@j15a204a.p.ssafy.io. PEM은 개인 로컬에서만 보관한다.
서버 비밀값은 /srv/mupid/.env, root 600이다. 파일 내용을 로그·이슈·저장소에 붙이지 않는다.
CI는 별도 제한 계정 mupid-deploy를 사용한다. 배포 키는 GitLab protected File 변수 MUPID_DEPLOY_SSH_KEY, 환경 demo에 저장한다. 시작/종료 문구를 포함한 OpenSSH 개인키 전체가 필요하다. 키 본문만 입력하면 libcrypto 오류가 난다.

## 정상 상태 점검 (기본 EC2)

```bash
sudo docker ps --format '{{.Names}} {{.Status}}'
curl --fail https://j15a204.p.ssafy.io/api/actuator/health
curl --fail https://j15a204.p.ssafy.io/deployment.txt
sudo cat /srv/mupid/current-image
sudo readlink /srv/mupid/web/current
sudo systemctl list-timers mupid-backup.timer certbot.timer --no-pager
```

프런트와 API는 독립적으로 배포하므로 각 버전이 달라도 정상일 수 있다. 각각 마지막 성공 job SHA와 비교한다.

## 배포 실패 대응

- 빌드 실패: job의 최초 오류 확인, 코드/lockfile 수정 후 새 MR. 실패를 숨기는 allow_failure 설정을 추가하지 않는다.
- SSH 실패: File 변수 및 demo 범위/Protected 확인, 키 시작/종료 문구 확인. 서버 host key 불일치 시 검증 없이 StrictHostKeyChecking을 끄지 않는다.
- API unhealthy: /srv/mupid/releases/<SHA>/deploy.log 확인. 자동 롤백 후 current-image와 HTTPS UP 확인.
- 프런트 실패: 서버는 previous 링크로 되돌린다. /srv/mupid/web/current 및 HTTPS 홈과 deployment.txt 확인.
- 일시적 전송 오류는 실패 deploy job만 재시도할 수 있다. 동일 SHA의 패키지는 덮어쓰지 않는다.

수동 API 복구는 관리자 점검 시간에 다음과 같이 보관된 이전 패키지를 재사용한다.

```bash
previous=$(sudo cat /srv/mupid/previous-image)
sha=${previous#mupid-api:}
# sha가 실제 40자리 커밋이고 해당 백업 패키지가 존재하는지 먼저 확인한다.
sudo ls -l /srv/mupid/releases/"$sha"/image.tar.gz
checksum=$(sudo cat /srv/mupid/releases/"$sha"/image.sha256)
sudo sh -c 'exec /usr/local/sbin/mupid-receive "$1" "$2" < "/srv/mupid/releases/$1/image.tar.gz"' sh "$sha" "$checksum"
```

DB/Redis 볼륨을 지우는 down -v와 docker volume prune은 금지한다. 이미지 복구는 DB 스키마를 되돌리지 않으므로 migration은 이전 버전과의 호환성을 고려한다.

## 백업 및 복원

매일 한국시간 03:00~03:05 MySQL 백업 후 격리 MySQL로 복원한다. 파일 경로 /srv/mupid/backups, .sql.gz + .sql.json(체크섬) + .sql.verified.json(복원 결과).

```bash
sudo systemctl start mupid-backup.service
sudo journalctl -u mupid-backup.service -n 30 --no-pager
sudo /usr/local/sbin/mupid-backup verify /srv/mupid/backups/<백업파일>.sql.gz
```

마지막 명령은 별도 임시 DB에 복원하며 운영 DB를 덮어쓰지 않는다. 운영 복구는 먼저 API 쓰기를 중지하고 현재 DB를 백업한 뒤 복원 대상/시점 승인을 받아 수행한다. 검증된 백업을 새 DB에 복원하고 API 연결을 전환하는 방식을 우선한다. 운영 데이터 덮어쓰기 명령은 자동 제공하지 않는다.

복원 테스트 컨테이너: network none, 메모리 1GB, tmpfs 768MB. 데이터가 커지면 먼저 한도를 조정한다. 임시 컨테이너만 테스트 후 삭제한다.

현재 MySQL만 백업한다. Redis AOF, 서버 설정/비밀값, 인증서는 별도 보관 대상이다. 외부 보관 및 알림 연결은 확정 전이며 자동 보관 기간별 삭제는 하지 않는다.

## 인증서·디스크

Certbot webroot /srv/mupid/acme, 갱신 후 Nginx reload hook. UFW는 active이고 기본 EC2는 22/80/443, DB/Redis는 호스트 포트를 열지 않는다. Docker의 공개 포트는 UFW 규칙만으로 차단된다고 가정하지 않는다.

컨테이너 로그는 10MB x 3개 회전. 이미지/정적 릴리스 및 Generic Packages는 계속 누적되므로 디스크/보관 정책을 정해야 한다. 09/07 확인: 서버 이미지 릴리스 525MB, 프런트 8.3MB, DB 백업 36KB.

## 데모와 프리즈

- 09/14: 실제 기능 API 연결, 샘플 데이터 교체 여부, 브라우저 기능 테스트, 복구 담당자 확인.
- 09/18: 확정 버전/설정/백업 위치/연락 채널 기록. 이후 변경은 수정 MR로 검토하고 배포 시간을 합의.
- 자동배포를 멈출 때 GitLab MUPID_DEPLOY_ENABLED를 false로 변경한다. 이미 실행 중인 job은 별도 확인이 필요하다.

현재 인프라 미완료: 백업 분리 보관, 실패 알림 대상 연결. 서비스 개발 미완료: 업무 API 및 데이터 처리 서비스 연결과 실제 기능 통합 검증.

## 추가 EC2 백업 분리 보관 (2026-09-07 적용)

매일 mupid-backup.service가 로컬 백업/격리 복원을 마친 뒤 ExecStartPost로 mupid-backup-sync를 실행한다. 최신 검증된 .sql.gz와 체크섬/검증 JSON을 추가 EC2 /srv/mupid-backup-vault에 보관한다. 전송 실패는 서비스 실패로 표시되고 로컬 백업은 유지된다. 전송만 재시도할 때 sudo /usr/local/sbin/mupid-backup-sync를 실행한다.

기본 EC2 /srv/mupid/backup-ssh에 root 700 디렉터리와 전용 개인키 600을 보관한다. 추가 EC2의 mupid-backup 계정은 sudo 권한 없이 root 소유 강제 수신 스크립트만 실행한다. 업로드 파일명 제한, 1GiB 제한, SHA256 검증, 기존 파일 덮어쓰기 금지를 적용한다. 이 계정으로 다운로드/삭제/일반 셸을 실행할 수 없다. 수신 볼트는 해당 계정 소유 700, 파일은 600이다. CI job에 볼트나 키를 마운트하지 않는다.

설치 파일: backup-sync.py → 기본 EC2 /usr/local/sbin/mupid-backup-sync; backup-receive.py → 추가 EC2 /usr/local/bin/mupid-backup-receive (root 755). 추가 서버 호스트 공개키는 관리자 SSH로 검증한 값만 known_hosts에 등록한다. 백업 개인키는 생성한 서버에서만 보관하며 관리자 PEM/CI 배포 키를 재사용하지 않는다.

복구는 관리자 SSH로 볼트 파일을 안전한 복원 환경에 가져오고 백업 checksum/격리 복원을 재검증한 후 진행한다. 최초 분리 복사본은 추가 EC2 자체의 별도 MySQL에서도 복원 검증했다. 매일 실행은 기본 EC2 격리 복원 + 추가 EC2 수신 체크섬 확인이며, 추가 EC2에서의 독립 복원 검사는 정기 점검 시 별도로 실시한다.

보관 기간별 자동 삭제는 아직 하지 않는다. 추가 EC2 장애·공간 부족 시 최신 로컬 검증 백업을 다시 전송한다. 두 EC2 동시 손실에는 대비하지 못하므로 이후 별도 저장소를 추가할 수 있다.

실패 알림 연결은 사용자 요청으로 추후 작업이다. 현재 GitLab job 상태와 systemctl/journalctl로 실패를 확인한다. 위 문서의 분리 보관 미확정 설명은 이 절차로 대체한다.
