# 0017. 온보딩 설문은 분위기 × 장르 조합으로 저장한다

**상태** 채택 · 구현 완료 · **더 나은 스키마를 팀에 제안 중** ([open-questions C6](../open-questions.md))

## 맥락

`onboarding_survey` 는 이렇게 생겼다.

```sql
CREATE TABLE `onboarding_survey` (
  `answer_id`     INT AUTO_INCREMENT NOT NULL,
  `user_id`       INT NOT NULL,
  `mood_code_id`  INT NOT NULL,
  `genre_code_id` INT NOT NULL,
  `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`answer_id`)
) COMMENT='온보딩 문항별 응답';
```

한 줄이 "문항별 응답" 이라는데, 분위기와 장르가 **둘 다 NOT NULL** 이다. 한 문항의
답 하나를 담으려면 나머지 한 칼럼에 넣을 값이 없다.

화면(`frontend/components/mupid/collection.tsx` 의 `WelcomeView`)은 분위기와
장르를 **각각 여러 개** 고르게 한다. 둘을 짝지어 묻지 않는다. "잔잔한 Jazz" 같은
쌍을 사용자가 만든 적이 없다.

그래서 집합 두 개를 짝 하나짜리 행에 어떻게 넣을지 정해야 했다.

## 후보

| | 방법 | 줄 수 | 문제 |
|---|---|---|---|
| A | 두 칼럼을 NULL 허용으로 바꾸고 한 줄에 답 하나 | M + G | **ERD 변경.** 팀 합의가 먼저다 ([AGENTS.md](../AGENTS.md)) |
| B | 순서대로 짝짓고 짧은 쪽을 돌려 쓴다 | max(M, G) | 없는 쌍을 만들어 낸다. `(잔잔한, Jazz)` 를 사용자가 고른 것처럼 보인다 |
| C | 모든 조합을 넣는다 | M × G | 줄이 많다 |

## 결정

**C — 모든 조합을 넣는다.**

- 각 칼럼을 `DISTINCT` 하면 사용자가 고른 집합이 **그대로** 복원된다. 손실이 없다.
- 모든 분위기가 모든 장르와 짝지어 있으므로 **짝에 의미가 없다는 사실이 데이터에
  드러난다.** B 는 있지도 않은 선호를 만들어 내는데, 나중에 추천 학습이 이 표를
  읽으면 그대로 잘못된 신호가 된다.
- 줄 수는 화면 선택지가 각각 8개라 한 사람 최대 64줄이다. 서비스 코드에
  `MAX_SELECTION = 16` 상한을 두어 조합이 터지지 않게 막았다.

`OnboardingServiceImpl.getResult` 가 읽을 때 중복을 제거하므로 **API 표면에는
조합이 드러나지 않는다.** 보낸 그대로 돌아온다.

## 따라온 것

- 분위기와 장르 **모두 하나 이상**이 필수다. 한쪽이 비면 만들 행이 없다. 그래서
  온보딩 화면에 장르 선택을 추가했다(원래 분위기만 물었다). 화면 디자인
  (`prototype/components/mupid-app.tsx`)은 처음부터 장르·분위기를 둘 다 묻고 있었다.
- ~~다시 제출하면 이전 응답을 전부 지우고 새로 넣는다.~~ **아래 "고친 것" 참고.**
- `codes` 표가 비어 있었다. 비면 `GET /api/v1/onboarding/option` 이 503 이다.
  지금은 기동 때 `bootstrap/BootstrapRunner` 가 빠진 코드를 채운다. 같은 목록의
  수동 스크립트는 [domain/seed-codes.sql](../domain/seed-codes.sql).

## 고친 것 — 재제출을 막는다 (2026-09-15)

처음에는 다시 제출하면 이전 응답을 통째로 갈아 끼웠다. 지금은 **계정당 한 번**
이고 두 번째 제출은 `ONBOARDING_ALREADY_SUBMITTED`(409) 다.

이 설문은 **이력이 없는 사람의 첫 추천을 만드는 콜드스타트 입력**이지 계속
고쳐 가며 쓰는 취향 설정이 아니다. 그렇게 보면 조합 저장의 약점도 사라진다 —
부분 수정이 필요 없으니 "갈아 끼우기밖에 못 한다" 가 문제가 아니게 된다.

같이 걷어낸 것:

- **본인 조회(`GET /api/v1/onboarding`)를 없앴다.** 화면이 답을 되돌려 받을 일이
  없다. 진입 분기에 필요한 "마쳤는가" 는 `GET /api/v1/user` 의
  `onboarding_submitted` 로 보낸다. 제출 **내용**을 읽는 것은 추천 서버뿐이고
  `ROLE_SYSTEM` 전용 `GET /api/v1/user/{userId}/onboarding` 을 쓴다.
- **마이페이지의 취향 편집 칩을 지웠다.** 칩을 누를 때마다 온보딩을 재제출하고
  있어서, 서버가 막으면 누를 때마다 실패했다.
- **설문이 닉네임을 받지 않는다.** 회원가입이 이미 2~10자로 받고 있었다.
  온보딩 화면도 3단계(닉네임·장르·분위기)에서 2단계로 줄었다.

아래 "팀에 제안할 것" 은 그대로 남는다. 한 번만 쓴다고 해서 `(mood, genre)`
한 쌍이 한 문항의 답이 되는 것은 아니다.

## 팀에 제안할 것

A 로 가면 줄 수가 M + G 로 줄고 "문항별 응답" 이라는 표 주석과도 맞는다. 바꾸려면
`mood_code_id` · `genre_code_id` 를 NULL 허용으로 내리면 된다. 마이그레이션은
조합에서 각 축을 `DISTINCT` 해 다시 넣으면 되고, 값 손실이 없다.

**ERD 변경이라 팀에 먼저 묻는다.** 그 전까지는 이 문서의 결정대로 둔다.
