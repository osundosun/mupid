# GitLab CI/CD 100MB 제한 검토

작성일: 2026-09-11. 로컬 HEAD `b31b74c`의 CI·스크립트 및 09/07~09/10 운영 기록 기준이다. 이번 검토에서는 실제 GitLab 실패 로그·관리자 제한 설정·현재 서버 상태를 조회하지 않았다. CI 수정, 서버 변경, 배포, 커밋·푸시는 실행하지 않았다.

## 판단

**GitLab CI/CD를 유지하면서 해결할 수 있다.** 100MB 제한 때문에 Jenkins로 이전하거나 수동 배포로 바꿀 필요는 없다.

먼저 실패 위치를 확인한다. 아티팩트 한도만 문제이고 설정을 올릴 수 있다면 한도 조정이 가장 작은 수정이다. 한도를 바꾸기 어렵거나 Generic Packages 전송에도 제한이 있다면, **추가 EC2의 기존 Runner에서 빌드·이미지 생성 후 운영 EC2에 SSH로 직접 전송**하는 방식을 추천한다. 운영 서버에서 빌드할 필요는 없다.

직접 전송은 JAR와 이미지가 GitLab 저장소를 거치는 경로를 모두 제거해야 효과가 있다. 이미지 업로드 단계만 SCP로 바꾸면 앞단의 JAR 아티팩트 제한은 남는다.

## 현재 흐름과 근거

```text
MR → backend-verify: Gradle build -x test
                     └ app.jar → GitLab Job Artifacts (7일)
                     └ backend-package: MR에서는 선택적 수동 실행

develop 병합 → backend-verify
             → backend-package: 아티팩트의 JAR → BuildKit → image.tar.gz
                                → Generic Packages/mupid-api/<SHA>
             → backend-deploy: 이미지 다운로드 → SHA256 확인 → SSH 전송
             → 운영 receive.sh: 검증 → docker load → Compose 교체
                                → 상태 검사, 실패 시 이전 이미지 복구
```

- [backend.yml](ci/backend.yml): JAR를 아티팩트로 저장한다. 테스트는 팀 결정으로 생략한다.
- [release.yml](ci/release.yml), [package-image.sh](ci/package-image.sh): JAR를 받아 Docker 이미지를 만들고 Generic Packages에 업로드한다. Container Registry를 쓰는 구조가 아니다.
- [deploy.sh](ci/deploy.sh), [receive.sh](deploy/receive.sh): 이미 완성된 이미지를 SSH stdin으로 전송한다. 제한 계정·강제 명령·체크섬·커밋 태그·배포 잠금·상태 검사·롤백이 구현되어 있다.
- [frontend.yml](ci/frontend.yml), [frontend-release.sh](ci/frontend-release.sh): 프런트도 정적 산출물 아티팩트 → Generic Packages → SSH를 거친다. 백엔드만 변경하면 프런트 제한은 별도로 남는다.
- [OPERATIONS.md](OPERATIONS.md), [WORKLOG-2026-09-10.md](WORKLOG-2026-09-10.md): 서버 Compose와 수신 스크립트는 CI가 자동 갱신하지 않는다. 서버 변경이 필요한 대안은 별도 설치가 필요하다.
- [SERVER-AUDIT-2026-09-09.md](SERVER-AUDIT-2026-09-09.md): 당시 추가 EC2는 약 15.4GiB RAM이었지만 [Runner 설정](runner/configure.py)의 job 제한은 CPU 2개·메모리 4GB·동시성 1이다. 호스트 전체 메모리와 job 한도를 구분해야 한다.

## 100MB 제한은 어디에 적용되는가

