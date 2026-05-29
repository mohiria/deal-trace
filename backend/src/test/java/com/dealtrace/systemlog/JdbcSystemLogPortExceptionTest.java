package com.dealtrace.systemlog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

/**
 * Spec R6（持久化异常不上抛）单元测试。
 *
 * <p>不使用 {@code @SpringBootTest}：在集成测试里 {@code @MockitoBean JdbcTemplate} 会扩散到
 * {@link com.dealtrace.common.MultiTransactionalIntegrationTest} 的 TRUNCATE 操作，
 * 反而让全套集成测试失败。这里用纯 Mockito 直接构造 {@link JdbcSystemLogPort} 测异常路径。
 */
class JdbcSystemLogPortExceptionTest {

    private ListAppender<ILoggingEvent> logAppender;
    private Logger jdbcPortLogger;

    @BeforeEach
    void attachLogAppender() {
        jdbcPortLogger = (Logger) LoggerFactory.getLogger(JdbcSystemLogPort.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        jdbcPortLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        jdbcPortLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void record_jdbcThrows_doesNotPropagate() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        // JdbcTemplate.update(String sql, Object... args) 的 varargs：用 8 个独立 any() 匹配
        // (sql + action + targetType + targetId + operatorId + leadId + summary + createdAt)
        // lead-core change 起 SystemLogPort 增 summary 参数，INSERT SQL 多一个绑定位
        Mockito.when(jdbc.update(anyString(),
                any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new DataAccessException("boom") {});

        JdbcSystemLogPort port = new JdbcSystemLogPort(jdbc);

        assertThatNoException().isThrownBy(() ->
            port.record("ACCOUNT_DISABLE", "ACCOUNT", 500L, 7L));

        // 先确认 mock 真的拦到了调用（排除"matcher 不匹配 → 默认返回 0 → 没抛"路径）
        verify(jdbc).update(anyString(),
            any(), any(), any(), any(), any(), any(), any());

        // SLF4J ERROR 行包含 action / targetType / targetId / operatorId
        assertThat(logAppender.list)
            .anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                String msg = event.getFormattedMessage();
                assertThat(msg)
                    .contains("ACCOUNT_DISABLE")
                    .contains("ACCOUNT")
                    .contains("500")
                    .contains("7");
                assertThat(event.getThrowableProxy())
                    .as("ERROR 行必须挂上原异常堆栈，便于运维对账")
                    .isNotNull();
            });
    }
}
