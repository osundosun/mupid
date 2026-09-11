# 테스트 규약

무엇이 실제로 검사되는지 알고 쓴다.

[← 컨벤션](README.md) · [지식 저장소](../README.md)

---

## 테스트

### 백엔드

테스트는 `api/src/test/java/com/osundosun/mupid/api/ApiApplicationTests.java` 의 `contextLoads()` 하나뿐이다 (`@SpringBootTest`). `api/build.gradle` 에 `tasks.named('test') { useJUnitPlatform() }` 가 걸려 있다.

```bash
./gradlew build   # 빌드 + 테스트
./gradlew test    # 테스트만
```

작업 디렉터리는 항상 `api/`. **CI 는 `-x test` 로 건너뛰므로 로컬에서 직접 돌려야 한다.**

CI 에서 테스트를 뺀 것은 팀 결정이고 과정이 이력에 남아 있다 — `fix(ci): supply isolated backend test environment values` 로 테스트용 서비스를 CI 에 띄워 보다가 `chore(ci): skip backend tests and remove test-only services` 로 빌드만 남겼다. `infra/README.md` 스스로 "현재 자동 테스트는 1개이므로 기능 전체 검증을 의미하지 않는다"라고 한계를 적어 뒀다.

### 프론트엔드

`frontend/tests/` 에 `node:test` 기반 `.test.mjs` 21개가 추적되고 있다. `frontend/package.json` 의 scripts 는 `dev`/`build`/`start`/`lint`/`format` 뿐이라 `npm test` 가 없다. 실행 방법은 `frontend/README.md` 의 한 줄이 유일하다.

```bash
npx tsc --noEmit --incremental false
npx oxlint hooks/use-mupid.ts components/mupid-app.tsx components/mupid lib/mupid-data.ts
node --experimental-strip-types --test
npm run build
```

CI 는 이 중 `npm run build` 만 돈다. 관련 함정은 [함정과 실패 사례](../failure/git-and-convention.md).

