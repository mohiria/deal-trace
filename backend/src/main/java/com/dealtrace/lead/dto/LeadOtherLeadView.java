package com.dealtrace.lead.dto;

/**
 * 客户其他业务线索提示项（spec ADDED customer-other-leads-hint，PRD §7.6.3）。
 *
 * <p>仅承载 §7.6.3 列举的摘要字段：业务类型、归属销售、当前阶段。结构上**不携带**联系方式 /
 * 进度内容，从类型层杜绝越权泄漏（design D2）。这是「提示卡片内容范围」，非对 ADMIN 总访问权的限制——
 * ADMIN 仍可经 {@code GET /leads/{id}} 查看完整详情。
 *
 * @param businessType  其他业务线索的业务类型（中文枚举值）
 * @param ownerSalesName 归属销售姓名；公海线索为「公海」
 * @param stage         当前阶段（中文枚举值；范围内只会是进行中或已赢单，不含已流失）
 */
public record LeadOtherLeadView(
    String businessType,
    String ownerSalesName,
    String stage
) {
}
