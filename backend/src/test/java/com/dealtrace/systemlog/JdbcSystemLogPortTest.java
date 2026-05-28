package com.dealtrace.systemlog;

import com.dealtrace.common.MultiTransactionalIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec R1 / R3 / R5 集成测试：直接调用 {@link JdbcSystemLogPort#record}，用 JdbcTemplate 直查
 * {@code system_log} 表断言行字段。
 *
 * <p>多事务基类用以让每次 record 后立即可读（不需等待事务提交边界）。
 *
 * <p>R6（持久化异常不上抛）由 {@link JdbcSystemLogPortExceptionTest} 单独覆盖，避免在
 * Spring 上下文里 mock {@code JdbcTemplate} 干扰基类的 TRUNCATE 操作。
 */
class JdbcSystemLogPortTest extends MultiTransactionalIntegrationTest {

    @Autowired
    private JdbcSystemLogPort jdbcSystemLogPort;

    @Override
    protected Set<String> tablesToTruncate() {
        return Set.of("system_log", "account");
    }

    @Test
    void record_account_event_persistsAllRequiredFields() {
        LocalDateTime before = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);

        jdbcSystemLogPort.record("ACCOUNT_DISABLE", "ACCOUNT", 100L, 1L);

        LocalDateTime after = LocalDateTime.now();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT action, target_type, target_id, operator_id, lead_id, summary, created_at "
                + "FROM system_log WHERE target_id = ?", 100L);

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("action")).isEqualTo("ACCOUNT_DISABLE");
        assertThat(row.get("target_type")).isEqualTo("ACCOUNT");
        assertThat(((Number) row.get("target_id")).longValue()).isEqualTo(100L);
        assertThat(((Number) row.get("operator_id")).longValue()).isEqualTo(1L);
        assertThat(row.get("lead_id")).isNull();
        assertThat(row.get("summary")).isNull();

        LocalDateTime createdAt = (LocalDateTime) row.get("created_at");
        assertThat(createdAt)
            .isAfterOrEqualTo(before)
            .isBeforeOrEqualTo(after);
    }

    @Test
    void record_systemAutoOperation_operatorIdIsNull() {
        jdbcSystemLogPort.record("SYSTEM_AUTO_EVENT", "ACCOUNT", 101L, null);

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT action, target_type, target_id, operator_id, lead_id, summary, created_at "
                + "FROM system_log WHERE target_id = ?", 101L);

        assertThat(row.get("operator_id")).isNull();
        assertThat(row.get("action")).isEqualTo("SYSTEM_AUTO_EVENT");
        assertThat(row.get("target_type")).isEqualTo("ACCOUNT");
        assertThat(((Number) row.get("target_id")).longValue()).isEqualTo(101L);
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    void record_createdAt_isServerGenerated() throws InterruptedException {
        LocalDateTime before = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);

        jdbcSystemLogPort.record("ACCOUNT_CREATE", "ACCOUNT", 200L, 1L);
        Thread.sleep(10);
        jdbcSystemLogPort.record("ACCOUNT_DISABLE", "ACCOUNT", 200L, 1L);

        LocalDateTime after = LocalDateTime.now();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT action, created_at FROM system_log WHERE target_id = ? ORDER BY id ASC", 200L);

        assertThat(rows).hasSize(2);
        LocalDateTime t1 = (LocalDateTime) rows.get(0).get("created_at");
        LocalDateTime t2 = (LocalDateTime) rows.get(1).get("created_at");

        assertThat(t1).isAfterOrEqualTo(before).isBeforeOrEqualTo(after);
        assertThat(t2).isAfterOrEqualTo(before).isBeforeOrEqualTo(after);
        assertThat(t2).isAfter(t1);
    }

    @Test
    void record_leadTarget_leadIdEqualsTargetId() {
        jdbcSystemLogPort.record("LEAD_CLAIM", "LEAD", 300L, 1L);

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT action, target_type, target_id, operator_id, lead_id "
                + "FROM system_log WHERE target_id = ?", 300L);

        assertThat(row.get("action")).isEqualTo("LEAD_CLAIM");
        assertThat(row.get("target_type")).isEqualTo("LEAD");
        assertThat(((Number) row.get("target_id")).longValue()).isEqualTo(300L);
        assertThat(((Number) row.get("lead_id")).longValue()).isEqualTo(300L);
        assertThat(((Number) row.get("operator_id")).longValue()).isEqualTo(1L);
    }
}
