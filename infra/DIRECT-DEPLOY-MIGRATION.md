# 백엔드 SSH 직접 배포 전환 — 2026-09-11

## 구현 상태

로컬 CI·수신기 수정 및 오프라인 검사 완료. 서버 설치, GitLab CI Lint, 실제 Runner 이미지 빌드, 운영 배포는 아직 실행하지 않았다. 로컬 Docker 데몬이 없어 실제 컨테이너 빌드는 검증하지 못했다.

새 경로는 `backend-verify → backend-deploy(Gradle 빌드 + BuildKit 이미지 생성 + SSH 전송)`이다. 백엔드의 JAR 아티팩트와 Generic Packages 업로드/다운로드를 제거했다. GitLab 100MB 파일 업로드 경로를 사용하지 않는다. 운영 수신기의 압축 이미지 1GiB 제한은 유지한다.

프런트는 기존 아티팩트/Generic Packages 배포를 유지한다. 프런트도 한도를 넘으면 별도 전환이 필요하다.

## CI 실행 조건

| 입력 | 동작 |
|---|---|
| 관련 파일의 MR | `backend-verify`에서 `build -x test`; 이미지 생성은 선택적 수동 `backend-package` |
| protected develop push + 배포 활성 + 관련 변경 | verify 성공 후 backend-deploy에서 같은 커밋을 빌드하고 전송 |
| protected develop의 Run pipeline(web) + 배포 활성 | 변경 파일 유무와 무관하게 verify 후 배포/보관 원본 재시도 |
| master | 기존 verify 조건에 따라 빌드 검증; 백엔드 이미지 자동 패키징/배포 없음 |
| 배포 비활성 또는 비보호 브랜치 | 자동 백엔드 배포 없음 |

`backend-package`는 이제 MR의 이미지 빌드 확인용이며 파일을 영구 보관하지 않는다. 기존처럼 패키지 다운로드용 job으로 사용하면 안 된다. backend-deploy는 backend-verify에 `artifacts: false`로 의존한다.

verify와 release에서 빌드를 각각 실행한다. release가 배포하는 JAR는 그 job의 멀티스테이지 빌드에서 `build -x test`에 성공한 결과다. 기존 JDK job 이미지에 BuildKit을 설치하는 대신, 기존 BuildKit job에서 JDK builder 스테이지를 실행한다. 별도의 Docker 데몬/socket/privileged job은 추가하지 않는다.

빌드 context는 api의 gradlew, gradle, build.gradle, settings.gradle, lombok.config, src만 포함한다. api/.env·이전 build 디렉터리·CI SSH 키 파일은 포함하지 않는다. 런타임은 기존 infra/image/Dockerfile의 사용자·헬스체크·실행 명령을 재사용한다.

## 먼저 서버 수신기를 설치해야 한다

**CI 변경 병합 전에 `receive.sh`와 `ssh-entrypoint.sh`를 서버에 설치한다.** 기존 업로드 명령도 계속 지원하므로 서버를 먼저 갱신할 수 있다. 설치만으로 API를 재시작하거나 배포하지 않는다.

관리자가 검토한 두 파일을 운영 서버의 임시 작업 디렉터리에 복사한다. 아래는 그 디렉터리에서 실행하는 예시다. 서버에 저장소 전체나 비밀값 파일을 복사할 필요는 없다.

```bash
bash -n receive.sh
bash -n ssh-entrypoint.sh
backup_dir="/srv/mupid/direct-deploy-backup-$(date -u +%Y%m%dT%H%M%SZ)"
sudo mkdir -m 700 "$backup_dir"
sudo cp -a /usr/local/sbin/mupid-receive "$backup_dir/mupid-receive"
sudo cp -a /usr/local/bin/mupid-ssh-entrypoint "$backup_dir/mupid-ssh-entrypoint"
sudo install -o root -g root -m 755 receive.sh /usr/local/sbin/mupid-receive
sudo install -o root -g root -m 755 ssh-entrypoint.sh /usr/local/bin/mupid-ssh-entrypoint
sudo visudo -c
```

진행 중인 배포가 없는 시점에 설치한다. 기존 sudoers가 수신기 경로 전체의 실행을 허용한다면 수정하지 않아도 된다. 인자를 제한하는 별도 정책이 있다면 `mupid-receive --resume <40자리 SHA>`도 허용되는지 관리자가 확인해야 한다. 일반 셸·임의 sudo 권한으로 확대하지 않는다. 기존 forced command와 restrict는 유지한다.

새 명령은 `resume <40자리 SHA>`이며 SSH entrypoint가 `sudo /usr/local/sbin/mupid-receive --resume <SHA>`로 연결한다. 파일이 없을 때만 종료 코드 44를 반환한다. 구버전 entrypoint의 64는 CI에서 `RECEIVER_UPGRADE_REQUIRED`로 안내하고 배포를 중단한다.

GitLab 변수는 기존 `MUPID_DEPLOY_SSH_KEY`·`MUPID_DEPLOY_KNOWN_HOSTS`(protected File/demo), `MUPID_DEPLOY_ENABLED`(protected/All)를 그대로 사용한다. 새 backend-deploy에도 `environment: demo`가 있다.

