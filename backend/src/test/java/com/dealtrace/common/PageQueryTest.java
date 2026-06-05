package com.dealtrace.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 分页参数归一/clamp 纯单元测试（design D1）。 */
class PageQueryTest {

    @Test
    void defaults_whenNull() {
        PageQuery q = PageQuery.of(null, null, null);
        assertThat(q.page()).isEqualTo(1);
        assertThat(q.size()).isEqualTo(20);
        assertThat(q.keyword()).isNull();
        assertThat(q.hasKeyword()).isFalse();
    }

    @Test
    void page_clampedToAtLeastOne() {
        assertThat(PageQuery.of(0, 20, null).page()).isEqualTo(1);
        assertThat(PageQuery.of(-5, 20, null).page()).isEqualTo(1);
        assertThat(PageQuery.of(3, 20, null).page()).isEqualTo(3);
    }

    @Test
    void size_clampedToRange() {
        assertThat(PageQuery.of(1, 0, null).size()).isEqualTo(1);
        assertThat(PageQuery.of(1, 1000, null).size()).isEqualTo(100);
        assertThat(PageQuery.of(1, 50, null).size()).isEqualTo(50);
    }

    @Test
    void keyword_blankBecomesNull_andTrimmed() {
        assertThat(PageQuery.of(1, 20, "   ").keyword()).isNull();
        assertThat(PageQuery.of(1, 20, "  建筑  ").keyword()).isEqualTo("建筑");
        assertThat(PageQuery.of(1, 20, "建筑").hasKeyword()).isTrue();
    }

    @Test
    void offset_computed() {
        assertThat(PageQuery.of(1, 20, null).offset()).isEqualTo(0L);
        assertThat(PageQuery.of(2, 20, null).offset()).isEqualTo(20L);
        assertThat(PageQuery.of(3, 25, null).offset()).isEqualTo(50L);
    }
}
