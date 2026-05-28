package com.dealtrace.account;

import com.dealtrace.common.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 V2__account.sql 已被 Flyway 应用：account 表与 uk_account_email 唯一索引存在。
 * 覆盖 spec R10（邮箱全局唯一，DB 约束兜底）。
 */
class AccountSchemaMigrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void accountTableExists() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_schema = DATABASE() AND table_name = 'account'",
            Integer.class
        );
        assertThat(count)
            .as("Flyway 应已应用 V2__account.sql，information_schema 应能查到 account 表")
            .isEqualTo(1);
    }

    @Test
    void uniqueIndexOnEmailExists() {
        Integer nonUnique = jdbc.queryForObject(
            "SELECT non_unique FROM information_schema.statistics " +
                "WHERE table_schema = DATABASE() " +
                "  AND table_name = 'account' " +
                "  AND index_name = 'uk_account_email' " +
                "LIMIT 1",
            Integer.class
        );
        assertThat(nonUnique)
            .as("uk_account_email 必须是 UNIQUE 索引（non_unique = 0），由 schema 兜底防止重复邮箱写入")
            .isEqualTo(0);
    }
}
