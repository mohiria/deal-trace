package com.dealtrace.progresslog.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * `progress_log` 表实体（progress-log / PRD §9.4）。销售手动新增、强绑定 lead。
 *
 * <p>{@code trackTime} 由服务端时钟生成（禁客户端注入），与新增进度同事务的 lead.last_tracked_at 同值同源。
 * {@code trackerId} 由认证用户派生。一经持久化不可改不可删（无 UPDATE/DELETE 端点，design D10）。
 */
@TableName("progress_log")
public class ProgressLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long leadId;

    private TrackMethod method;

    private String content;

    /** 跟踪人 = 认证用户 account.id（非客户端入参）。 */
    private Long trackerId;

    /** 服务端时钟；与本次新增同事务的 lead.last_tracked_at 同值。 */
    private LocalDateTime trackTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getLeadId() { return leadId; }
    public void setLeadId(Long leadId) { this.leadId = leadId; }

    public TrackMethod getMethod() { return method; }
    public void setMethod(TrackMethod method) { this.method = method; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Long getTrackerId() { return trackerId; }
    public void setTrackerId(Long trackerId) { this.trackerId = trackerId; }

    public LocalDateTime getTrackTime() { return trackTime; }
    public void setTrackTime(LocalDateTime trackTime) { this.trackTime = trackTime; }
}
