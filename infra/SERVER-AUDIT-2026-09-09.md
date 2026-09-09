# 2026-09-09 서버 확인 보고

확인 시각: 한국시간 약 13:34~13:36. 사용자가 제공한 관리자 PEM으로 두 EC2에 SSH 접속하여 읽기 전용으로 확인했다. 키 내용, 환경변수 값, 원문 애플리케이션 로그는 출력하지 않았다. 서버 파일 변경·재시작·배포·복원은 수행하지 않았다.

## 확인 완료

| 항목 | 실제 결과 |
|---|---|
| 운영 응답 | 홈 HTTP 200, API health UP |
| 컨테이너 | API·Nginx·MySQL·Redis healthy, API restart 0 / 현재 OOMKilled false |
| API 버전 | 0a8c90728281e6a2b96367d8a9728f0ca4f0ac41 (09/07 커밋) |
| 프런트 버전 | e601a5ba6ef8938fdc86e06670b49816d0714695 (09/08 병합 커밋) |
| 최근 API 배포 | 7a5a90f 및 25529f7 릴리스에 deploy.log 존재, unhealthy 문자열 존재, succeeded-at 없음. 현재 이미지와 성공 기록은 이전 버전 |
| 업무 DB | information_schema에서 mupid 스키마 테이블 수 0 확인. 업무 데이터는 조회하지 않음 |
| 비밀값 파일 권한 | /srv/mupid/.env root 소유, 0600 |
| 기존 DB/JWT 설정 | 파일 및 실행 API에 등록됨 |
| 신규 설정 | Gmail 4개, S3 access key/secret/bucket, Redis 접두사, 클라이언트 URL은 파일과 API 모두 비어 있거나 없음. S3 endpoint도 비어 있거나 없음(빈 값 자체는 허용) |
| 오늘 구현한 서버 설정 | 운영 Compose에 Gmail 매핑 없음, 수신 스크립트에 config --quiet 사전 검사 없음. 로컬 변경은 아직 서버 미적용 |
| 서버 Compose | v5.5.1. 로컬 검증 CLI v5.3.1과 버전이 다르므로 설치 전 서버 config --quiet 실행 필요 |
| 최신 백업 | 09/09 03:03:31 KST 생성, 백업 서비스 종료 03:03:43, Result=success / exit 0 |
| 백업 분리 보관 | 최신 로컬/추가 EC2 파일명·SHA256 동일. 양쪽 verified sidecar 존재. 이번 확인에서 복원을 재실행하지 않음 |
| 인증서 | 만료 2026-12-06 05:18:15 UTC, certbot 타이머 존재. 실제 갱신 성공 여부를 새로 시험하지 않음 |
| 추가 EC2 | Runner 컨테이너 실행 중. GitLab 측 online 상태나 새 job 실행은 확인하지 않음 |

## 자원 스냅샷

- 기본 EC2: RAM 약 15.4GiB, available 약 13.8GiB. API 약 390MiB/2GiB, MySQL 약 441MiB. 디스크 사용 2.8%, 여유 약 300GiB.
- 추가 EC2: RAM 약 15.4GiB, available 약 14.6GiB. Runner 약 20MiB/512MiB. 디스크 사용 2.5%, 여유 약 301GiB.
- 두 서버 swap 없음. 관측 시점에는 메모리·디스크 부족 징후가 없다. 부하 시 성능, 과거 OOM 이력, AI 모델 중복 실행 가능성을 보장하지 않는다.

## 최초 점검 시 미해결 사항 (아래 후속 확인으로 일부 해소)

1. 백엔드 담당자가 운영 Gmail 자격 증명/발신 주소와 S3 사용처·버킷·리전을 확정해야 한다. 서버에 값이 없다는 사실은 확인됐으나 외부 계정에서 발급됐는지는 확인할 수 없다.
2. 현재 코드의 S3 리전은 SeaweedFS로 고정돼 있다. AWS 사용 여부에 따라 백엔드 설정 보완이 필요하다.
3. 클라이언트 URL과 Redis 접두사 예시는 준비됐지만 공식 값으로 사용할지 확정해야 한다.
4. 최신 API는 운영 반영에 성공하지 않았다. 필수 환경 설정 누락은 확인된 배포 준비 결함이지만 unhealthy의 정확한 애플리케이션 예외 원인은 이번 확인만으로 확정하지 않는다. 값 등록·설정 설치 후 다음 배포의 기동 결과 확인이 필요하다.
5. AI 실행 형태·모델·자원·종료 규약은 PEM으로 결정할 수 없다. 담당자와 확정 후 배포에 연결한다.

## 다음 순서

운영 값 확정 → 서버 .env 등록 → DEPLOYMENT-CONTRACT.md에 따른 새 Compose/수신 스크립트 설치 → 새 SHA 배포 및 기동 실패 원인 확인 → 실제 API/스토리지 기능 통합 검증.

build -x test는 계속 유지한다. 현재 health UP은 이전 API의 상태이며 최신 인증 API나 프런트 통합 완료를 뜻하지 않는다.

## 후속 확인 및 담당자 답변 반영

- 사용자가 입력한 Gmail 4개, S3 endpoint/access key/secret/bucket, Redis 접두사는 서버 .env에서 등록 확인. 비밀값은 출력하거나 저장소에 복사하지 않았다.
- MUPID_CLIENT_WEBSITE는 마지막 확인 시 비어 있다. 운영 값은 https://j15a204.p.ssafy.io이며 이메일 링크의 기본 주소로 사용된다.
- 백엔드 담당자가 SeaweedFS 설정 유지로 확정했다. AWS 리전 변경은 현재 작업 대상이 아니다.
- 원격 fetch 후 확인한 api/compose.yml에는 개발용 SeaweedFS가 있고, api/compose.prod.yml은 env_file로 API 설정을 전달한다. 후자에는 storage 서비스가 없다.
- 실행 API의 Compose 경로는 /srv/mupid/config/compose.yml이며 신규 변수 매핑과 env_file이 없다. 실행 API에는 여전히 신규 변수가 전달되지 않았다.
- 남은 순서: 운영 SeaweedFS 접근 대상 확인 → 클라이언트 URL 등록 → 운영 Compose/수신 스크립트 설치 → 최신 API 배포 → 실제 Gmail/S3 및 기능 검증.
