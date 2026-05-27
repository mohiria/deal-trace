package com.dealtrace.common;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link MultiTransactionalIntegrationTest#truncateAfterEach()} 的清理逻辑：
 * 方法 A 真实 INSERT，方法 B 必须看到表是空的（说明 A 之后的 @AfterEach 真的清掉了数据）。
 *
 * <p>测试夹具是 ephemeral 表 {@code truncate_poc}，类级 @BeforeAll / @AfterAll
 * 负责建表 / 删表，不污染 Flyway baseline。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiTransactionalBasePoCTest extends MultiTransactionalIntegrationTest {

    @Override
    protected Set<String> tablesToTruncate() {
        return Set.of("truncate_poc");
    }

    @BeforeAll
    void createTempTable() {
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS truncate_poc (id BIGINT PRIMARY KEY)"
        );
        jdbcTemplate.execute("TRUNCATE TABLE truncate_poc");
    }

    @AfterAll
    void dropTempTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS truncate_poc");
    }

    @Test
    @Order(1)
    void methodAInsertsAndCommits() {
        jdbcTemplate.update("INSERT INTO truncate_poc(id) VALUES (?)", 1L);
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM truncate_poc", Integer.class
        );
        assertThat(count).as("方法 A 自身看到 INSERT 已提交").isEqualTo(1);
    }

    @Test
    @Order(2)
    void methodBSeesEmptyTableProvingAfterEachTruncated() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM truncate_poc", Integer.class
        );
        assertThat(count)
            .as("@AfterEach 必须已清掉方法 A 的提交，方法 B 起手时应见空表")
            .isZero();
    }
}
