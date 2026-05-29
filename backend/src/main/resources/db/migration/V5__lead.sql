-- lead-core: 业务线索表（PRD §7.3 / §9.3 / design D3）
-- 14 业务字段 + 双 FK（customer / account）+ 3 索引覆盖 lead-core / ownership / closure 查询模式。
-- last_tracked_at / lose_reason / lose_note / won_at / lost_at 由后续 capability 写入，
-- 在本表预留 NULL 列避免 ALTER 大表。collation 沿用库默认 utf8mb4_unicode_ci。
--
-- 注意：`lead` 是 MySQL 8 的保留字（LEAD() 窗口函数），CREATE TABLE / FK / 索引引用时必须反引号。
CREATE TABLE `lead` (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    customer_id       BIGINT       NOT NULL,
    business_year     SMALLINT     NOT NULL,
    business_type     VARCHAR(16)  NOT NULL,
    contact_name      VARCHAR(64)  NOT NULL,
    contact_phone     VARCHAR(32)  NOT NULL,
    lead_source       VARCHAR(128) NULL,
    owner_sales_id    BIGINT       NULL,
    stage             VARCHAR(16)  NOT NULL,
    last_tracked_at   DATETIME(3)  NULL,
    lose_reason       VARCHAR(16)  NULL,
    lose_note         VARCHAR(512) NULL,
    created_at        DATETIME(3)  NOT NULL,
    won_at            DATETIME(3)  NULL,
    lost_at           DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_lead_customer_year_type (customer_id, business_year, business_type),
    KEY idx_lead_owner_created      (owner_sales_id, created_at),
    KEY idx_lead_stage_created      (stage, created_at),
    CONSTRAINT fk_lead_customer FOREIGN KEY (customer_id)    REFERENCES customer(id),
    CONSTRAINT fk_lead_owner    FOREIGN KEY (owner_sales_id) REFERENCES account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
