package com.osundosun.mupid.api.music.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MusicSearchQueryTest {

    @Test
    void booleanQueryRequiresEveryNormalizedWordAndSupportsPrefixes() {
        assertThat(MusicServiceImpl.toBooleanPrefixQuery("  Blue-hour 라이브! "))
            .isEqualTo("+Blue* +hour* +라이브*");
    }

    @Test
    void booleanQueryRemovesOperatorsInsteadOfPassingThemToMysql() {
        assertThat(MusicServiceImpl.toBooleanPrefixQuery("+봄 -(remix)"))
            .isEqualTo("+봄* +remix*");
    }
}
