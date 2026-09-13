# 0016. 오디오 등록은 3단계 프로토콜로 하고, 바이트는 스프링을 거치지 않는다

**상태** 채택 · 구현 완료

## 맥락

`music_chunking` 배치가 만든 음원 청크를 서버에 올릴 방법이 필요했다. 곡 하나가 6초 단위 청크 수십 개(3분 곡이면 30개, 각 100KB 남짓)로 쪼개진다.

두 가지를 정해야 했다.

**(1) 한 번에 올릴 것인가, 나눠 올릴 것인가.** 곡 하나를 멀티파트 한 덩어리로 보내면 3MB 를 넘고 긴 곡은 더 커진다. 업로드가 중간에 끊기면 처음부터 다시 보내야 하고, nginx `client_max_body_size` 와 `spring.servlet.multipart.max-request-size` 를 곡 길이에 맞춰 계속 올려야 한다. `StreamStatus` enum 에 `PENDING` / `IN_PROGRESS` / `DONE` / `FAILED` 가 이미 있었는데, 이 상태 기계는 "여러 요청에 걸친 업로드" 를 전제하지 않으면 쓸 데가 없다.

**(2) 바이트가 JVM 을 거쳐야 하는가.** 처음에는 `@RequestParam MultipartFile` 로 받았다. 동작은 했지만 음원은 계속 커지고 개수도 곡 수에 비례해 늘어난다. 청크 하나가 힙에 통째로 올라오는 구조는 오래 못 간다. 다운로드는 이미 `X-Accel-Redirect` 로 nginx 가 스토리지에서 직접 읽어 보내고 스프링은 인증만 한다 — 업로드만 반대로 갈 이유가 없다.

## 결정

**스트림 생성 → 청크 업로드 → 완료 선언** 3단계로 나누고, **청크 바이트는 nginx 가 오브젝트 스토리지로 직접 흘려보낸다.**

| 단계 | 요청 | 처리 주체 | 상태 전이 |
|---|---|---|---|
| 1 | `POST /api/v1/music/{musicId}/stream` | 스프링 | → `PENDING` |
| 2-a | `PUT /upload/chunk/{streamId}/{index}` | **nginx → 스토리지** (스프링은 인증만) | — |
| 2-b | `POST /api/v1/chunk/{streamId}/{index}` | 스프링 | 첫 커밋에서 `PENDING` → `IN_PROGRESS` |
| 3 | `PATCH /api/v1/stream/{streamId}` `{"status":"DONE"}` | 스프링 | → `DONE` |

### 2-a 가 도는 방식

nginx 의 `auth_request` 를 쓴다. `X-Accel-Redirect` 는 **응답** 시점 리다이렉트라 요청 본문이 있는 업로드에는 쓸 수 없다.

1. nginx 가 `PUT /upload/chunk/1/0` 을 받으면, **본문 없이** `GET /api/v1/chunk/1/0/upload-auth` 를 스프링에 서브요청한다 (`proxy_pass_request_body off`).
2. 스프링은 권한(`@SystemRole`)·스트림 상태·청크 순서·용량(`X-Original-Content-Length`)을 검사하고, 200 + `X-Storage-Path: /buckets/mupid/music/1/opus128/chunk_0000.rtp` 를 돌려준다.
3. nginx 가 `auth_request_set` 으로 그 경로를 읽어 본문을 스토리지 업스트림으로 그대로 흘린다 (`proxy_request_buffering off`).

**음원 바이트는 JVM 힙에도, 디스크 임시 파일에도 올라오지 않는다.**

### 부수 규칙

