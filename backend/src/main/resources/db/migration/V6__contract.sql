-- lead-closure: 合同记录表（PRD §7.11.1 / §9.6 / design D4-D6）
-- 由"标记赢单"动作在其事务内原子生成；无独立创建入口。
-- contract_amount 精确数值类型 DECIMAL(15,2)（tech-arch §9.2，禁浮点）；signed_date 为用户填的业务日期，
-- 与 created_at（服务端事件时间戳）相互独立。deal_sales_id = 赢单时刻线索归属（公海单由 Admin 赢单时为 NULL）。
-- 每条线索最多 1 条合同：lead_id UNIQUE 强约束（tech-arch §6.1.4）。
--
-- 注意：`lead` 是 MySQL 8 保留字，FK 引用必须反引号。collation 沿用库默认 utf8mb4_unicode_ci。
CREATE TABLE contract (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    lead_id         BIGINT         NOT NULL,
    contract_amount DECIMAL(15, 2) NOT NULL,
    signed_date     DATE           NOT NULL,
    deal_sales_id   BIGINT         NULL,
    created_at      DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_contract_lead (lead_id),
    CONSTRAINT fk_contract_lead  FOREIGN KEY (lead_id)       REFERENCES `lead`(id),
    CONSTRAINT fk_contract_sales FOREIGN KEY (deal_sales_id) REFERENCES account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
