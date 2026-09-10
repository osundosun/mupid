# 파일 속성

줄바꿈과 실행 권한. Windows·Linux 가 섞인 팀이라 어긋나면 조용히 깨진다.

[← 컨벤션](README.md) · [지식 저장소](../README.md)

---

## 파일 속성

### 줄바꿈

루트 `.gitattributes` 에 `* text=auto` 가 **없다.** 전역 정규화를 켜지 않고, 깨지면 실행이 안 되는 경로만 골라 LF 를 강제한다.

```
.husky/**        text eol=lf   # Windows 에서도 LF 여야 sh 가 실행할 수 있다
*.sh             text eol=lf
api/gradlew      text eol=lf
infra/deploy/*.py text eol=lf
```

`api/.gitattributes` 는 추가로 `/gradlew text eol=lf`, `*.bat text eol=crlf`, `*.jar binary`.

Windows 개발 환경이라 나머지는 손대지 않는 쪽을 택한 것이다. **새 셸/파이썬 진입점을 추가하면 이 목록도 갱신한다.**

### 실행 권한

실행 비트(`100755`)가 붙은 추적 파일은 3개뿐이다.

```
api/clickhouse/init/apply-ddl.sh
api/clickhouse/kafka/create-topics.sh
api/clickhouse/verify.sh
```

`infra/` 아래 셸 스크립트 9개는 전부 `100644` 다. CI 가 `sh infra/ci/deploy.sh`, `bash gradlew` 처럼 인터프리터를 명시하는 이유다. Windows 체크아웃에서는 실행 비트가 유지되지 않으므로, 새 스크립트를 추가하면 `git update-index --chmod=+x` 로 비트를 넣거나 호출부에 `sh` 를 붙인다.
