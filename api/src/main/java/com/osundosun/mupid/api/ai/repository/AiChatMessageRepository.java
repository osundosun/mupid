package com.osundosun.mupid.api.ai.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.osundosun.mupid.api.ai.entity.AiChatMessage;

public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, Integer> {

    /**
     * 그 세션에 이미 쌓인 마지막 순서. **한 마디도 없으면 `-1`** 이다.
     *
     * <p>다음 순서는 이 값 + 1 이다. 세는 대신 최댓값을 묻는 이유는, 세션 안에서
     * 순서가 빠져도 다음 값이 겹치지 않게 하려는 것이다. `content_blocks` 처럼 나중에
     * 들어온 기능이 중간 순서를 만들면 `COUNT(*)` 는 기존 값과 충돌한다.
     *
     * <p>네이티브 쿼리인 이유는 두 가지다. 컬럼명이 `` `index` ``(ERD 가 백틱을 요구한다)이고,
     * HQL 은 `index` 를 컬렉션 내장 함수 이름으로도 쓴다. 엔티티가 이미
     * `@Column(name = "\`index\`")` 로 매핑하고 있어 테이블·컬럼명을 그대로 적는 편이
     * 안전하다. `COALESCE` 가 null 을 막아 주므로 반환 타입을 `int` 로 둘 수 있다.
     */
    @Query(value = "SELECT COALESCE(MAX(`index`), -1) FROM ai_chat_messages"
        + " WHERE session_id = :sessionId", nativeQuery = true)
    int findMaxIndexBySessionId(@Param("sessionId") int sessionId);
}
