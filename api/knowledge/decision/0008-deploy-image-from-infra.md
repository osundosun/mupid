# 0008. 배포 이미지는 `infra/image/Dockerfile` 로 굽는다

**상태** 채택 · **가장 헷갈리는 지점**

## 맥락

`api/` 안에 Dockerfile 과 compose 가 있어서, 그것이 배포되는 것이라고 읽기 쉽다. 실제로는 아니다.

## 결정

역할을 나눈다.

| 파일 | 실제 용도 |
|---|---|
| `api/Dockerfile` | 로컬·검증용 |
| `api/compose.yml` | 개발 인프라 (스프링은 호스트에서 직접 실행) |
| `api/compose.prod.yml` | 운영 형태 검증용 (스프링까지 컨테이너) |
| **`infra/image/Dockerfile`** | **실제 배포되는 이미지** |
| **`infra/deploy/compose.yml`** | **실제 EC2 가 쓰는 스택** |
| **`infra/deploy/nginx.conf`** | **실제 EC2 의 nginx 설정** |

## 결과

- **`api/Dockerfile` 이나 `api/nginx/` 만 고치면 운영에 반영되지 않는다.** 이 저장소에서 가장 밟기 쉬운 함정이다.
- 로컬 검증과 실제 배포가 다른 파일을 쓰므로, 둘 사이에 드리프트가 생길 수 있다. 한쪽을 고치면 다른 쪽도 봐야 한다.
- `api/compose.ci.yml` 은 현재 어디서도 참조되지 않는다.

## 근거

`infra/image/Dockerfile`, `infra/deploy/compose.yml`, `infra/deploy/nginx.conf`, `infra/ci/`, [architecture/deployment.md](../architecture/deployment.md)
