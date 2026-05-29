package com.dealtrace.lead.dto;

/**
 * 标记流失入参（lead-closure / PRD §7.11.2）。
 * {@code loseReason} 中文枚举名（校验在 service）；{@code loseReason==其他} 时 {@code loseNote} 必填非空。
 * 不加校验注解，空 / 非法由 service 层判 VALIDATION_ERROR（与 CreateLeadRequest / ReleaseLeadRequest 一致）。
 */
public record LoseLeadRequest(String loseReason, String loseNote) {
}
