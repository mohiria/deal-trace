package com.dealtrace.lead.dto;

import com.dealtrace.customer.entity.Customer;
import com.dealtrace.lead.entity.BusinessType;
import com.dealtrace.lead.entity.Lead;
import com.dealtrace.lead.entity.LeadStage;

import java.time.LocalDateTime;

/**
 * 公海线索列表项（spec ADDED 公海列表）。
 *
 * <p>{@code contactPhone} 为「脱敏 / 明文」二态：由 service 按调用者角色决定传入值
 * （ADMIN 明文、SALES 经 {@link com.dealtrace.lead.service.PhoneMasker} 脱敏）。
 * 公海线索 {@code owner_sales_id} 恒为 NULL，故不返回归属字段。
 *
 * <p>{@code customerHasOtherLeads}：该客户是否另有「不同业务类型、非已流失」线索（spec
 * customer-other-leads-hint，PRD §7.6.4）。SALES 浏览公海时仅得此布尔，**不**附带其他线索详情。
 */
public record PoolLeadView(
    Long id,
    Long customerId,
    String customerName,
    String customerUsci,
    Short businessYear,
    String businessType,
    String contactName,
    String contactPhone,
    String leadSource,
    String stage,
    LocalDateTime lastTrackedAt,
    LocalDateTime createdAt,
    boolean customerHasOtherLeads
) {
    /**
     * @param contactPhone          已由 service 决定的电话展示值（脱敏或明文）
     * @param customerHasOtherLeads 由 service 聚合判定的「该客户有其他业务线索」布尔（§7.6.4）
     */
    public static PoolLeadView of(Lead l, Customer customer, String contactPhone,
                                  boolean customerHasOtherLeads) {
        BusinessType bt = l.getBusinessType();
        LeadStage st = l.getStage();
        return new PoolLeadView(
            l.getId(),
            l.getCustomerId(),
            customer == null ? null : customer.getName(),
            customer == null ? null : customer.getUsci(),
            l.getBusinessYear(),
            bt == null ? null : bt.getDbValue(),
            l.getContactName(),
            contactPhone,
            l.getLeadSource(),
            st == null ? null : st.getDbValue(),
            l.getLastTrackedAt(),
            l.getCreatedAt(),
            customerHasOtherLeads
        );
    }
}
