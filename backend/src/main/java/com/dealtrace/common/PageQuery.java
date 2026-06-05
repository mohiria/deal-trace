package com.dealtrace.common;

/**
 * 列表端点统一分页参数解析与裁剪（design D1）。后端各列表端点共用，避免各处重复 clamp。
 *
 * <ul>
 *   <li>{@code page}：null 或 &lt;1 视为 1。</li>
 *   <li>{@code size}：null 取默认 20；否则 clamp 到 [1,100]（防 size 过大拖垮库）。</li>
 *   <li>{@code keyword}：trim 后空白视为无（{@code null}）。</li>
 * </ul>
 */
public record PageQuery(int page, int size, String keyword) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public static PageQuery of(Integer page, Integer size, String keyword) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = size == null ? DEFAULT_SIZE : Math.min(Math.max(size, 1), MAX_SIZE);
        String k = keyword == null ? null : keyword.strip();
        if (k != null && k.isEmpty()) {
            k = null;
        }
        return new PageQuery(p, s, k);
    }

    /** keyword trim 后是否非空。 */
    public boolean hasKeyword() {
        return keyword != null;
    }

    /** SQL OFFSET = (page-1)*size。 */
    public long offset() {
        return (long) (page - 1) * size;
    }
}
