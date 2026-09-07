# 구축 진행 기록 — 2026-09-07

진행도: 72/100% (CI 패키징·HTTPS·서버 배포/롤백 검증 완료)

## 완료

- 두 EC2 Docker Engine/Compose 설치 및 UFW 활성 유지.
- 추가 EC2 Runner #2083 운영, 제한된 자원 및 프로젝트별 job 네트워크 적용.
- MR !23 생성, 최초 CI 및 캐시 복원 검증. 캐시 적용 전체 검증 job 약 1분.
- Pipeline #181405 / verify #490007, package #490008 성공.
- 검증된 JAR을 rootless BuildKit으로 이미지 생성, Generic Packages 업로드 성공.
- 기본 EC2 운영 Compose 전환, DB/Redis 데이터 볼륨 유지, 앱 전용 DB 계정 분리.
- HTTPS 인증서 발급, 외부 API UP 확인, Certbot 갱신 시뮬레이션 성공.
- 제한된 배포 SSH 계정과 강제 명령 구성, 일반 명령 거부 검증.
- 배포 잠금·체크섬·이미지 태그 검증·버전 기록·롤백 구현.
- 기존 소스 2b20dcc526de296e4724e6513412801fe7811d4d 이미지로 실제 배포 성공.
- 같은 버전의 상이한 체크섬 거부 및 현재 버전 유지 확인.
- 의도적인 unhealthy 이미지 배포 후 ROLLBACK_TEST_PASSED, 정상 이미지/HTTPS 복구 확인.

## 다음

1. GitLab protected File 변수에 배포 키/known_hosts 연결.
2. 최신 배포 변경 MR 검증 및 검토 후 develop 병합, 실제 CI→서버 자동배포 확인.
3. DB 백업 및 별도 환경 복원 검증.
4. 프런트엔드 배포와 API 통합 확인.
5. 09/14 데모, 09/18 인프라 프리즈 전 운영 절차 정리.

자동배포 키 연결과 MR 병합은 아직 완료하지 않았다. 서버 API 교체 방식은 짧은 중단이 발생하며 DB 스키마 롤백은 포함하지 않는다.
