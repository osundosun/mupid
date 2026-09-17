# Flow Music 데이터 파이프라인

## 목적

Google Flow Music을 통해 프롬프트 기반으로 음원(MP3 + 커버 이미지 + 생성 메타데이터)을 대량 확보하는 반자동 파이프라인. 최종 목표는 **1,000개 트랙**(`prompts.jsonl` 기준 P0001~P1000, 100개 배치 B001~B100) 확보.

## 왜 이런 구조인가

- Flow Music은 공식 API가 없어 브라우저 자동화(Playwright)로 접근.
- Playwright가 직접 띄운 브라우저는 Google 로그인에서 차단됨 → **일반 Chrome을 `--remote-debugging-port=9222`로 띄우고 사람이 로그인한 세션에 Playwright가 CDP로 접속**하는 방식 채택 (`chromium.connect_over_cdp`).
- 따라서 파이프라인 실행 중에도 Chrome은 절대 자동으로 닫지 않으며, 로그인 세션은 항상 사람이 관리.

## 아키텍처 (스크립트 구성)

| 파일 | 역할 |
|---|---|
| `flow_connect.py` | CDP 연결 + Flow 탭 탐색 공용 로직 |
| `submit_once.py` | 프롬프트 입력창 fill + Send 클릭, 신규 카드 대기 (저수준, 단일 프롬프트 CLI) |
| `download_latest.py` | 실제 결과 카드 판별(중첩 래퍼·하단 플레이어 제외), More options→Download→MP3, Details 패널에서 Sound/Lyrics/커버 추출 |
| `run_once.py` | submit + wait + download를 하나로 묶은 단일 프롬프트 파이프라인. 세션 hydration 대기, 신규 응답 그룹 판정, Flow의 명시적 생성 오류 감지 담당 |
| `run_queue.py` | `prompts.jsonl`을 순회하며 `run_once`를 호출하는 큐 러너. 상태 체크포인트, 재시작/복구, RETRYABLE 처리 |
| `recover_batch.py` | REVIEW_REQUIRED/RETRYABLE 프롬프트에 대해 실제로 이미 생성된 결과가 세션에 있는지 읽기 전용으로 재조사 후 복구 |

모든 스크립트는 **기본이 dry-run**이며, 실제 클릭/전송/다운로드는 `--confirm-generate` / `--confirm-download` / `--confirm-run` / `--confirm-recovery`를 명시해야만 수행된다.

## 파이프라인 흐름 (프롬프트 1개 기준)

1. `prompts.jsonl`에서 상태가 `WAITING`인 프롬프트를 선택
2. **New session을 정확히 1회 클릭** → 해당 프롬프트 전용 새 세션 확보 (세션은 프롬프트마다 1개, 공유하지 않음)
3. 세션 DOM이 안정화될 때까지 대기(실제 카드 수·응답 그룹 수가 3회 연속 동일해야 통과) → hydration 실패 시 아무것도 전송하지 않고 안전 중단
4. 입력창에 프롬프트 fill → Send 정확히 1회 클릭
5. Send 직후 나타나는 `/session/{uuid}` URL을 해당 프롬프트 레코드에 저장
6. 방금 보낸 메시지 바로 다음 응답 그룹(producer-part)만 범위로 한정해 신규 결과 카드를 폴링
   - 카드 2개 확인 → 생성 성공
   - Flow가 "technical glitch / generation tool / try again" 등 **명시적 오류**를 반환하면 15분을 기다리지 않고 즉시 실패 처리 (Try again 버튼은 자동 클릭하지 않음)
7. 카드 2개 중 1개를 무작위 선택 → More options → Download → MP3 (동일 candidate에서 커버·Sound·Lyrics도 함께 수집)
8. MP3/JPG 파일 크기·헤더 검증 → `downloads/audio/T{n}.mp3`, `downloads/covers/T{n}.jpg`
9. `downloads/metadata/T{n}.json` 저장 (요청 라벨과 Flow 생성 라벨을 구조적으로 분리)

## 상태 머신 (`logs/queue_state.json`)

```
WAITING → SUBMITTED → GENERATED → DOWNLOADED → COMPLETED
                                              → FAILED
                                              → REVIEW_REQUIRED
                     → RETRYABLE   (Flow가 명시적 생성 오류를 반환한 경우)
```

- **COMPLETED**: 항상 재실행에서 스킵.
- **RETRYABLE**: Flow 자체가 생성 실패를 알린 경우. 자동 재전송하지 않고 사람이 `--approve-retry PROMPT_ID [--fresh-session]`으로만 재시도 승인.
- **REVIEW_REQUIRED**: 카드 매칭이 모호하거나(제목 중복 등) 결과를 확신할 수 없는 경우. `recover_batch.py`로 별도 재조사.
- 기본적으로 **한 프롬프트라도 실패하면 큐 전체를 fail-fast로 중단**하고 사람이 판단.

## 저장 구조