서버 설치 후 MR에서 verify와 선택적 package를 확인하고 GitLab CI Lint를 실행한다. 검토 후 develop에 병합하여 첫 실제 배포를 확인한다. 관련 변경에는 CI 파일과 수신기 파일도 포함되므로 이번 CI 변경만 병합해도 배포 활성 조건을 만족하면 API 배포가 실행된다.

## 실패·재시도 동작

1. backend-deploy는 빌드 전에 `resume <SHA>`를 실행한다.
2. 보관 원본이 없으면 44 → 새 이미지 빌드 → 압축/체크섬 → 기존 `deploy <SHA> <checksum>`으로 전송한다.
3. 보관 원본이 있으면 서버의 배포 잠금 안에서 체크섬·gzip·태그·설정·DB를 검증하고 그 원본으로 배포한다. Runner는 이미지를 재빌드하지 않는다.
4. 현재 버전과 같고 실제 컨테이너·HTTPS가 정상이라면 `ALREADY_DEPLOYED`로 성공한다. 같은 버전이 unhealthy이면 기존 `SAME_RELEASE_UNHEALTHY` 정책에 따라 실패하고 운영자 복구가 필요하다.
5. SSH/권한/설정/건강 상태 오류는 실패로 끝난다. 44 외 오류를 신규 빌드로 우회하지 않는다.

보관된 이미지와 체크섬 중 하나만 있거나 내용이 손상되면 `STORED_RELEASE_INCOMPLETE` 또는 체크섬 오류로 중단한다. 기존 원본을 복구하고 재시도하며 동일 SHA의 파일을 새 빌드로 덮어쓰지 않는다. 다른 커밋의 정상 릴리스로 복구하는 기존 운영 절차도 사용할 수 있다.

서버 보관 전에 job이 중단되면 Runner 파일은 유지되지 않을 수 있다. 서버에 이미지·체크섬이 모두 없을 때는 다시 빌드한다. 서버에 저장된 후 상태 검사에 실패하면 이전 이미지로 롤백하고, 재시도 시 저장된 새 이미지로 다시 시도한다. 이미지 롤백은 DB 스키마를 되돌리지 않는다.

배포 직렬화는 GitLab `resource_group: mupid-demo`와 서버 flock을 유지한다. 통합 job은 빌드 중에도 resource_group을 점유한다. 기존 Runner의 CPU 2개/4GB 제한과 동시성 1을 유지하므로 첫 빌드 시간을 측정해야 한다. job timeout은 빌드·전송·기동을 포함해 40분이다.

오래된 pipeline의 재시도가 최신 배포를 덮지 않도록 GitLab의 오래된 배포 방지 설정도 확인한다. resource_group 자체는 최신 커밋 우선 배포를 보장하지 않는다. 현재/직전 정상 이미지와 필요한 원본은 보존하고 서버 릴리스 보관·정리 정책은 별도로 관리한다.

## 확인한 것과 남은 검증

로컬 `python -B -m unittest discover -s infra/tests -v`: 12개 통과. Windows에서는 `MUPID_TEST_BASH`에 Git Bash 실행 파일을 지정한다. Docker Compose CLI는 필요하지만 Docker 데몬·실제 SSH·운영 DB는 사용하지 않는다.

- CI YAML 앵커 파싱, 아티팩트 의존성 제거, verify와 deploy 변경 감지 범위 일치.
- 스크립트 구문 및 기존 same-release 상태 검사 4개 사례.
- resume 성공/44/구버전/SSH 오류/검증 실패에 따른 빌드·전송 분기, 빌드 실패 시 전송 방지.
- 실제 수신기의 Docker 실행 전 부분을 격리하여 보관 파일 없음·불완전·체크섬 오류·정상 원본 검증.
- 강제 SSH 명령의 정확한 SHA 형식 검증 및 추가 셸 명령 거부.
- BuildKit 실행을 모의한 빌드 context·멀티스테이지 Dockerfile·압축 체크섬 검사.
- 기존 Compose 환경변수 및 스토리지 검사.

이 검사는 GitLab 서버 측 CI Lint나 실제 이미지 빌드/배포의 대체가 아니다. 남은 검증은 실제 Runner의 rootless 멀티스테이지 빌드, 산출물 크기, 100MB 초과 이미지 전송, 운영 수신기 설치 및 sudo 연결, API 기동·롤백이다. 기존 운영 환경변수/DB 스키마 문제는 전송 경로 수정과 별도로 확인한다.

## 변경 되돌리기

CI만 이전 버전으로 되돌리면 새 수신기는 기존 업로드 명령도 처리한다. 다만 기존 100MB 문제는 다시 남는다. 수신기까지 되돌릴 때는 새 backend-deploy가 실행되지 않도록 먼저 배포를 비활성화하고 진행 중인 job을 확인한 뒤, 위 백업 파일 두 개를 원래 경로에 복구한다. DB·볼륨·릴리스 원본을 삭제할 필요는 없다.
