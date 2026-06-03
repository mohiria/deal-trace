package com.dealtrace.contract.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 合同记录展示行（contract-view spec R3）。
 *
 * <p>{@code contractAmount} 为精确数值字符串（如 {@code "120000.50"}），千分位格式化交前端，断言按数值；
 * {@code dealSalesName} 已组装为成交销售当前姓名，公海赢单（{@code dealSalesId} 为 NULL）时为"公海赢单"。
 */
public record ContractRowView(
    Long leadId,
    String customerName,
    String businessType,
    String contractAmount,
    LocalDate signedDate,
    LocalDateTime createdAt,
    Long dealSalesId,
    String dealSalesName
) {
}