```
downloads/
  audio/T000001.mp3 ...
  covers/T000001.jpg ...
  metadata/T000001.json ...
quarantine/T{n}/{timestamp}/   ← 잘못 저장된 결과를 삭제하지 않고 이동 보관
logs/
  queue_state.json     ← 프롬프트별 상태 체크포인트 (원자적 저장)
  queue_events.jsonl   ← 모든 상태 전이 이력
  queue_lock.json       ← 중복 실행 방지
  session_forensic_report.json  ← 세션 DOM 포렌식 조사 기록
```

`metadata/T{n}.json` 구조:

```json
{
  "track_id": "T000001",
  "prompt_id": "P0001",
  "batch_id": "B001",          // 세션 재사용과 무관, prompts.jsonl 분류 라벨일 뿐
  "flow_title": "...",
  "session_url": "...",        // 이 프롬프트 전용 세션
  "requested": { "category": "...", "vocal_type": "...", "language": "...", "bpm_range": [95,105] },
  "flow_generated": { "sound_raw": "...", "lyrics_raw": "...", "lyrics_present": true, "song_id": "..." },
  "analyzed": null,   // 추후 실제 음원 분석 결과용 예약 필드
  "resolved": null
}
```
`requested`(우리가 요청한 weak label)와 `flow_generated`(Flow가 생성 시점에 스스로 붙인 weak label, 실분석 아님)를 구조적으로 분리해, 추후 실제 오디오 분석(`analyzed`) 결과를 얹을 자리를 미리 마련해둠.

## 안전장치 (설계 원칙)

- 프롬프트당 **Send는 정확히 1회**. Send가 실제로 클릭됐는지(`sent` 플래그) 확인한 뒤에만 상태를 SUBMITTED로 바꿈 — 클릭 실패를 SUBMITTED/FAILED로 오기록하는 사고를 방지.
- **자동 재시도·Try again 자동 클릭·CAPTCHA 우회·자동 로그인 금지.**
- 신규 결과 판정은 "페이지 전체 마지막 카드"가 아니라 **방금 보낸 메시지 다음 응답 그룹으로 범위 한정** — 다른 프롬프트의 결과가 섞여 들어가는 것을 원천 차단.
- MP3/JPG는 크기 + 매직바이트(ID3/JPEG 헤더) 이중 검증.
- 세션은 프롬프트 1개당 1개, 오류 난 세션은 재사용도 삭제도 하지 않음.
- 모든 상태 변경은 원자적 저장(temp write + replace) + 이벤트 로그 기록 → Ctrl+C에도 진행 손실 없음.

## 지금까지 발견·수정한 주요 이슈

| 이슈 | 원인 | 대응 |
|---|---|---|
| 다른 곡의 MP3/커버가 반복 저장됨 | 세션 DOM hydration 완료 전에 Send를 눌러, 기존 카드를 "신규"로 오판 | 전송 전 3회 연속 안정 상태 확인, 신규 판정을 응답 그룹 단위로 범위 한정 |
| MP3 다운로드 시 클릭 실패 | 메뉴가 닫히며 대상 엘리먼트가 detach됨 | hover→(필요시)click/ArrowRight로 서브메뉴 오픈, 최종 클릭은 `dispatch_event`로 1회만 실행 |
| New session 직후 세션 URL 오판 | Flow는 첫 프롬프트 전송 후에야 `/session/{uuid}`를 부여 (그 전엔 `/session`) | UUID 확인 전엔 세션 URL을 확정 저장하지 않음 |
| Flow의 명시적 생성 실패(technical glitch)를 15분간 무의미하게 폴링 | 오류 메시지를 신규 카드 신호와 구분하지 못함 | 응답 그룹 텍스트에서 오류 문구를 감지하면 즉시 RETRYABLE로 중단 |
| 지연 렌더링된 결과를 "미전송"으로 오판 | Flow가 비동기로 늦게 결과를 렌더링 | prompt 해시로 모든 user-turn 발생을 추적, 다음 user-turn 전까지 전체 응답 범위를 재조사 |

## 현재 진행 상황 (B001, 10개 기준)

- 완료(COMPLETED): 6 / 10
- 대기(WAITING): 4 / 10
- 오류 데이터 오염 사고 1건 발생 → 포렌식 조사 후 격리(quarantine) + 재다운로드로 전량 복구 완료

## 1,000개 확장을 위한 고려사항

1. **세션 1개 = 1 프롬프트** 정책이므로 1,000개면 New session 클릭도 1,000회 — 순차 실행 기준 소요 시간·크레딧 예산을 사전에 가늠 필요.
2. `prompts.jsonl`을 B002~B100(P0011~P1000)까지 동일 스키마로 확장 필요 (`batch_id`/`batch_position`은 분류용이므로 자유롭게 채번 가능).
3. RETRYABLE·REVIEW_REQUIRED로 빠지는 항목은 자동 재시도되지 않으므로, 대량 실행 시 **주기적으로 사람이 검토/승인하는 절차**가 필요 (`recover_batch.py` dry-run 정기 실행 권장).
4. 현재는 순차(fail-fast) 실행만 지원 — 1,000개 규모에서는 배치 단위로 나눠 `--limit`을 걸고 중간 점검하는 운영 루틴이 현실적.
5. `analyzed`/`resolved` 필드가 비어 있으므로, 이후 실제 오디오 분석 파이프라인을 붙일 확장 지점은 이미 마련돼 있음.
