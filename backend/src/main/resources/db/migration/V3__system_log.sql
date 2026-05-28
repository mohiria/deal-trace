-- system-log: 系统自动产生的审计事件持久化（PRD §7.1.11 / §7.8 / §9.5 / design D1）
-- 多态 target：account 事件 lead_id=NULL，lead 事件 lead_id=target_id（见 spec R5）。
-- 无 FK：MySQL 不支持 polymorphic FK；operator_id / lead_id 视为逻辑外键，由应用层保证。
-- summary 列容纳 PRD §7.8.6 的"关键变更摘要"，本 change 不消费（仅 account 事件，恒 NULL）。
CREATE TABLE system_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    action      VARCHAR(64)  NOT NULL,
    target_type VARCHAR(16)  NOT NULL,
    target_id   BIGINT       NOT NULL,
    operator_id BIGINT       NULL,
    lead_id     BIGINT       NULL,
    summary     VARCHAR(512) NULL,
    created_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_system_log_lead_created_at (lead_id, created_at),
    KEY idx_system_log_target (target_type, target_id, created_at),
    KEY idx_system_log_operator_created_at (operator_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
