# 정예준 — 진행사항

**주제** 
**기간** 2026-08-18 ~ 2026-08-28

---

## 학습 기술 스택

| 분류 | 스택 | 상태 |
|---|---|---|
| AI | [LLM 양자화 및 파인튜닝](LLM_양자화_파인튜닝.md) | ✅ 완료 |
| AI | [위성 이미지 기반 농지 이상탐지 모델 학습](위성_이미지_이상탐지.md) | ✅ 완료 |
| 인프라 | [Hadoop Docker 환경 구축 및 MapReduce 실습](Hadoop_Docker_MapReduce.md) | ✅ 완료 |
| 인프라 | [쿠버네티스 활용 분산 서버 배포 및 관리](쿠버네티스_분산_배포.md) | ✅ 완료 |
| 백엔드 | [SpringSecurity · JPA · Flyway](SpringSecurity_JPA_Flyway.md) | ✅ 완료 |

---

## 일자별 진행사항

### 2026-09-17

- **[AI·데이터 남은 일과 순서](AI데이터_인수인계_계획.md)** · **[세션 이어가기](세션_이어가기.md)** 작성
  - 곡 분석 결과를 서비스에 붙이는 적재 경로를 develop 에 반영 (MR !103 · !104 · !105 · !107)
    - `PUT /api/v1/music/{id}/section` 에 `instruments` 추가, 구간별 악기를 demucs 스템에서 측정
    - 전처리 배치가 관리자 API 없이 곡별 장르·분위기를 직접 등록 (`music-preprocessing classify`)
  - **어휘가 세 벌**인 것을 확인 — Spring `codes`(8) · 색인 `genre` 축(57) · 색인 `style` 축(37).
    taxonomy 45종은 style 축이고, 추천기는 genre·style 을 이미 둘 다 보는데 온보딩 변환만
    genre 축을 넘긴다. 45종을 그냥 등록하면 **39종이 씨앗 0** — 오류 없이 개인화만 죽는다.
    무드는 9/9 전부 0 이고 축이 달라 변환표를 사람이 적어야 한다
  - 기존 온보딩 사용자 35명은 영향 없음을 운영 색인 어휘로 직접 확인
  - 운영 스키마 문제 2건 확인 — `music_analysis_instruments` 의 FK 컬럼에 AUTO_INCREMENT
    (값이 0 이면 MySQL 이 번호를 발급해 엉뚱한 구간에 붙고 외래키 검사까지 통과. 로컬 재현),
    `music_section_analysis.external_id` 의 UNIQUE 누락. 세 테이블 모두 0행이라 지금이 반영 시점
  - `@EmbeddedId` + 관계 이중 매핑 함정을 `MusicAnalysisInstrument` 에서도 발견해 `@MapsId` 로 수정

### 2026-08-24

- **[위성 이미지 기반 농지 이상탐지](위성_이미지_이상탐지.md)** 파이프라인 구축 및 검증
  - Sentinel-2 L2A(STAC) · Sentinel-1 GRD(GEE) · WorldCover · DEM 수집 → 10일 중앙값 합성 → 100 m 가상 필지(1 ha) 생성
  - 앙상블 구성: A1 위상정렬 robust z-score(0.55) + A2 Conv1D AutoEncoder(0.45), A3 Isolation Forest는 스윕에서 해로워 제외(0.00)
  - 결과: 87,627필지 / 44지역, RED 385개(2.1%), ROC-AUC 0.973 / PR-AUC 0.924 / lift@10% 8.2배, 초당 347필지(전국 ~1.6h)
  - 함정 8건 추적 및 해소 — 데이터 누수(AUC 1.000), 조생종 위상 차이 오독, 이모작 오염, 비-논 혼입(RED의 46%), 타이밍 오차 21배 증폭, 산림 오탐, 침수 탐지가 사실상 생육 측정
  - 개선: S1 결합으로 관측 공백 25.9% → 11.1%, 히스테리시스로 경보 유지율 50% → 99%
  - 남은 한계: 격자(1 ha) > 실제 필지(0.1~0.3 ha), 구름 지역 관측 편향, 단기 침수 탐지 불가, 필지 단위 정답 부재

### 2026-08-25

