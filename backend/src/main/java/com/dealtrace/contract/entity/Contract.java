package com.dealtrace.contract.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * `contract` 表实体（lead-closure / PRD §9.6）。由标记赢单原子生成，每线索≤1（lead_id UNIQUE）。
 *
 * <p>{@code contractAmount} 用 {@link BigDecimal} 精确类型（tech-arch §9.2，禁浮点）。
 * {@code signedDate} 是用户填的业务日期；{@code createdAt} 是服务端事件时间戳，二者独立。
 * {@code dealSalesId} = 赢单时刻线索归属（公海单由 Admin 赢单时为 NULL）。
 */
@TableName("contract")
public class Contract {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long leadId;

    private BigDecimal contractAmount;

    private LocalDate signedDate;

    /** 成交销售 = 赢单时刻 lead.owner_sales_id；公海单可为 NULL。 */
    private Long dealSalesId;

    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getLeadId() { return leadId; }
    public void setLeadId(Long leadId) { this.leadId = leadId; }

    public BigDecimal getContractAmount() { return contractAmount; }
    public void setContractAmount(BigDecimal contractAmount) { this.contractAmount = contractAmount; }

    public LocalDate getSignedDate() { return signedDate; }
    public void setSignedDate(LocalDate signedDate) { this.signedDate = signedDate; }

    public Long getDealSalesId() { return dealSalesId; }
    public void setDealSalesId(Long dealSalesId) { this.dealSalesId = dealSalesId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
