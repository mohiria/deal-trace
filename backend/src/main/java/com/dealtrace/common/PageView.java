package com.dealtrace.common;

import java.util.List;

/**
 * 通用分页信封 {@code { items, total, page, size }}。
 *
 * <p>与既有 {@code SystemLogPageView} 同形，但泛型化供 customer / lead 列表复用；
 * {@code total} 为当前查询（含 keyword 过滤）命中的总条数，{@code page}/{@code size} 回显生效分页参数。
 */
public record PageView<T>(
    List<T> items,
    long total,
    int page,
    int size
) {
    public static <T> PageView<T> of(List<T> items, long total, int page, int size) {
        return new PageView<>(items, total, page, size);
    }
}
