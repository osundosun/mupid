# 문서 관리

회고 기록 규칙과 문서 갱신 의무.

[← 컨벤션](README.md) · [지식 저장소](../README.md)

---

## 기록 문서

### 회고

`회고/README.md` — 날짜별 폴더를 만들지 않고 `회고/YYYY-MM-DD.md` 파일 하나에 기록한다.

실제 파일 서식 (`회고/2026-08-28.md`, `회고/2026-09-04.md`).

```markdown
# 📝 주간 회고 취합 (YYYY-MM-DD)

🏫 **2반**\
👥 **A204팀**

---

## 🔎 금주 회고

*(참여 인원에 맞춰 가나다순으로 이름을 작성해 주세요.)*

### 👤 이름
- **Keep:** …
- **Problem:** …
- **Try:** …

---

## 🎯 차주 계획

### 👤 이름
- …
```

개인 항목은 Keep / Problem / Try 를 쓰지만 강제는 아니다 — 두 파일 모두 일부 인원은 단순 불릿으로 적었다.

취합 커밋은 봇 저자로 남는다.

```
저자: A204 회고 봇 <a204-retro-bot@noreply.local>
제목: docs(retro): YYYY-MM-DD 주간 회고 N명 취합
```

이 저자는 커밋 훅 2단계의 예외라 티켓이 붙지 않는다. 현재 20건이다.

제목 형식은 하나로 통일돼 있지 않다. 위 취합 형식이 11건, `docs(retro): 2026-08-25 이규원 회고 갱신` 류의 개인 갱신이 6건, 영문 제목이 3건(`docs(retro): store September 4 retrospective as a dated markdown file` 등)이다.


## 문서 갱신 의무

무언가를 바꿨을 때 같이 손대야 하는 것.

| 바꾼 것 | 같이 갱신할 것 | 근거 |
|---|---|---|
| 엔드포인트 추가 | `api/docs/` Bruno 컬렉션에 요청 파일 추가 | `api/AGENTS.md` |
| 설정(환경 변수) 추가 | `api/.env.example` (값은 비우고 키·주석 섹션 유지) | `api/AGENTS.md` |
| 커스텀 프로퍼티 추가 | `src/main/resources/META-INF/additional-spring-configuration-metadata.json` | `api/AGENTS.md` |
| CI job 추가 | 관련 job 들의 `rules.changes` 경로 목록 | `infra/ci/*.yml` |
| 셸/파이썬 진입점 추가 | 루트 `.gitattributes` LF 목록, 실행 비트 | `.gitattributes` |
| 운영 상태 변경 | `infra/README.md` / `infra/OPERATIONS.md` / `infra/PROGRESS.md` | 아래 |

`.env` 는 커밋하지 않는다. `api/.gitignore` 1행이 `.env` 이며 `git check-ignore -v api/.env` 도 `api/.gitignore:1:.env` 를 돌려준다 — 다만 `api/AGENTS.md` 와 `api/README.md` 는 반대로 적고 있으니 그 문구를 근거로 행동하지 않는다 ([함정과 실패 사례](../failure/git-and-convention.md)).

### 문서를 갱신하는 방식

이 저장소는 낡은 문서를 지우지 않고 **뒤쪽 절이 앞쪽을 무효화한다고 선언하는** 방식을 쓴다.

- `infra/README.md` 마지막 절 "운영 완료 상태 (2026-09-07 갱신)" — "이 갱신은 위 초기 단계의 미완료 표기를 대체한다"
- `frontend/TEAM-HANDOFF.md` 마지막 줄 — "기존 README의 자산 제작 기록 및 개인 배포 관련 문구는 과거 이력이며, 팀 전달 범위는 이 문서를 기준으로 확인해 주세요"

즉 같은 주제를 두 문서가 다르게 말하면 **더 구체적이고 나중에 쓰인 쪽**이 기준이다. 프론트엔드는 `frontend/README.md` 가 아니라 `frontend/TEAM-HANDOFF.md`, API 규약은 `api/README.md` 가 아니라 `api/docs/opencollection.yml` 이다.
