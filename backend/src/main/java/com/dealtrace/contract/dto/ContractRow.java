package com.dealtrace.contract.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 合同记录浏览的 Mapper 投影（contract-view spec）。
 *
 * <p>由 {@link com.dealtrace.contract.repository.ContractMapper} 的 JOIN 查询按列别名自动映射；
 * {@code contractAmount} 以 String 读出（{@code ResultSet.getString} 保留 DECIMAL 精确标度，如
 * {@code "120000.50"}，避免浮点）；{@code dealSalesName} 为 LEFT JOIN account 当前姓名，公海赢单
 * （{@code deal_sales_id} 为 NULL）时为 {@code null}，由 service 组装为"公海赢单"。
 */
public class ContractRow {

    private Long leadId;
    private String customerName;
    private String businessType;
    private String contractAmount;
    private LocalDate signedDate;
    private LocalDateTime createdAt;
    private Long dealSalesId;
    private String dealSalesName;

    public Long getLeadId() {
        return leadId;
    }

    public void setLeadId(Long leadId) {
        this.leadId = leadId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getBusinessType() {
        return businessType;
    }

    public void setBusinessType(String businessType) {
        this.businessType = businessType;
    }

    public String getContractAmount() {
        return contractAmount;
    }

    public void setContractAmount(String contractAmount) {
        this.contractAmount = contractAmount;
    }

    public LocalDate getSignedDate() {
        return signedDate;
    }

    public void setSignedDate(LocalDate signedDate) {
        this.signedDate = signedDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Long getDealSalesId() {
        return dealSalesId;
    }

    public void setDealSalesId(Long dealSalesId) {
        this.dealSalesId = dealSalesId;
    }

    public String getDealSalesName() {
        return dealSalesName;
    }

    public void setDealSalesName(String dealSalesName) {
        this.dealSalesName = dealSalesName;
    }
}
