# 0004. 로그아웃을 Redis 차단 목록으로 구현한다

**상태** 채택

## 맥락

JWT 는 서버가 상태를 갖지 않는 것이 장점이지만, 그래서 발급한 토큰을 만료 전에 무효화할 수 없다. 로그아웃이 실제로 동작하려면 무효화 수단이 필요하다.

## 결정

Redis 에 **차단 목록(deny list)** 을 둔다. 로그아웃 시 해당 토큰을 등록하고, 남은 유효 기간만큼 TTL 을 준다. 인증 필터가 매 요청에서 차단 여부를 확인한다.

## 결과

- 로그아웃이 즉시 반영된다.
- **이 Redis 계층은 범용 캐시가 아니다.** 조회 결과 캐싱도, 이메일 인증코드 저장도 여기 없다. "캐시니까 기본 TTL 이 있겠지 / 무효화 API 가 있겠지" 라는 가정은 전부 어긋난다.
- Redis 가 죽으면 인증 경로가 영향을 받는다. 가용성이 인증의 전제가 됐다.
- TTL 계산이 토큰 수명에 의존하므로, 토큰 수명을 바꾸면 차단 목록 동작도 함께 검토해야 한다.

## 근거

`api/src/main/java/com/osundosun/mupid/api/cache/`, [architecture/cache-redis.md](../architecture/cache-redis.md), [architecture/auth-security.md](../architecture/auth-security.md)
