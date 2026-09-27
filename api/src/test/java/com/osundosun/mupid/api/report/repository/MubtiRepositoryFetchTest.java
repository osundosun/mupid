package com.osundosun.mupid.api.report.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import com.osundosun.mupid.api.report.enumerate.MubtiClass;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.EntityGraph;

/**
 * 유형 조회가 이미지 행을 함께 읽는지 (S15P21A204-463). **인프라 없이 돈다.**
 *
 * <p>전날 리포트 배치는 트랜잭션 밖에서 `Mubti.imageFile` 을 읽는다. 이 선언이 빠지면 배치만
 * `LazyInitializationException` 으로 조용히 실패하고, 화면 요청은 open-in-view 에 가려 멀쩡해
 * 보인다 — 운영 로그로만 드러난다.
 */
class MubtiRepositoryFetchTest {

    @Test
    @DisplayName("네 축으로 찾는 조회는 imageFile 을 함께 끌어온다")
    void findByClassesFetchesImageFile() throws NoSuchMethodException {
        Method find = MubtiRepository.class.getMethod("findByClassAAndClassBAndClassCAndClassD",
            MubtiClass.A.class, MubtiClass.B.class, MubtiClass.C.class, MubtiClass.D.class);

        EntityGraph graph = find.getAnnotation(EntityGraph.class);

        assertThat(graph).as("@EntityGraph 가 없다").isNotNull();
        assertThat(graph.attributePaths()).contains("imageFile");
    }
}
