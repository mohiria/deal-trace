-- customer: 客户主体（PRD §7.2 / §8.1 / design D7）
-- 3 业务字段：name（trim 后查重）+ usci（trim+upper 归一化后查重，18 位 GB 32100-2015）+ created_at。
-- 两条 UNIQUE 兜底唯一性（tech-arch §7.2.2/3）；无 FK（customer 是被引用方，lead capability
-- 落地时由 lead 表加 customer_id FK）。collation 沿用库默认 utf8mb4_unicode_ci（design D1）。
CREATE TABLE customer (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(128) NOT NULL,
    usci        CHAR(18)     NOT NULL,
    created_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_usci (usci),
    UNIQUE KEY uk_customer_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
