package com.dealtrace.lead.dto;

/**
 * 阶段变更入参（spec ADDED 线索阶段变更）。{@code stage} 接收中文阶段名（如「方案报价」）。
 * 不加 @NotBlank：空 / null / 非法枚举 / 结束阶段 / 等于当前阶段均由 service 层判 VALIDATION_ERROR
 * （与 CreateLeadRequest / ReleaseLeadRequest 不加校验注解的模式一致）。
 */
public record UpdateStageRequest(String stage) {
}