GitLab 공식 문서상 Job Artifacts 기본 제한은 **job이 업로드하는 최종 아카이브 100MB**다. 개별 JAR 원본 크기나 전체 프로젝트 저장 용량과 동일한 뜻이 아니다. [GitLab Job Artifacts](https://docs.gitlab.com/ci/jobs/job_artifacts/)

Generic Packages는 별도 파일 크기 제한을 사용한다. SSAFY GitLab의 실제 설정은 확인하지 않았으므로 아티팩트와 동일한 100MB라고 가정하면 안 된다. [GitLab Plan Limits API](https://docs.gitlab.com/api/plan_limits/)

| 실패 위치·로그 | 의미 | 대응 |
|---|---|---|
| backend-verify 마지막 `Uploading artifacts`에서 `413` / `too large` | JAR 전달용 아티팩트 경로에서 차단 가능성 | 아티팩트 한도 조정 또는 JAR의 job 간 아티팩트 전달 제거 |
| backend-package의 `/packages/generic/` curl 업로드에서 `413` | 패키지 파일 한도 또는 GitLab 앞단 프록시 제한 가능성 | 관리자 설정 확인 또는 이미지 직접 전송 |
| Git push 단계에서 100MB 오류 | 저장소 파일 크기 정책일 수 있음 | CI 산출물 경로 문제와 분리해 조사 |
| receive.sh의 `Image exceeds 1 GiB` | 운영 수신기의 명시적 제한 | 압축 이미지 크기 확인 및 저장·로드 자원까지 검토 |

413만으로 애플리케이션 설정과 프록시 중 어느 쪽인지 확정할 수 없다. 관리자는 GitLab 앞단 프록시 업로드 제한도 확인해야 한다. [GitLab 아티팩트 오류 대응](https://docs.gitlab.com/administration/cicd/job_artifacts_troubleshooting/)

확인할 최소 자료는 실패 job 링크·오류 전후 로그·app.jar 바이트 수·image.tar.gz 바이트 수·유효 아티팩트/패키지 한도다. 토큰·키·환경변수 값은 필요하지 않다. 현재 로컬 빌드 산출물이 없어 파일 크기는 실측하지 못했다.

## 대안 비교

| 대안 | 적합한 조건 | 변경과 부담 | 평가 |
|---|---|---|---|
| 아티팩트/패키지 한도 상향 | 관리 권한 또는 운영기관 협조 가능 | 현재 구조 유지. 프록시 한도와 저장 용량도 확인 | 가장 먼저 확인할 최소 변경 |
| JAR도 Generic Packages로 전달 | 아티팩트만 제한, Generic Packages는 충분한 크기 허용 | verify 업로드 및 package 다운로드 수정. MR 패키지 보관 정책 필요 | 작은 코드 수정 대안 |
| Runner 빌드 → SSH 이미지 직접 전송 | GitLab 업로드 제한 변경 어려움 | 빌드·패키징·전송 연결 변경, 재시도용 원본 보관 필요 | 현재 인프라에서 추천하는 구조 변경 |
| Container Registry push/pull | 사용 가능한 사설 Registry가 있음 | 인증·digest 고정·정리 정책, 서버 배포 명령 변경 | 서비스·이미지가 늘면 유리 |
| 운영 EC2에서 소스 받아 빌드 | 별도 빌드 환경이 없는 경우 | 운영 부하·소스 접근 권한·빌드 도구·재현성 관리 | 이미 Runner EC2가 있어 우선순위 낮음 |
| 압축 강화·파일 분할 | 일시적 우회 필요 | 이미 JAR와 이미지 gzip 사용. 분할은 목록·결합·검증 추가 | 주된 해결책으로 비추천 |

Registry는 BuildKit에서 직접 push할 수 있다. 다만 지금처럼 먼저 JAR 아티팩트를 전달하면 그 제한은 그대로이므로 멀티스테이지 빌드 또는 동일 job 내 빌드로 함께 변경해야 한다. SSAFY Container Registry의 활성화 여부·권한·실제 한도는 미확인이다. [Docker Registry exporter](https://docs.docker.com/build/exporters/image-registry/)

## 추천 구조의 구체적인 수정 범위

```text
MR: 빌드 검증, 대용량 JAR 아티팩트 업로드 없음

protected develop:
  검증 → release job
         ├ 해당 커밋으로 Gradle 빌드 → 동일 job에서 BuildKit 이미지 생성
         ├ image.tar.gz 완성 → SHA256 계산
         └ 기존 제한 SSH 전송 → 서버 검증·보관·교체·상태 검사·롤백

GitLab에는 job 로그와 작은 릴리스 메타데이터만 보관
```

1. `backend.yml`: MR 빌드 검증과 `build -x test`는 유지하고 대용량 JAR 아티팩트를 제거한다. 기존 verify를 develop에도 유지하면 release에서 다시 빌드하므로 비용은 증가한다. 실제 배포 대상은 release job에서 성공한 바로 그 JAR여야 한다.
2. `release.yml`: 빌드·이미지 생성·전송을 하나의 release job으로 묶어 job 사이에 대용량 파일을 넘기지 않는다. 현재 JDK job 이미지와 BuildKit job 이미지는 다르므로 단순 YAML 합치기로 끝나지 않는다. JDK/BuildKit/SSH를 갖춘 고정 도구 이미지 또는 BuildKit 멀티스테이지 빌드를 준비하고 기존 비특권 실행 환경에서 검증한다.
3. `package-image.sh`: 이미지 생성과 Generic Packages 업로드를 분리한다. `deploy.sh`는 다운로드 대신 같은 job의 완성된 로컬 이미지와 체크섬을 받도록 수정한다. 현재 서버 프로토콜은 체크섬을 먼저 요구하므로 빌드 출력을 그대로 SSH에 파이프로 흘리기보다 압축 파일을 완성한 뒤 전송한다.
4. 배포 job에 `environment: demo`, protected develop 및 `MUPID_DEPLOY_ENABLED` 조건, `resource_group: mupid-demo`, `interruptible: false`를 유지한다. demo 범위 SSH 변수가 실제 전송 job에 제공되어야 한다. MR 검증은 배포 키가 필요 없는 경로로 유지한다.
5. `needs` 및 `rules:changes`를 함께 맞춘다. 현재 `release.yml`·`backend.yml`·`package-image.sh`만 변경하면 package는 실행되어도 backend-deploy가 생략될 수 있다. 구조 전환 MR에 맞는 배포 조건을 명시하고 수동 웹 실행 정책도 확인한다.
6. 기존 서버의 이미지 태그 `mupid-api:<SHA>`, SHA256, 1GiB 수신 제한, 설정·DB 사전 검사, 상태 검사·롤백은 유지한다. 전송 경로만 바꾸는 첫 구현에서는 서버 수신 코드를 가급적 재사용한다.
7. 통합 job은 빌드 동안에도 resource_group을 점유하므로 프런트 배포가 기다릴 수 있다. 추가 EC2 Runner 동시성도 현재 1이다. 이후 처리량이 필요하면 별도 산출물 저장소와 deploy job을 분리한다.

resource_group은 동시 실행을 제어하지만 기본 실행 순서는 보장하지 않는다. 오래된 pipeline이 나중에 새 버전을 덮지 않도록 GitLab의 오래된 배포 방지 설정과 재시도 정책도 확인한다. [GitLab Resource groups](https://docs.gitlab.com/ci/resource_groups/)

### 직접 전송 전 반드시 정할 재시도 정책

현재는 Generic Packages에서 같은 파일을 다시 내려받을 수 있다. 이를 없애고 job을 재실행하면 이미지가 재빌드된다. 같은 Git SHA여도 베이스 이미지 태그·apt 패키지·빌드 결과에 따라 압축물 체크섬이 달라질 수 있다. 서버는 같은 SHA에 다른 체크섬이 들어오면 의도적으로 거부한다. digest 고정만으로 결과 파일의 바이트 단위 재현성까지 보장하지는 않는다.

- 단기 권고: 서버에 이미지가 정상 보관된 뒤 배포가 실패했다면 보관된 원본으로 운영 절차에 따라 재배포한다. 새로 빌드한 파일로 같은 SHA를 덮어쓰지 않는다. 전송 중 끊겨 미완성 임시 파일만 남은 경우는 서버의 릴리스 기록 유무를 확인하고 다시 전송한다.
- 서버 보관 전 job이 끝나면 Runner 컨테이너의 파일은 재사용을 보장할 수 없다. 모든 실패에 대한 자동 재시도까지 필요하면 별도 릴리스 저장소가 필요하다. CI 캐시는 배포 원본의 유일한 보관소로 쓰지 않는다.
- 후속 자동화: 고정된 보관 이미지에 대해서만 재배포하는 제한 명령을 추가하거나, Registry/별도 객체 저장소에 원본을 보관한다. 이 경우 강제 SSH 명령과 수신기 수정도 필요하다. 일반 셸·관리자 PEM·Docker socket을 CI에 주는 방식으로 해결하지 않는다.
- 서버 릴리스 파일·로드된 Docker 이미지의 보관 기간을 정한다. 현재/직전 정상 버전은 보존하고 자동 삭제는 대상 검증 후 별도 구현한다. 압축 이미지 1GiB 이하라도 압축 해제·Docker 로드에는 더 큰 공간이 필요하다.

## 적용·검증 순서

1. 실제 실패 job으로 제한 지점을 확정하고 관리자 한도 상향 가능 여부를 확인한다.
2. 한도 변경이 불가능하면 위 직접 전송 구조를 별도 변경으로 구현한다. 프런트가 한도 이내라면 우선 백엔드만 전환한다.
3. GitLab CI Lint에서 include/needs/rules를 검증하고 MR·develop·master·web·배포 비활성 조건을 확인한다. CI 구문 검사와 앱 빌드 검증을 구분한다.
4. Runner에서 이미지 생성까지 실행해 실제 크기·CPU/메모리·소요 시간을 측정한다. 100MB를 넘는 압축 산출물도 GitLab 대용량 업로드 없이 전달 가능한지 격리 수신 환경에서 확인한다.
5. 체크섬 불일치·1GiB 초과·전송 단절·동일 SHA 재시도·상태 검사 실패 시의 동작을 검증한다. 운영 장애 재현은 점검 시간에 수행한다.
6. 운영 반영 시 current-image, 컨테이너 건강 상태, HTTPS API와 실제 업무 API를 확인한다. 원본 보관 및 실패 시 재시도 절차를 운영 문서에 반영한다.

## 용량 해결과 별개인 운영 이슈

09/10 기록에서는 DB 인증을 해결했지만 사용자 요청에 따라 최신 백엔드 재배포를 실행하지 않았다. 업무 스키마 초기화·최신 설정 적용·실제 인증/메일 기능 검증도 후속으로 남아 있었다. 오늘의 상태는 다시 확인해야 한다. 따라서 **100MB 전송 문제 해결이 곧 최신 API 정상 기동을 보장하지는 않는다.**

백엔드팀과 논의할 결론: 한도 상향이 가능하면 기존 구조를 유지하는 것이 가장 빠르다. 한도가 고정되어 있다면 기존 Runner에서 빌드한 Docker 이미지를 SSH로 직접 전송하되, JAR 아티팩트 경로 제거와 보관 원본 재시도 절차를 함께 구현하는 것이 현재 환경에 가장 적합하다.
