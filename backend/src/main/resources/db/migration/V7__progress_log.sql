-- progress-log: 进度跟踪表（PRD §5.4 / §7.8 / §9.4 / design D4-D9）
-- 由"新增进度跟踪"动作（仅 SALES 对自己名下未结束线索）在其事务内插入，并同步更新 `lead`.last_tracked_at。
-- track_time 为服务端时钟（禁客户端注入），与新增进度同事务的 lead.last_tracked_at 同值同源。
-- tracker_id 由认证用户派生。进度一经写入不可改不可删（无 UPDATE/DELETE 端点）；新增进度不写 system_log。
--
-- 注意：`lead` 是 MySQL 8 保留字，FK 引用必须反引号。collation 沿用库默认 utf8mb4_unicode_ci。
CREATE TABLE progress_log (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    lead_id     BIGINT        NOT NULL,
    method      VARCHAR(16)   NOT NULL,
    content     VARCHAR(1024) NOT NULL,
    tracker_id  BIGINT        NOT NULL,
    track_time  DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_progress_lead_time (lead_id, track_time),
    CONSTRAINT fk_progress_lead    FOREIGN KEY (lead_id)    REFERENCES `lead`(id),
    CONSTRAINT fk_progress_tracker FOREIGN KEY (tracker_id) REFERENCES account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
