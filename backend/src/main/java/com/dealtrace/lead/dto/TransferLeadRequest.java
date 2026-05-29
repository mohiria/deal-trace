package com.dealtrace.lead.dto;

/**
 * 转移入参（spec ADDED 转移）。{@code salesId} 为新归属目标，须为 ENABLED SALES 且不等于现归属；
 * 校验在 service 层。
 */
public record TransferLeadRequest(Long salesId) {
}
