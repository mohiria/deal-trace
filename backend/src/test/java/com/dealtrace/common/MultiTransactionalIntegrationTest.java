package com.dealtrace.common;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 跨事务集成测试基类。
 *
 * <p>与 {@link IntegrationTest} 的区别：本基类放弃单事务自动回滚（{@link Propagation#NOT_SUPPORTED}），
 * 子类可在多个独立事务中真实提交，便于测试**事务边界相关的业务行为**
 * （如认领并发、流水插入与外部状态依赖、Outbox 等场景）。
 *
 * <p>代价是每个测试方法结束后必须**显式清理副作用**：子类返回 {@link #tablesToTruncate()}，
 * 基类在 {@code @AfterEach} 中按"关 FK → TRUNCATE 全部 → 开 FK"顺序清理；FK 关闭仅在清理期间，
 * 不会泄漏到下一个测试方法。
 *
 * <p>使用约束：{@link #tablesToTruncate()} 必须只列出测试自身可能写入的表；不要把 Flyway baseline
 * 之外的迁移表也带上，否则会破坏其他测试的环境假设。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public abstract class MultiTransactionalIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected abstract Set<String> tablesToTruncate();

    @AfterEach
    void truncateAfterEach() {
        Set<String> tables = tablesToTruncate();
        if (tables == null || tables.isEmpty()) {
            return;
        }
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            for (String table : tables) {
                jdbcTemplate.execute("TRUNCATE TABLE " + table);
            }
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }
}
