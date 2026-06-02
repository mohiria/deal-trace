package com.dealtrace.systemlog.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * `system_log` 表实体（system-log / view-system-log）。系统自动产生的审计事件。
 *
 * <p>一经持久化只读：无 UPDATE/DELETE 业务路径。{@code detail} 为结构化引用 JSON（可空，旧行为 NULL），
 * 这里以原始 JSON 字符串承载，由 {@code SystemLogReadService} 解析后组装展示（design D1/D6）。
 * 多态 target：{@code targetType="LEAD"} 时 {@code leadId=targetId}，account 事件 {@code leadId=NULL}。
 */
@TableName("system_log")
public class SystemLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String action;

    private String targetType;

    private Long targetId;

    /** 操作人账号 id；系统自动操作为 NULL。 */
    private Long operatorId;

    private Long leadId;

    /** 人读摘要 freetext，读出侧在 detail 缺失时回退展示。 */
    private String summary;

    /** 结构化引用 JSON（可空）。 */
    private String detail;

    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }

    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }

    public Long getLeadId() { return leadId; }
    public void setLeadId(Long leadId) { this.leadId = leadId; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
