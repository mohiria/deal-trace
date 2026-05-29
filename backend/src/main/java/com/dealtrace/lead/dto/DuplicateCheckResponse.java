package com.dealtrace.lead.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 查重预检响应（spec R6）。
 *
 * <p>{@code blockingReason} 取 {@code DUPLICATE_ACTIVE_LEAD} 或 {@code DUPLICATE_WON_LEAD}
 * 字符串常量；canCreate=true 时为 null。
 *
 * <p>{@code historicalLost} 列出该三元组下所有已流失线索；lead-core 阶段
 * 该数组始终为空（lose_reason / lost_at 由 lead-closure 写入）。
 */
public record DuplicateCheckResponse(
    boolean canCreate,
    String blockingReason,
    List<HistoricalLost> historicalLost
) {
    public record HistoricalLost(
        LocalDateTime lostAt,
        String loseReason,
        String loseNote
    ) {
    }
}
