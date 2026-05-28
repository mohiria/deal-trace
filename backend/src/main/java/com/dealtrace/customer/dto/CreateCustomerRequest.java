package com.dealtrace.customer.dto;

/**
 * Spec R1 / R2 / R3 入参。**不**在反序列化时归一化，保留原始输入便于错误回显；
 * 归一化在 {@link com.dealtrace.customer.service.CustomerService} 入口立即发生。
 *
 * <p>不加 @NotBlank：服务层对 trim 后空白做权威校验（spec R2 / R3 第一个场景明确要求
 * "trim 后" 为空也按必填失败），与 jakarta validation 的 @NotBlank "trim 前判空" 语义不同。
 */
public record CreateCustomerRequest(
    String name,
    String usci
) {
}
