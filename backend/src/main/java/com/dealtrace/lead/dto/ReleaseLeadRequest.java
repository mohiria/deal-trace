package com.dealtrace.lead.dto;

/**
 * 退回公海入参（spec ADDED 退回）。{@code releaseNote} 必填，trim 后非空校验在 service 层
 * （与 CreateLeadRequest 不加 @NotBlank 的模式一致）。备注仅进 LEAD_RELEASE 系统日志 summary。
 */
public record ReleaseLeadRequest(String releaseNote) {
}
