# 0025. 무드 코드 `code_id` 32~48 은 동결한다

**상태** 채택 · **동결 규약** · 되돌리기 어려움

> ## ⛔ 에이전트·사람 모두에게: 확인 없이 고치지 않는다
>
> 아래 17행의 **`code_id` · `code_name` · `display_name` 을 바꾸거나, 순서를 바꾸거나,
> 사이에 끼워 넣거나, 지우는 변경은 금지한다.**
>
> 이 표에 손대야 할 것 같으면 **작업을 멈추고 사람에게 묻는다.** 묻지 않고 진행하지
> 않는다. "시드 목록을 정리했다", "이름을 통일했다", "안 쓰는 것 같아 뺐다" 로 넘어간
> 변경이 이미 운영 사고를 두 번 냈다 (아래 *맥락*).
>
> **확인해야 하는 변경의 예** — 하나라도 해당하면 멈춘다.
>
> - `BootstrapRunner.CODE_SEEDS` 의 MOOD 항목을 추가·삭제·수정·재배치
> - `BootstrapRunner.MOOD_CODE_ID_BASE` 변경
> - `seed-codes.sql` 의 MOOD 행 변경
> - `frontend/lib/mupid-data.ts` 의 `moodOptions` 변경
> - `ai_server/app/onboarding.py` 의 `MOOD_MAP` 키 변경
> - `codes` 테이블의 32~48 을 건드리는 UPDATE/DELETE/INSERT
>
> **물을 때 같이 낸다:** 무엇을 왜 바꾸려는지, 바꾸면 어긋나는 다섯 곳(아래 *한 값이
> 다섯 곳에 걸려 있다*)을 어떻게 맞출 것인지, 이미 쌓인 참조를 어떻게 이관할 것인지.
>
> ### 그리고: FE·BE 는 **32~48 만** 쓴다
>
> **온보딩·추천·화면 어디서든, 무드 `code_id` 는 32~48 안의 값만 주고받는다.**
> 그 밖의 번호(98~106 · 107~123 · 124~132 같은 중복분)는 **DB 에 남아 있어도 쓰지
> 않는다.** "DB 에 있으니 쓸 수 있는 번호" 가 아니다.
>
> 새 무드가 정당하게 생기는 길은 하나뿐이다 — **음악을 추가로 preprocess 하면서
> 새 `code_id` 가 발급되는 것.** 그렇게 발급된 번호만 새로 쓸 수 있고, 그 번호를
> **온보딩 선택지에 넣으려면** `MoodCodeIds` 의 구간을 넓혀야 하며 그것은 위
> 확인 절차를 거친다. 발급 없이 기존 번호를 골라 쓰는 것은 금지한다.

## 맥락

무드는 `codes` 테이블에 `code_type = 'MOOD'` 로 들어간다. 그런데 이 값은 **데이터가
아니라 식별자**다 — 네 테이블이 `code_id` 를 들고 있고, 그중 `onboarding_survey` 는
사용자가 직접 고른 답이라 다시 만들 수 없다.

이 표는 두 번 무너졌다.

1. **2026-09-18.** 무드 `code_name` 을 한국어에서 영문으로 바꾼 뒤(`애상적인` →
   `melancholic`), 아직 배포되지 않은 옛 판이 한국어 이름을 못 찾고 **17행을 새 번호로
   더 넣었다** (98~106). 어느 곡도 참조하지 않는 고아였고, 온보딩 화면에 같은 분위기가
   두 칩으로 떴다. 정리 절차가
   [`migrate-mood-codes-2026-09-21.sql`](../domain/migrate-mood-codes-2026-09-21.sql) 이다.
2. **2026-09-21.** 같은 일이 반복됐다 — 107~123, 다시 124~132. **배포가 끝나도 옛 파드가
   살아 있는 동안 기동마다 늘어난다.**

두 번 다 원인이 같다. 부트스트랩이 **"이 이름이 있나" 로 판단**했기 때문이다. 이름 비교를
아무리 촘촘히 해도, 이름을 못 알아보는 판이 하나라도 떠 있으면 새 번호가 생긴다.

## 결정

**무드 17종의 `code_id` 32~48 과 그 자리의 값을 동결한다.**

| code_id | code_name | display_name | |
|---|---|---|---|
| 32 | `serene` | 잔잔한 | 화면 디자인 대분류 8종 |
| 33 | `uplifting` | 벅찬 | |
| 34 | `ethereal` | 몽환적인 | |
| 35 | `energetic` | 신나는 | |
| 36 | `focused` | 집중되는 | |
| 37 | `emotional` | 감성적인 | |
| 38 | `intense` | 강렬한 | |
| 39 | `romantic` | 설레는 | |
| 40 | `melancholic` | 애상적인 | taxonomy 9종 (2026-09-17 추가) |
| 41 | `warm` | 따뜻한 | |
| 42 | `dreamy` | 몽글한 | |
| 43 | `bright` | 화사한 | |
| 44 | `hopeful` | 희망찬 | |
| 45 | `powerful` | 웅장한 | |
| 46 | `dark` | 어두운 | |
| 47 | `calm` | 고요한 | |
| 48 | `exciting` | 짜릿한 | |

