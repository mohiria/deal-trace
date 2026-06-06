package com.dealtrace.lead.dto;

/**
 * 创建线索入参（spec R3 / R4）。
 *
 * <p>不加 @NotBlank：service 层对 trim 后空白做权威校验（与 CustomerService 模式一致）。
 *
 * <p>{@code ownerSalesId} 仅在调用者为 ADMIN 时生效；SALES 的归属在 service 层根据
 * {@code assignToPool} 标识与 principal.id 决定，请求体中的 ownerSalesId 被忽略
 * （design D5）。
 *
 * <p>客户来源二选一（恰择其一）：{@code customerId}（选既有客户）或 {@code newCustomer}
 * （内联录入新客户，走事务内 USCI find-or-create 仲裁）。两者同缺或同提供均 VALIDATION_ERROR。
 */
public record CreateLeadRequest(
    Long customerId,
    NewCustomer newCustomer,
    String businessType,
    String contactName,
    String contactPhone,
    String leadSource,
    Long ownerSalesId,
    Boolean assignToPool
) {
    /** 内联新建客户入参：名称 + 统一社会信用代码（USCI 归一化 / 校验由 service 权威完成）。 */
    public record NewCustomer(String name, String usci) {
    }
}
