# 0007. nginx 설정 템플릿 하나를 개발·운영이 공유한다

**상태** 채택 · 적용 범위는 `api/` 스택 한정

## 맥락

개발과 운영에서 nginx 설정이 갈라지면 "로컬에서는 되는데 배포하면 안 되는" 라우팅 문제가 생긴다.

## 결정

`api/nginx/templates/default.conf.template` **한 장**을 개발·운영이 공유한다. 환경 차이는 `API_UPSTREAM` 환경변수 하나로만 준다 (개발 `host.docker.internal:8800`, 운영 `api:8800`). 치환은 nginx 공식 이미지의 envsubst 가 하고, 범위를 `NGINX_ENVSUBST_FILTER: "^API_UPSTREAM$$"` 로 좁혔다.

## 결과

- 라우팅 규칙이 한 곳에만 있다.
- envsubst 범위를 좁혔기 때문에 **nginx 변수는 `$host` 처럼 중괄호 없이 써야 한다.** `${host}` 로 쓰면 치환 대상으로 오인돼 빈 값이 된다.
- **이 결정은 실제 EC2 배포에는 적용되지 않는다.** 배포된 서버가 쓰는 nginx 설정은 `infra/deploy/nginx.conf` 이고 이 템플릿과 별개다. → [0008](0008-deploy-image-from-infra.md)

## 근거

`api/nginx/templates/default.conf.template`, `api/compose.yml`, `api/compose.prod.yml`, [failure/docker-and-nginx.md](../failure/docker-and-nginx.md)
