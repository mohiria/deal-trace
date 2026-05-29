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
 * <p>设计：{@code @Primary} 接管 {@link Slf4jSystemLogPort} NoOp 的默认注入位（system-log design D3）。
 * 持久化失败时**不向上抛**——业务事务不因系统日志写失败而回滚（system-log spec R6 / design D2）；
 * 失败上下文通过 SLF4J ERROR 留痕，便于运维事后对账。
 *
 * <p>多态 target：{@code targetType="LEAD"} 时 {@code lead_id=targetId}，否则 {@code lead_id=NULL}
 * （system-log spec R5）。{@code summary} 由调用方传入；account 事件通过 4 参 default 自动 null。
 */
@Primary
@Component
public class JdbcSystemLogPort implements SystemLogPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcSystemLogPort.class);

    private static final String INSERT_SQL =
        "INSERT INTO system_log "
            + "(action, target_type, target_id, operator_id, lead_id, summary, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    public JdbcSystemLogPort(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(String action, String targetType, Long targetId, Long operatorId, String summary) {
        Long leadId = "LEAD".equals(targetType) ? targetId : null;
        LocalDateTime createdAt = LocalDateTime.now();
        try {
            jdbcTemplate.update(INSERT_SQL,
                action, targetType, targetId, operatorId, leadId, summary, createdAt);
        } catch (RuntimeException ex) {
            log.error("[systemlog] persist failed action={} targetType={} targetId={} operatorId={} summary={}",
                action, targetType, targetId, operatorId, summary, ex);
        }
    }
}
