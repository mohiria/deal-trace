package com.dealtrace.systemlog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * {@link SystemLogPort} 的 JDBC 真实实现：INSERT 一行到 {@code system_log} 表。
 *
 * <p>设计：{@code @Primary} 接管 {@link Slf4jSystemLogPort} NoOp 的默认注入位（design D3）。
 * 持久化失败时**不向上抛**——业务事务不因系统日志写失败而回滚（spec R6 / design D2）；
 * 失败上下文通过 SLF4J ERROR 留痕，便于运维事后对账。
 *
 * <p>多态 target：{@code targetType="LEAD"} 时 {@code lead_id=targetId}，否则 {@code lead_id=NULL}
 * （spec R5）。本 change 仅 account 事件，{@code summary} 列恒 {@code NULL}。
 */
@Primary
@Component
public class JdbcSystemLogPort implements SystemLogPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcSystemLogPort.class);

    private static final String INSERT_SQL =
        "INSERT INTO system_log "
            + "(action, target_type, target_id, operator_id, lead_id, summary, created_at) "
            + "VALUES (?, ?, ?, ?, ?, NULL, ?)";

    private final JdbcTemplate jdbcTemplate;

    public JdbcSystemLogPort(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(String action, String targetType, Long targetId, Long operatorId) {
        Long leadId = "LEAD".equals(targetType) ? targetId : null;
        LocalDateTime createdAt = LocalDateTime.now();
        try {
            jdbcTemplate.update(INSERT_SQL,
                action, targetType, targetId, operatorId, leadId, createdAt);
        } catch (RuntimeException ex) {
            log.error("[systemlog] persist failed action={} targetType={} targetId={} operatorId={}",
                action, targetType, targetId, operatorId, ex);
        }
    }
}
