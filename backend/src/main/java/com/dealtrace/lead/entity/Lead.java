package com.dealtrace.lead.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * `lead` 表实体（design D3）。MySQL 保留字，表名反引号；MyBatis-Plus @TableName 自动处理。
 */
@TableName("`lead`")
public class Lead {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long customerId;

    private Short businessYear;

    private BusinessType businessType;

    private String contactName;

    private String contactPhone;

    private String leadSource;

    /** NULL = 公海；非 NULL = 私海归属销售 account.id。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long ownerSalesId;

    private LeadStage stage;

    /** lead-core 阶段不维护；progress-log change 写入。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lastTrackedAt;

    /** lead-core 阶段不维护；lead-closure change 写入。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String loseReason;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String loseNote;

    private LocalDateTime createdAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime wonAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lostAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }

    public Short getBusinessYear() { return businessYear; }
    public void setBusinessYear(Short businessYear) { this.businessYear = businessYear; }

    public BusinessType getBusinessType() { return businessType; }
    public void setBusinessType(BusinessType businessType) { this.businessType = businessType; }

    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }

    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }

    public String getLeadSource() { return leadSource; }
    public void setLeadSource(String leadSource) { this.leadSource = leadSource; }

    public Long getOwnerSalesId() { return ownerSalesId; }
    public void setOwnerSalesId(Long ownerSalesId) { this.ownerSalesId = ownerSalesId; }

    public LeadStage getStage() { return stage; }
    public void setStage(LeadStage stage) { this.stage = stage; }

    public LocalDateTime getLastTrackedAt() { return lastTrackedAt; }
    public void setLastTrackedAt(LocalDateTime lastTrackedAt) { this.lastTrackedAt = lastTrackedAt; }

    public String getLoseReason() { return loseReason; }
    public void setLoseReason(String loseReason) { this.loseReason = loseReason; }

    public String getLoseNote() { return loseNote; }
    public void setLoseNote(String loseNote) { this.loseNote = loseNote; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getWonAt() { return wonAt; }
    public void setWonAt(LocalDateTime wonAt) { this.wonAt = wonAt; }

    public LocalDateTime getLostAt() { return lostAt; }
    public void setLostAt(LocalDateTime lostAt) { this.lostAt = lostAt; }
}
