# [Jira 이관 요청] Flow Music 자동화 프로젝트 — 백로그/스프린트 구성

너는 Jira와 연동되어 있는 어시스턴트야. 아래는 "flow-music-automation" 프로젝트의 지금까지 작업 이력과 앞으로 할 작업 내역이야. 이 내용을 기반으로 **Jira 백로그에 Epic/Story/Task 구조로 티켓을 만들고, 아직 끝나지 않은 항목은 현재 스프린트로 배치**해줘. 프로젝트 키, 스프린트 이름/ID 같은 Jira 쪽 정보가 없으면 작업을 시작하기 전에 먼저 물어봐줘.

## 0. 프로젝트 배경

Google Flow Music(생성형 AI 음악 서비스)은 공식 API가 없어서, Playwright로 브라우저를 자동 조작해 프롬프트 1,000개에 대해 곡(MP3+커버 이미지+메타데이터)을 대량 생성/다운로드하는 반자동 파이프라인을 만들었어. Playwright가 직접 띄운 브라우저는 Google 로그인에서 막히기 때문에, 사람이 직접 로그인해둔 일반 Chrome(`--remote-debugging-port=9222`)에 CDP로 접속하는 구조를 채택함. 곡 생성 자체는 1,000/1,000 전부 완료되었고, 지금은 그 데이터셋의 품질(특히 제목 중복/반복 문제)을 정비하는 단계임.

## 1. 완료된 작업 (Done — 지난 스프린트로 처리)

### Epic A. Playwright 다운로드 파이프라인 구축
- Story: CDP 기반 Chrome 연결 아키텍처 설계 및 검증 (`check_connection.py`, `flow_connect.py`)
- Story: 단일 프롬프트 처리 파이프라인 구현 (`submit_once.py` → `download_latest.py` → `run_once.py`: 입력창 fill/Send, 신규 응답 카드 판별, 다운로드, Sound/Lyrics/커버 추출)
- Story: 대량 처리용 큐 러너 구현 (`run_queue.py`: `prompts.jsonl` 순회, 상태 체크포인트, RETRYABLE/REVIEW_REQUIRED 분기)
- Story: 상태 머신 및 안전장치 구현
  - `WAITING → SUBMITTED → GENERATED → DOWNLOADED → COMPLETED` (실패 시 `FAILED`/`REVIEW_REQUIRED`, Flow 자체 생성 오류 시 `RETRYABLE`)
  - Send 1회 보장, 신규 카드 판정을 "직전 전송 다음 응답 그룹"으로 한정 (다른 프롬프트 결과 혼입 방지)
  - MP3/JPG 크기+매직바이트 이중 검증, 모든 상태 변경 원자적 저장(temp write + replace)
  - 기본 dry-run, 실제 실행은 `--confirm-*` 플래그 명시해야만 동작
- Story: 오염 사고 대응 체계 구축 (`recover_batch.py`, `recover_orphan.py`, `quarantine/` 격리 구조) — 실제 데이터 오염 사고 1건 발생 후 포렌식 조사 → 격리 → 재다운로드로 전량 복구한 이력 있음
- Story: 1,000개 트랙 전량 생성/다운로드 완료 + GPU 서버로 동기화 완료 (`sync_to_gpu.sh`)

### Epic B. 데이터셋 구성 및 분석
- Story: `prompts.jsonl` 1,000개 프롬프트 세트 구성, 언어/보컬 성비 밸런싱 (일본어 mixed→male 15곡 전환 등)
- Story: `downloads/metadata/T{n}.json` 스키마 v2 설계 (요청 라벨 `requested` vs Flow 자체 생성 라벨 `flow_generated` 구조적 분리, 추후 실분석용 `analyzed` 필드 예약)
- Story: 전체 데이터셋 분포 리포트 생성 (`full_dataset_report.json`: 언어/보컬/장르/BPM 분포)
- Story: 무드 추출 (`mood_extraction_full1000.json`, Energy/primary/secondary mood 패턴 파싱, 98.8% 커버리지)
- Story: 악기 구성 분포 추출 (`sound_raw` 키워드 스캔, 드럼 85%/베이스 74%/피아노 50% 등)

