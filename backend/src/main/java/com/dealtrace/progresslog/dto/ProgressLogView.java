package com.dealtrace.progresslog.dto;

import com.dealtrace.account.entity.Account;
import com.dealtrace.progresslog.entity.ProgressLog;
import com.dealtrace.progresslog.entity.TrackMethod;

import java.time.LocalDateTime;

/**
 * 进度列表项（progress-log spec R9）。内联跟踪人标识（id + 名称，name 由 service 解析 account）。
 * {@code method} 以中文标签返回（PRD §7.8 跟踪方式）。
 */
public record ProgressLogView(
    Long id,
    Long leadId,
    String method,
    String content,
    Long trackerId,
    String trackerName,
    LocalDateTime trackTime
) {
    /**
     * @param tracker 跟踪人账号（可为 null，仅取名称展示）
     */
    public static ProgressLogView of(ProgressLog p, Account tracker) {
        TrackMethod m = p.getMethod();
        return new ProgressLogView(
            p.getId(),
            p.getLeadId(),
            m == null ? null : m.getDbValue(),
            p.getContent(),
            p.getTrackerId(),
            tracker == null ? null : tracker.getName(),
            p.getTrackTime()
        );
    }
}
