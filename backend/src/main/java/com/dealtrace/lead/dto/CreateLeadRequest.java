package com.dealtrace.lead.dto;

/**
 * 创建线索入参（spec R3 / R4）。
 *
 * <p>不加 @NotBlank：service 层对 trim 后空白做权威校验（与 CustomerService 模式一致）。
 *
 * <p>{@code ownerSalesId} 仅在调用者为 ADMIN 时生效；SALES 的归属在 service 层根据
 * {@code assignToPool} 标识与 principal.id 决定，请求体中的 ownerSalesId 被忽略
 * （design D5）。
 */
public record CreateLeadRequest(
    Long customerId,
    String businessType,
    String contactName,
    String contactPhone,
    String leadSource,
    Long ownerSalesId,
    Boolean assignToPool
) {
}