- **[LLM 양자화 및 파인튜닝](LLM_양자화_파인튜닝.md)** skeleton01~04 실습 정리 (Text-to-SQL)
  - skeleton01 — Upstage `solar-pro3` Teacher로 pseudo-label 생성(Black-box KD), `response_format` json_schema로 파싱 안정화, LLM as a Judge의 과대평가 확인(스키마 없이 추측한 SQL에 score 5)
  - skeleton02 — SmolLM2-360M LoRA(r=32) SFT, labels `-100` 마스킹으로 assistant 구간만 학습. base exec acc 0.567(easy .682 / medium .250), 100 steps 155.6s, loss 2.331 → 1.159
  - skeleton03 — SmolLM2-1.7B QLoRA(nf4 + double quant). **파라미터 4.7배 / FLOPs 4.9배 모델을 VRAM +300MiB로 학습**, 시간 오버헤드는 실측 1.19배(노트북 서술 3배와 불일치)
  - skeleton04 — vLLM 서빙. PagedAttention · KV Cache 원리를 시뮬레이터로 검증, KV Cache 9.73GiB → 동시성 31.14x. Transformers 대비 **throughput 12.74x**(단, first-token·peak memory 수치는 측정 방식 탓에 비교 무의미)
  - 공통 결론: **스키마 미주입이 구조적 병목** — JOIN 실패, `table_name` 플레이스홀더 누출. 서빙 성능은 확보했으나 모델 품질은 미달
  - 트러블슈팅 기록: `BatchEncoding.squeeze`, `datasets.map(num_proc)` pickle 실패, PEFT adapter 중첩, T4 V1 Engine 미지원(→ `dtype="half"` + `enforce_eager`)
- 다음 단계: Hadoop · Hive · Spark 분산 처리 및 쿠버네티스 배포 학습 착수

### 2026-08-26

- **[Hadoop Docker 환경 구축 및 MapReduce 실습](Hadoop_Docker_MapReduce.md)** — 단일 컨테이너 pseudo-distributed Hadoop 3.3.6 + Java 21 환경 구축
  - Dockerfile(Ubuntu 24.04) + docker-compose.yml로 HDFS·YARN 데몬 자동 기동, entrypoint.sh에서 NameNode 포맷·Ant 빌드·입력 데이터 업로드 자동화
  - MapReduce WordCount1Char 잡 구현 및 YARN 제출 — `Driver.java` + `Wordcount1char.java` (Mapper: 첫 글자 추출, Reducer: 빈도 합산, `NumReduceTasks=2`)
  - 트러블슈팅 3건 해결: (1) MRAppMaster ClassNotFoundException — `mapred-site.xml`에 `HADOOP_MAPRED_HOME` env 전달 설정 추가, (2) 설정 반영 안 됨 — `COPY` vs volume mount 차이, `--no-cache` 재빌드, (3) Java 21 모듈 접근 제한 — `--add-opens` 13개 모듈을 AM/Map/Reduce JVM opts에 추가
  - YARN 아키텍처 정리: ResourceManager·NodeManager·ApplicationMaster 구조, 컨테이너 단위 자원 격리, Hadoop 1.x JobTracker와의 차이
  - 템플릿 과제 18종(MatrixAdd·KMeans·KNNJoin·VecSimJoin·AllPairJoin 등) 확인, 미구현 상태

### 2026-08-27

- **[MetaMusic](SpringSecurity_JPA_Flyway.md)** — Spring Boot 4.1.1 음악 메타데이터 서비스 백엔드 스켈레톤 구축 (SpringSecurity · JPA · Flyway 실습)
  - 아키텍처 설계: nginx → Controller(User·Song·Search) → Service → Repository 3계층 구조를 다이어그램으로 정리 (`MetaMusic.arch.json`, `metamusic.png`)
  - 프로젝트 구성: Java 21 toolchain · Gradle, MySQL(`localhost:13306/meta_music_Db`) + Flyway, JJWT 0.12.5, Lombok, Actuator/DevTools
  - 패키지 구조를 도메인 기반으로 분리 — `auth/{controller,request,service}`, `user/{entity,repository}`, `common/{auth,config}`
  - JWT 인증 기반 구현
    - `JwtProvider` — `jwt.secret`(BASE64 디코딩) 기반 HMAC-SHA 서명, subject=email + `role` 클레임, 유효기간 1시간. 토큰 파싱 결과를 `UsernamePasswordAuthenticationToken`으로 변환하고 `validateToken`에서 `JwtException`·`IllegalArgumentException` 캐치
    - `AuthFilter`(`OncePerRequestFilter`) — `Authorization: Bearer` 헤더 파싱 → 검증 → `SecurityContextHolder`에 인증 주입
    - `SecurityConfig` — csrf·httpBasic·formLogin 비활성화, `SessionCreationPolicy.STATELESS`, `/api/auth/**` permitAll + 그 외 authenticated, `UsernamePasswordAuthenticationFilter` 앞에 `AuthFilter` 등록, `BCryptPasswordEncoder` 빈 등록
  - 도메인 모델: `User` 엔티티(email·username unique, `UserRole` GUEST·USER·ADMIN을 `EnumType.STRING`으로 저장), `UserRepository.findByEmail`
  - 요청 검증: `AuthLoginRequest`를 record + Bean Validation(`@NotBlank`, `@Email`)으로 작성, 검증 메시지 한글화
  - 미완 / 확인된 이슈
    - 컴파일 불가 — `JwtProvider.issueRefreshToken()` 본문 미작성(return 없음)
    - `AuthFilter.doFilterInternal`에서 `filterChain.doFilter(request, response)` 호출 누락 — 필터 체인이 끊겨 모든 요청이 뒤 단계로 넘어가지 못함
    - `AuthController.login`이 더미 `User`로 토큰만 발급하는 TODO 상태, `AuthService`/`AuthServiceImpl`은 빈 껍데기
    - `User.password` 길이 30 — BCrypt 해시(60자) 저장 불가. `@Builder`만 선언되어 JPA용 기본 생성자 부재
    - Flyway `V260827_1330_init.sql`이 빈 파일 — 스키마 DDL 미작성
  - 다음 단계: 로그인·회원가입 서비스 로직 구현, RefreshToken 발급/재발급, Song·Search 도메인 추가, 전역 예외 처리

