package com.dealtrace.lead.dto;

import java.math.BigDecimal;

/**
 * 标记赢单入参（lead-closure / PRD §7.11.1）。
 * {@code contractAmount} 精确类型（>0、≤2 位小数，校验在 service）。
 * {@code signedDate} 以字符串接收并在 service 层 LocalDate.parse（非法日期 → VALIDATION_ERROR，
 * 而非反序列化 500）；这样校验路径确定可控。
 * 不加校验注解，空 / 非法均由 service 层判 VALIDATION_ERROR（与 CreateLeadRequest 模式一致）。
 */
public record WinLeadRequest(BigDecimal contractAmount, String signedDate) {
}