- **1단계에서 `chunk_count` 를 선언하고, 3단계에서 실제 커밋 수와 대조한다.** 맞지 않으면 `MUPID_STREAM_CHUNK_INCOMPLETE` (409) 로 거부한다.
- **`DONE` 이 아닌 스트림은 조회 응답에서 뺀다.** 재생 도중 끊기는 반쪽짜리 스트림을 클라이언트가 보지 못하게 한다.
- **같은 순서를 다시 올리면 덮어쓴다.** 배치가 중간에 죽어도 처음부터 다시 돌릴 수 있어야 한다.
- **같은 음악에 같은 코덱·비트레이트 조합은 하나만 허용한다** (`MUPID_DUPLICATE_STREAM`, 409). 둘이면 어느 쪽을 재생할지 알 수 없다. 다시 올리려면 `DELETE /api/v1/stream/{streamId}` 로 청크·스토리지 파일까지 지운 뒤 1번부터 반복한다.
- **2-b 는 스토리지에 실제로 올라왔는지 확인한 뒤에만 기록한다.** `HEAD`(S3 `headObject`)로 존재와 크기만 보고, 본문은 받지 않는다. 없으면 `MUPID_CHUNK_NOT_UPLOADED`, 크기가 다르면 `MUPID_CHUNK_SIZE_MISMATCH` (둘 다 409).

## 결과

- **음원 크기가 JVM 메모리와 무관해진다.** 업로드 상한을 올리는 데 힙을 늘릴 필요가 없다.
- 실패 단위가 청크 하나다. 30개 중 29번째가 깨져도 그 하나만 다시 보낸다.
- 곡 메타(`music`)와 음원(`streams`/`chunks`)의 수명이 분리된다. 음원만 교체할 때 `music_id` 가 유지되므로 플레이리스트·청취 이력 참조가 끊기지 않는다.
- **`chunks.file_hash` 를 서버가 검증하지 못한다.** 바이트를 보지 않으므로 SHA-256 은 올린 쪽이 신고한 값을 그대로 저장한다. 서버가 대조할 수 있는 것은 크기뿐이다. 무결성 보증 수준이 내려가는 대가를 치른 것이고, 해시는 클라이언트가 다운로드 후 자기 검증용으로 쓰는 값으로 격하됐다.
- **요청 수가 곡당 2N+2 번으로 늘어난다.** 크론 배치라 처리량보다 재시도 가능성이 중요해 받아들인다.
- **nginx 설정에 강하게 묶인다.** `/upload/chunk/` location 과 `auth_request` 가 없으면 업로드 경로 자체가 없다. 스프링만 띄워서는 음원을 올릴 수 없다. 개발 템플릿(`api/nginx/templates/`)과 운영(`infra/deploy/nginx.conf`) **양쪽**에 같은 블록이 있어야 하고, 서버의 `/srv/mupid/config/nginx.conf` 는 CI 가 자동 교체하지 않으므로 사람이 옮겨야 반영된다.
- **운영 네트워크 구성이 바뀐다.** nginx 가 스토리지에 직접 닿아야 해서 `storage` 를 `frontend` 네트워크에도 붙였다(`infra/deploy/compose.yml`). nginx 를 `backend` 에 붙이는 대안은 [AGENTS.md](../../AGENTS.md) 가 금지하고 있고, 그쪽이면 nginx 가 mysql·redis 까지 보게 된다.
- **업로드 실패 사유가 뭉개진다.** nginx `auth_request` 는 서브요청의 401/403 만 그대로 넘기고 나머지 4xx 는 전부 500 으로 바꾼다. 사유는 `X-Mupid-Error` 응답 헤더로 살려 보낸다 (본문은 사라진다).
- 중간 상태가 DB 에 남을 수 있다. 배치가 2단계 도중 죽으면 `IN_PROGRESS` 스트림이 남는다. 조회에는 안 나오지만 `DUPLICATE_STREAM` 때문에 재등록을 막는다. 배치가 실패 시 스스로 `DELETE` 하도록 했지만, 프로세스가 강제 종료되면 수동 정리가 필요하다.

## 관련

- 배치 쪽 구현: `music_chunking/src/music_chunking/registrar.py`, `api_client.py`
- nginx 설정: `api/nginx/templates/default.conf.template`(개발), `infra/deploy/nginx.conf`(운영) — 두 파일이 같은 블록을 가져야 한다 → [0008](0008-deploy-image-from-infra.md)
- 다운로드도 같은 발상이다 (`X-Accel-Redirect`) → [0007](0007-shared-nginx-template.md)
- 스트림·청크는 하드 삭제다. 스토리지 파일만 [0013](0013-soft-delete-policy.md) 대로 `isDeleted` 를 쓴다.