### Epic C. 제목(flow_title) 품질 정비
- Story: 완전 중복 제목 제거 1차 (동일 문자열 150곡/60그룹 중 268건 처리)
- Story: 고위험 보컬 곡 재작업 (가사 실사용 여부 검토 후 26곡 개별 수정 — 1차 자동화 버그로 롤백 후 수작업 재작업)
- Story: Take/Option 태그 제거 (149곡, 충돌 26곡은 `sound_raw` 기반 재작명)
- Story: 2차 파생 클러스터 수정 (Hour/Late/Cozy 클러스터 23곡 재분산)
- Story: Wave 4 — 카테고리 내부 쏠림 수정 (coffee/neon/battle/hero's/echoes/reflections 등 29개 카테고리-단어 조합, 228곡)

## 2. 진행 중 / 앞으로 할 작업 (To Do — 현재 스프린트로 배치)

- **Task: Wave 5 적용** — 전역 단어 drive(23)/night(16)/groove(15)/pulse(14)/dreamy(14) 반복 해소, 71건. 계획(`title_rework_plan_wave5.json`) 및 충돌 검증까지는 완료, **실제 적용은 아직 안 함**. 완료 기준: 백업 → 원자적 적용 → 전수 검증(파일 개수 유지, JSON 파싱 성공, title 외 필드 불변, 계획대로 정확히 변경, 신규 완전중복 미발생) 통과.
- **Task: Wave 5 적용 후 전역 단어 재스캔 및 결과 보고** — Wave5 시뮬레이션에서 funk(13)/light(12)/line(11)/acoustic(10)이 여전히 두 자리로 남는 것으로 확인됨. 실제 적용 후 재확인 필요.
- **Task: Wave 6 — 장르 라벨 재검토 (사용자 승인 대기 중, 계획만 존재)**
  - funk(11곡, instrumental_funk) → Boogie/Soul/Pocket/Swagger 등으로 분산 (단, Slap/Swing은 연주기법명이라 유지)
  - chamber(5곡, chamber_classical) → Ensemble/Quartet/Salon 등으로 분산
  - acoustic(6곡, 여러 카테고리) → Unplugged/Stripped/Bare/Organic/Live로 분산
  - bgm(6곡, casual_light_game_bgm 접미사) → 아예 제거 (Take/Option 태그 제거와 동일 방식)
  - Reese/Slap/Swing/Shuffled는 연주/프로덕션 기법명이므로 손대지 않음 (예외 확정)
- **참고(작업 불필요로 확정된 항목, 백로그에 넣지 않아도 됨)**: light/line 반복은 동음이의어·관용구라 실제 반복 아님으로 판단 종료. `instrumental_adventure_fantasy`의 legend's/odyssey/valiant/kingdom(각 15%)은 경계값이라 보류. `female_english_dance_pop` line 클러스터 3곡은 코러스 자체라 영구 제외.

## 3. 작업 원칙 (티켓 설명/DoD에 반영해줘)

1. `flow_title` 필드를 직접 덮어쓴다 (별도 필드 생성 금지 — 사용자가 명시적으로 선택한 방식).
2. 모든 변경은 `logs/title_rework_log.jsonl`에 감사 로그로 남긴다.
3. 적용 전: 계획(JSON) 생성 → 충돌 검증 → `downloads/metadata` 전체 타임스탬프 백업 → 원자적 적용(.tmp + os.replace) → 재로드 검증 → 전수 검증. 이 순서를 생략하지 않는다.
4. 보컬 곡은 가사에 대상 단어가 실제로 등장하는지, 그 단어가 "노래의 정체성/펀치라인/언어유희"인지로 판단한다 (코러스에 있다고 무조건 보존하지 않음).
5. 대체어 풀은 실제로 로테이션되는지 매번 재확인한다 (과거 pool[0] 고정 배정 버그로 26곡이 전부 같은 단어가 된 사고 있었음).
6. 매 웨이브 적용 후 전체 단어 빈도를 처음부터 재스캔해서 2차 파생 클러스터가 생기지 않았는지 확인한다.
7. 전역 단어 빈도 기준: 9 이하 이상적, 12~13까지 허용. 카테고리 내부 비중은 15% 이하(소규모 카테고리는 3곡 이하 절대 기준).
8. 장르/기법 라벨이어도 무조건 예외 처리하지 않는다 — 진짜 예외는 "바꾸면 정보 자체가 사라지는 실제 연주/프로덕션 기법명"뿐이다.

## 4. 요청

위 내용을 Epic(A/B/C 완료분, 신규 Epic "제목 정비 Wave 5-6"은 진행중) / Story / Task 계층으로 Jira 백로그에 만들어줘. 완료된 항목은 Done 상태로 기록(과거 스프린트 또는 완료 이력용)하고, "2. 진행 중/앞으로 할 작업"에 있는 항목들만 현재 스프린트에 넣어줘. 각 티켓에는 관련 파일 경로(`title_rework_plan_wave5.json`, `scripts/build_title_plan_wave5.py` 등)와 완료 기준을 설명란에 포함해줘.
