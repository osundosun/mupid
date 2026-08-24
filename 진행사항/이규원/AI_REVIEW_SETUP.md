# Gemini MR 자동 리뷰 설정

이 설정은 `study/이규원/*` 브랜치에서 생성한 Merge Request만 자동으로 검토한다.
Gemini가 리뷰를 생성하면 `ai-review-bot`이 MR 댓글로 등록하고, GitLab 알림을 통해 이메일로 받을 수 있다.

## 필요한 CI/CD 변수

GitLab의 `Settings > CI/CD > Variables`에서 아래 변수를 생성한다.

| 변수 | 용도 | 권장 설정 |
| --- | --- | --- |
| `GEMINI_API_KEY` | Gemini API 호출 | Masked and hidden, Protected |
| `GITLAB_REVIEW_TOKEN` | MR 리뷰 댓글 등록 | Masked and hidden, Protected |
| `GEMINI_MODEL` | 사용할 모델 변경(선택) | 기본값 `gemini-3.7-flash` |

`GITLAB_REVIEW_TOKEN`에는 프로젝트 범위의 Project Access Token을 사용한다.

- 이름: `ai-review-bot`
- 역할: Developer
- 범위: `api`
- 만료일: 학습 기간 이후의 가까운 날짜

토큰과 API 키는 저장소 파일이나 CI 로그에 직접 작성하지 않는다.

## 권장 브랜치 흐름

```text
study/이규원/day01-react-typescript
                ↓ Merge Request
study/이규원/main
```

민감한 CI/CD 변수를 `Protected`로 사용할 수 있도록 `study/이규원/*` 브랜치를 보호하고,
Push와 Merge 권한을 Maintainer로 제한한다. 소스와 대상 브랜치가 모두 보호되어야
Merge Request 파이프라인에서 보호된 변수를 사용할 수 있다.

## 실행 조건

- Merge Request 파이프라인에서만 실행
- 소스 브랜치가 `study/이규원/`으로 시작할 때만 실행
- AI 리뷰 실패가 MR 병합을 막지 않도록 `allow_failure: true` 사용
- 같은 커밋에는 리뷰 댓글을 한 번만 등록
- `package-lock.json`, 빌드 결과물, 의존성 폴더는 리뷰 diff에서 제외