### 2026-08-28

- **[MetaMusic](SpringSecurity_JPA_Flyway.md)** — 컨테이너 이미지 빌드 및 [쿠버네티스 배포](쿠버네티스_분산_배포.md) 매니페스트 작성, JWT 토큰 구조 정리
  - Docker 이미지화 — `deploy/docker/Dockerfile`, `bootJar` 산출물을 Zulu JDK 21 headless 베이스에 COPY하는 단일 스테이지 구성. `yejun614/meta-music:0.0.1` 빌드 완료(약 728MB)
  - 쿠버네티스 매니페스트 — `deploy/k8s/app-deploy.yml`
    - Deployment `replicas: 3` + `RollingUpdate(maxSurge 1 / maxUnavailable 0)` — 롤링 중에도 가용 파드 수를 유지하는 무중단 배포 설정
    - `resources` requests(250m / 512Mi) · limits(500m / 1Gi)로 컨테이너 단위 자원 격리
    - `readinessProbe` → `/api/actuator/health/readiness`, `livenessProbe` → `/api/actuator/health/liveness`
    - Service `type: LoadBalancer`, 8800 → targetPort 8080
  - 배포를 전제로 한 애플리케이션 설정 정비 (`application.yaml`)
    - Actuator를 k8s probe와 연결 — base-path `/api/actuator`, `exposure.include: health`, `show-details: never`, `health.probes.enabled: true`로 readiness/liveness 엔드포인트 노출
    - `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 20s` — 롤링 업데이트 시 처리 중 요청 보전
    - `SecurityConfig`에 `/api/actuator/**` permitAll 추가 — probe가 401에 막히지 않도록
    - JWT 시크릿을 환경변수로 외부화 — `${MMUSIC_JWT_SECRET:...}` (`openssl rand -base64 32`)
    - MySQL datasource를 주석 처리하고 H2 + h2console 의존성 추가 — 외부 DB 없이 컨테이너 단독 기동이 가능한 상태로 전환
  - JWT 토큰 구조 개선
    - subject를 email → **userId**로 변경, `token_type` 클레임(ACCESS / REFRESH) 추가로 토큰 종류 구분
    - `issueRefreshToken(User)` 구현 — 유효기간 7일, role 클레임 제외(재발급 전용). `issueAccessToken`은 role을 `User`에서 직접 읽도록 시그니처 정리
    - 어제 확인한 `AuthFilter.doFilterInternal`의 `filterChain.doFilter()` 누락 해결 — 필터 체인 정상 연결
  - 남은 이슈
    - `UserRepository.findById(int)`를 추가하고 `getAuthentication`에서 `Integer.parseInt(subject)` 사용 — PK가 `Long`이므로 `Long.parseLong` + `JpaRepository.findById(Long)`로 통일 필요
    - `User` 엔티티에 `@Builder`만 있어 JPA 기본 생성자 부재 — Hibernate 엔티티 인스턴스화 실패 위험(`@NoArgsConstructor(access = PROTECTED)` 필요)
    - `User.password` 길이 30 유지 — BCrypt 해시(60자) 저장 불가
    - Flyway `V260827_1330_init.sql` 여전히 빈 파일 (현재는 H2 + Hibernate DDL로 우회)
    - `AuthController.login`은 여전히 더미 User 토큰 발급 TODO, `AuthService`/`AuthServiceImpl` 비어 있음
    - k8s 매니페스트는 작성 완료했으나 클러스터 적용(`kubectl apply`) 미확인 — 현재 컨텍스트(`docker-desktop`)에 `meta-music` 리소스 없음
  - 다음 단계: 매니페스트 실제 적용 및 롤링 업데이트 검증, ConfigMap/Secret으로 설정 분리, 로그인·회원가입 서비스 로직 구현