세 가지가 따라온다.

**① 판단 기준은 이름이 아니라 번호다.** `BootstrapRunner.reconcileMoodCodes()` 는 32~48
**각 번호**를 보고, 그 자리가 위 표와 다르면 그 자리에만 쓴다 — 있으면 `UPDATE`, 없으면
**번호를 지정해** `INSERT` 한다. 자동 채번을 쓰지 않으므로 **새 번호가 생길 길이 없다.**
`seedCodes()` 는 MOOD 를 아예 건너뛴다 (거기서 넣으면 자동 채번이다).

**② 32~48 밖의 MOOD 는 건드리지 않는다.** 곡을 추가로 전처리하면서 무드가 늘 수 있고,
그 번호는 이 배치가 정하지 않는다. 읽지도, 지우지도, 고치지도 않는다.

**③ 배치는 지우지 않는다.** 32~48 을 다른 용도 코드가 점유했거나, 넣으려는 이름을 다른
번호가 쥐고 있으면(`UNIQUE (code_type, code_name)`) **손대지 않고 `log.error` 만 남긴다.**
참조가 걸린 행을 배치가 밀어붙이면 사용자의 온보딩 답이 조용히 다른 뜻이 된다.

## 쓰는 쪽 규칙 — FE·BE 는 32~48 만 쓴다

동결은 "이 표를 바꾸지 마라" 로 끝나지 않는다. **읽는 쪽이 용도(`code_type = 'MOOD'`)로만
고르면 중복분이 그대로 딸려 나온다.** 실제로 그렇게 터졌다 — 온보딩 선택지가 MOOD 전량을
내놓아 같은 분위기가 두 칩으로 떴고, 프런트의 이름→번호 변환은 `new Map(...)` 이라
**나중 항목(124~132)이 앞 항목(40~48)을 덮어써서**, `고요한` 을 고른 사람이 47 이 아니라
132 를 보냈다. 그 답이 `onboarding_survey` 에 쌓여 손으로 이관해야 했다
([migrate-onboarding-survey-mood-124-132-2026-09-21.sql](../domain/migrate-onboarding-survey-mood-124-132-2026-09-21.sql)).

### 규칙

1. **무드 `code_id` 는 32~48 안의 값만 주고받는다.** FE·BE 어느 쪽이든, 읽기든 쓰기든.
2. **32~48 밖의 무드 번호는 DB 에 있어도 쓰지 않는다.** 중복분은 "아직 안 지운 쓰레기" 지
   "쓸 수 있는 번호" 가 아니다. 팀원의 로컬 DB 는 정리 시점이 제각각이라 **로컬에 보인다는
   것이 근거가 되지 않는다.**
3. **새 무드는 preprocess 가 새 `code_id` 를 발급할 때만 생긴다.** 기존 번호를 주워
   쓰거나, 손으로 `codes` 에 INSERT 해 쓰지 않는다.
4. **발급된 새 번호를 온보딩 선택지에 넣으려면** `MoodCodeIds` 의 구간을 넓힌다 —
   위 확인 절차를 거친다. 넓히기 전까지 그 무드는 곡에 붙어 탐색 화면에는 나타나지만
   온보딩에는 나오지 않는다. **이것이 의도한 동작이다.**

### 경계를 실제로 거는 곳

| 곳 | 무엇을 막나 |
|---|---|
| `api/.../code/MoodCodeIds.java` | 구간을 한 곳에만 적는다 (`FIRST`=32 · `LAST`=48 · `isFrozen`) |
| `OnboardingServiceImpl.moodOptions()` | 선택지를 32~48 로 좁혀 내놓는다. 용도로만 고르지 않는다 |
| `OnboardingServiceImpl.resolve()` | 32~48 밖 번호로 온 제출을 400 으로 막는다 |
| `frontend/lib/onboarding.ts` 의 `idsOf` | 같은 이름이 여러 번호로 와도 **가장 작은 번호**를 쓴다 |
| `BootstrapRunner.reconcileMoodCodes()` | 32~48 을 규격대로 유지하고, 그 밖은 건드리지 않는다 |

**왜 네 겹인가.** 하나만으로는 안 샌다는 보장이 없다. 서버 선택지를 좁혀도 옛 화면을
띄워 둔 탭·캐시된 번들·손으로 부르는 요청은 옛 번호를 보낸다(그래서 `resolve`). 서버를
고쳐도 팀원의 로컬은 옛 배포본일 수 있다(그래서 `idsOf`). 이 결함은 **오류가 나지 않아**
새는 것을 알아채는 데 며칠이 걸렸다.

## 한 값이 다섯 곳에 걸려 있다

표를 고치면 이 다섯 곳이 **동시에** 맞아야 한다. 하나라도 빠지면 조용히 어긋난다 —
오류가 나지 않는 것이 이 결함의 성질이다.

