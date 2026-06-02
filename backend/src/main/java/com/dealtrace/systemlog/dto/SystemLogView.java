package com.dealtrace.systemlog.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 系统日志读出项（view-system-log spec R3/R4）。
 *
 * <p>展示信息由读出侧按<b>当前</b>账号信息组装，<b>不</b>依赖写侧 freetext 格式：
 * {@code operatorName} 为操作人当前姓名（系统自动操作为"系统"）；{@code actionLabel} 为中文动作标签；
 * {@code detail} 为已富化的结构化引用（归属 id 旁附 *Name 当前姓名、公海为"公海"；金额为精确字符串，
 * 千分位由前端 formatAmount 渲染），旧行 {@code detail=null} 时回退 {@code summaryFallback}。
 */
public record SystemLogView(
    Long id,
    String action,
    String actionLabel,
    String operatorName,
    LocalDateTime createdAt,
    String targetType,
    Long targetId,
    Long leadId,
    Map<String, Object> detail,
    String summaryFallback
) {
}
