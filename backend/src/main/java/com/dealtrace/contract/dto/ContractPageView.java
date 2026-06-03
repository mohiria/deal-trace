package com.dealtrace.contract.dto;

import java.util.List;

/**
 * 合同记录分页结果（contract-view spec R1）。倒序条目 + 分页元信息，对齐 SystemLogPageView 形态。
 */
public record ContractPageView(
    List<ContractRowView> items,
    long total,
    int page,
    int size
) {
}