| 곳 | 무엇이 걸려 있나 | 어긋나면 |
|---|---|---|
| `codes` 32~48 (운영 DB) | 번호 그 자체 | 아래 네 표의 참조가 다른 뜻이 된다 |
| `onboarding_survey.mood_code_id` | 사용자가 고른 답 | **복구 불가.** 다른 분위기를 고른 것으로 읽힌다 |
| `music_moods` · `music_section_analysis_mood` · `daily_recommendations` | 적재·추천 결과 | 추천이 엉뚱한 분위기로 나간다 |
| `ai_server/app/onboarding.py` 의 `MOOD_MAP` | `code_name` 을 **키로** 받는다 (`CodeOption::name`) | 그 사용자의 취향이 추천에 반영되지 않는다 |
| `frontend/lib/mupid-data.ts` 의 `moodOptions` | 화면에 그릴 글자 | 온보딩에 영문·한국어가 섞이거나 칩이 둘로 보인다 |

`code_name` 은 **영문 내부 코드**, `display_name` 은 **화면용 한국어**다. 이 방향을
뒤집지 않는다 — 2026-09-21 에 장르 45종과 같은 방향으로 맞춘 결과다.

**표시명끼리 겹쳐서도 안 된다.** 정리 전 45(`강렬한`)·48(`신나는`) 이 38·35 와 화면에서
같은 글자였고, 사용자에게는 같은 칩이 둘로 보였다.

## 결과

**얻은 것.** 옛 파드가 떠 있어도 무드 번호가 늘지 않는다. 빈 DB 로 띄운 환경과 운영의
번호가 같아, 덤프를 옮겨 붙여도 어긋나지 않는다. 32~48 이 이미 맞으면 SQL 한 문장도
실행하지 않아 사람이 만든 상태를 건드릴 일이 없다.

**받아들인 비용.**

- **표시명을 운영 DB 에서 고쳐 두면 기동 때 코드 값으로 되돌아간다.** 무드에 한해
  코드가 기준이다 — 위 다섯 곳이 맞물려 있어 DB 한 곳만 다른 상태를 허용할 수 없다.
  화면 글자를 바꾸려면 `CODE_SEEDS` 를 고쳐 배포한다 (그 전에 이 문서의 확인 절차).
- **무드를 18번째로 늘리려면 번호를 사람이 정해야 한다.** 49 를 쓸지, 전처리가 쓰는
  번호대를 따로 둘지는 이 ADR 이 정하지 않았다. 그때 새 ADR 을 쓴다.
- **지난 중복 행(98~106 · 107~123 · 124~132)은 배치가 치우지 않는다.** 참조를 보고
  사람이 정리한다 — 절차는
  [`migrate-mood-codes-2026-09-21.sql`](../domain/migrate-mood-codes-2026-09-21.sql) 와 같다.

## 지금 상태를 확인하는 법

```sql
SELECT code_id, code_name, display_name FROM codes
WHERE code_type = 'MOOD' ORDER BY code_id;
```

기대: 32~48 이 위 표와 **글자까지 같다.** 그 밖의 번호가 보이면 전처리가 늘린 무드이거나
지난 중복분이다 — 어느 쪽인지 참조로 가른다.

```sql
-- 32~48 밖의 무드를 실제로 쓰는 곳이 있나. 0이면 고아다.
SELECT 'music_moods' AS t, COUNT(*) FROM music_moods WHERE mood_code_id NOT BETWEEN 32 AND 48
UNION ALL SELECT 'section', COUNT(*) FROM music_section_analysis_mood WHERE mood_code_id NOT BETWEEN 32 AND 48
UNION ALL SELECT 'onboarding', COUNT(*) FROM onboarding_survey WHERE mood_code_id NOT BETWEEN 32 AND 48
UNION ALL SELECT 'daily', COUNT(*) FROM daily_recommendations WHERE mood_code_id NOT BETWEEN 32 AND 48;
```

## 근거

`api/src/main/java/com/osundosun/mupid/api/bootstrap/BootstrapRunner.java`
(`CODE_SEEDS` · `MOOD_CODE_ID_BASE` · `reconcileMoodCodes` · `mismatchedMoodIds` · `writeMoodTarget`)
`api/src/test/java/com/osundosun/mupid/api/bootstrap/BootstrapSeedTest.java`
`api/knowledge/domain/seed-codes.sql`
`api/knowledge/domain/migrate-mood-codes-2026-09-21.sql`
`api/knowledge/domain/migrate-onboarding-survey-mood-124-132-2026-09-21.sql`
`api/src/main/java/com/osundosun/mupid/api/code/MoodCodeIds.java`
`api/src/main/java/com/osundosun/mupid/api/onboarding/service/OnboardingServiceImpl.java` (`moodOptions` · `resolve`)
`frontend/lib/onboarding.ts` (`idsOf`)
`api/knowledge/domain/erd.sql` (`codes`, `UNIQUE (code_type, code_name)`, FK 네 개)
`frontend/lib/mupid-data.ts`
`ai_server/app/onboarding.py`
