package com.dealtrace.systemlog.dto;

import java.util.List;

/**
 * 全局系统日志分页结果（view-system-log spec R2）。倒序条目 + 分页元信息。
 */
public record SystemLogPageView(
    List<SystemLogView> items,
    long total,
    int page,
    int size
) {
}
