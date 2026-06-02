package com.dealtrace.systemlog;

import com.dealtrace.common.MultiTransactionalIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * system-log spec ADDED（结构化 detail 持久化 / detail 可空非破坏演进）集成测试：
 * 直接调用 {@link JdbcSystemLogPort#record} 落 {@code system_log.detail} JSON 列，解析回 map 断言形状。
 *
 * <p>真 MySQL 8.4（JSON 列）：MySQL 会规范化 JSON（冒号后加空格、键序重排），故按解析后的值断言而非裸字符串。
 * 多事务基类让 record 后立即可读。
 */
class SystemLogDetailWriteTest extends MultiTransactionalIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private JdbcSystemLogPort port;

    @Override
    protected Set<String> tablesToTruncate() {
        return Set.of("system_log");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readDetail(long targetId) {
        String detail = (String) jdbcTemplate.queryForMap(
            "SELECT detail FROM system_log WHERE target_id = ?", targetId).get("detail");
        assertThat(detail).as("detail JSON 应已落盘").isNotNull();
        return JSON.readValue(detail, Map.class);
    }

    @Test
    void ownerChange_persists_owner_ids_not_email() {
        port.record("LEAD_TRANSFER", "LEAD", 9001L, 1L,
            "转移", SystemLogDetails.ownerChange(11L, 22L));

        Map<String, Object> d = readDetail(9001L);
        assertThat(((Number) d.get("fromOwnerId")).longValue()).isEqualTo(11L);
        assertThat(((Number) d.get("toOwnerId")).longValue()).isEqualTo(22L);
        assertThat(d.values()).noneMatch(v -> String.valueOf(v).contains("@")); // 不得落邮箱
    }

    @Test
    void win_persists_exact_amount_string_and_signed_date() {
        port.record("LEAD_WIN", "LEAD", 9002L, 1L,
            "赢单", SystemLogDetails.win(new BigDecimal("1234.50"), LocalDate.of(2026, 5, 31)));

        Map<String, Object> d = readDetail(9002L);
        assertThat(d.get("contractAmount")).isEqualTo("1234.50"); // 精确字符串、不丢精度
        assertThat(d.get("signedDate")).isEqualTo("2026-05-31");
    }

    @Test
    void stageChange_persists_enum_codes() {
        port.record("LEAD_STAGE_CHANGE", "LEAD", 9003L, 1L,
            "阶段", SystemLogDetails.stageChange("方案报价", "商务谈判"));

        Map<String, Object> d = readDetail(9003L);
        assertThat(d.get("fromStage")).isEqualTo("方案报价");
        assertThat(d.get("toStage")).isEqualTo("商务谈判");
    }

    @Test
    void noDetail_oldPath_persistsNullDetail() {
        port.record("ACCOUNT_DISABLE", "ACCOUNT", 9004L, 1L); // 4 参：detail=null

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT detail FROM system_log WHERE target_id = ?", 9004L);

        assertThat(row.get("detail")).isNull();
    }
}
