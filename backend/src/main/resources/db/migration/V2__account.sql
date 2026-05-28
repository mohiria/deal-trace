-- auth-account: 账号表（PRD §7.1 / tech-arch §5.1 / design D3）
-- email 全局唯一（spec R10），password 用 BCrypt（CHAR(60)）存储，
-- role / status 用 VARCHAR(16) 字符串枚举（Java 侧用 enum + @EnumValue）。
CREATE TABLE account (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    email           VARCHAR(255) NOT NULL,
    password_hash   CHAR(60)     NOT NULL,
    name            VARCHAR(64)  NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    disabled_at     DATETIME(3)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
