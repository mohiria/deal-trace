package com.dealtrace.contract.dto;

import java.time.LocalDate;

/**
 * 合同记录浏览查询条件（contract-view spec R1）。
 *
 * <p>{@code dealSalesId} 仅 ADMIN 有意义；SALES 的可见范围由 service 强制收窄为本人（忽略此入参）。
 * 签订日期 {@code signedDateFrom} / {@code signedDateTo} 为闭区间（含端点），任一为 null 表示该侧不设限。
 * {@code keyword} 对客户名称或业务类型做包含匹配。
 */
public record ContractQuery(
    Long dealSalesId,
    LocalDate signedDateFrom,
    LocalDate signedDateTo,
    String keyword,
    int page,
    int size
) {
}
