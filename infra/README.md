# Mupid CI/CD 구축

## 현재 범위

- `bootstrap-docker.sh`: Ubuntu 24.04에 공식 Docker Engine/Compose 설치. UFW와 사용자 그룹은 변경하지 않는다.
- `bootstrap-backend.sh`: `api/` 소스 복사본에서 최초 내부 접속용 배포. 서버에서만 `.env`를 생성하고 기존 파일은 보존한다.
- `ci/backend.yml`: `mupid-ci` 태그 Docker executor에서 MR/통합 브랜치의 백엔드 빌드·테스트 실행. DB/Redis는 job별 서비스이며 실제 운영 비밀값을 사용하지 않는다.
- `runner/compose.yml`: 추가 EC2에서 검증한 Runner 관리 컨테이너. 이미지 digest 고정, 자동 재시작 및 로그 회전 적용.
- `runner/configure.py`: 등록된 Runner 설정에 자원 제한 검증·동시성 설정 적용. 토큰은 출력하지 않는다.
- `../api/compose.ci.yml`: CI와 같은 의존성으로 독립된 Compose 테스트 실행.

현재는 최초 기동·검증 단계이며 자동배포, HTTPS, 이미지 저장소, 롤백 구축 완료를 의미하지 않는다.

## Git

- 작업 브랜치: `back/chore/cicd-setup` (사용자 요청으로 Jira 번호 생략).
- `master`/`develop` 직접 push 금지. 커밋·push·MR 병합은 현재 미실행.
- 기존 AI 리뷰 job은 유지한다.
- `mupid-ci` Runner는 현재 프로젝트에 제한하고, untagged job을 받지 않는다.

## 서버 배치

| 서버 | 역할 | 점검 결과 |
|---|---|---|
| 기본 EC2 | API 및 서비스 DB/Redis | Ubuntu 24.04, RAM 약 16GB, root 디스크 약 309GB |
| 추가 EC2 | CI 실행 및 향후 데이터 처리 | RAM 약 16GB, 4 vCPU, root 디스크 약 309GB |

두 서버의 최초 점검 시 외부 listener는 SSH뿐이었으며 UFW는 active였다.
CI와 데이터 작업이 추가 EC2를 공유하므로 Runner 동시성/자원 한도를 적용할 예정이다.
서버 역할 분리는 다중 노드 데이터 분산 처리 구현과 별개다.

## 내부 기동 확인

원본 `.env`/PEM/인증정보를 소스 압축물에 포함하지 않는다. 기본 EC2의 `~/mupid-bootstrap/api`가 최초 검증 디렉터리다.

```bash
cd ~/mupid-bootstrap/api
# bootstrap-backend.sh 실행 후
sudo docker compose -f compose.prod.yml -f compose.bootstrap.yml ps
curl --fail http://127.0.0.1:8080/api/actuator/health
```

bootstrap nginx는 `127.0.0.1:8080`에만 바인딩한다. 외부 HTTPS 설정 전 단계다.
초기 bootstrap은 기존 API Compose의 DB root 계정 구성을 사용한다. 외부 서비스 전 제한 DB 계정으로 전환한다.
Docker publish는 UFW만으로 차단된다고 가정하지 않는다. DB/Redis는 host port를 열지 않는다.
운영 볼륨을 제거하는 `down -v`는 배포·복구 명령에 사용하지 않는다.

## 독립 테스트

```bash
cd api
docker compose -p mupid-ci-smoke -f compose.ci.yml run --rm test
docker compose -p mupid-ci-smoke -f compose.ci.yml down
```

테스트 MySQL 데이터는 tmpfs이며 컨테이너 제거 시 사라진다. 운영 볼륨은 참조하지 않는다.
테스트 Gradle cache 볼륨은 유지한다. `.env`가 없는 소스 복사본에서 실행하는 것을 권장한다.

## 후속 작업

1. Online 상태인 전용 Runner #2083에서 실제 GitLab job 실행 (CI 변경 커밋/push 필요).
2. 이미지 저장소 제공 여부 확인 후 이미지 build/publish/deploy 구성.
3. 서버 운영 디렉터리와 비밀값·제한 DB 계정 구성.
4. HTTPS, 배포 잠금, 버전 기록, 상태 검사, 이전 버전 복구.
5. DB 백업·복원 및 프런트 배포 방식 검증.

## 공식 참고

- Docker Ubuntu 설치: https://docs.docker.com/engine/install/ubuntu/
- GitLab Runner: https://docs.gitlab.com/runner/
