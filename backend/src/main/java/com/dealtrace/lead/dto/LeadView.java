package com.dealtrace.lead.dto;

import com.dealtrace.customer.entity.Customer;
import com.dealtrace.lead.entity.BusinessType;
import com.dealtrace.lead.entity.Lead;
import com.dealtrace.lead.entity.LeadStage;

import java.time.LocalDateTime;

/**
 * 对外详情视图（spec R1）：14 业务字段 + customerName / customerUsci 内联（design D8）。
 */
public record LeadView(
    Long id,
    Long customerId,
    String customerName,
    String customerUsci,
    Short businessYear,
    String businessType,
    String contactName,
    String contactPhone,
    String leadSource,
    Long ownerSalesId,
    String stage,
    LocalDateTime lastTrackedAt,
    String loseReason,
    String loseNote,
    LocalDateTime createdAt,
    LocalDateTime wonAt,
    LocalDateTime lostAt
) {
    public static LeadView of(Lead l, Customer customer) {
        BusinessType bt = l.getBusinessType();
        LeadStage st = l.getStage();
        return new LeadView(
            l.getId(),
            l.getCustomerId(),
            customer == null ? null : customer.getName(),
            customer == null ? null : customer.getUsci(),
            l.getBusinessYear(),
            bt == null ? null : bt.getDbValue(),
            l.getContactName(),
            l.getContactPhone(),
            l.getLeadSource(),
            l.getOwnerSalesId(),
            st == null ? null : st.getDbValue(),
            l.getLastTrackedAt(),
            l.getLoseReason(),
            l.getLoseNote(),
            l.getCreatedAt(),
            l.getWonAt(),
            l.getLostAt()
        );
    }
}
