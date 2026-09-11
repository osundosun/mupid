# 0012. 프론트엔드를 정적 산출물로 EC2 에 배포한다

**상태** 채택 · **package.json 만 보면 오해하기 쉬움**

## 맥락

`frontend/package.json` 에는 `wrangler`, `@cloudflare/vite-plugin`, `@cloudflare/workers-types`, `@openai/sites-vite-plugin` 이 있고 `start` 스크립트가 `wrangler dev` 다. 이것만 보면 Cloudflare Workers 에 배포된다고 읽게 된다.

## 결정

**Workers 를 쓰지 않는다.** 정적 빌드 산출물을 EC2 로 보낸다.

```
npm run build  →  frontend/dist/client/
  → tar.gz + sha256  → GitLab generic package registry
  → SSH 강제 명령 deploy-front <sha> <checksum>  (mupid-deploy@j15a204.p.ssafy.io)
  → https://j15a204.p.ssafy.io/deployment.txt 가 커밋 SHA 와 같은지 확인
```

CI 가 `MUPID_DEPLOY_TARGET: ec2` 를 명시하고, `infra/` 전체에 `wrangler`·`workers`·`cloudflare` 문자열이 **하나도 없다.**

## 결과

- Workers 관련 의존성은 템플릿 잔재다. 이걸 근거로 Workers 런타임 기능(KV, D1, R2, Durable Objects)을 쓰는 코드를 짜면 배포되지 않는다. `frontend/.openai/hosting.json` 도 `{"d1": null, "r2": null}` 이다.
- 배포 경로가 SSH 강제 명령이라 임의 명령 실행이 아니다. 배포 절차를 바꾸려면 서버 쪽 `deploy-front` 도 함께 바꿔야 한다.
- 배포 검증이 `deployment.txt` 비교로 자동화돼 있다. 이 파일을 없애면 배포 잡이 실패한다.
- SSR/RSC 서버 런타임이 배포되지 않으므로, 서버 컴포넌트에 의존하는 기능은 빌드 타임에 해결돼야 한다.

## 근거

`infra/ci/frontend.yml`, `infra/ci/frontend-release.sh`, `infra/deploy/receive-front.py`, [architecture/frontend.md](../architecture/frontend.md)
