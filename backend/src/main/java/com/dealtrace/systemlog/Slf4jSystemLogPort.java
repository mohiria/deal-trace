package com.dealtrace.systemlog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link SystemLogPort} 的 NoOp 实现：只写 SLF4J INFO 日志，不落 DB。
 *
 * <p>仅在 auth-account change 阶段使用；progress-log change 必须提供 JDBC 实现替换本 bean
 * （已存项目记忆 systemlog-port-noop）。
 */
@Component
public class Slf4jSystemLogPort implements SystemLogPort {

    private static final Logger log = LoggerFactory.getLogger(Slf4jSystemLogPort.class);

    @Override
    public void record(String action, String targetType, Long targetId, Long operatorId) {
        log.info("[systemlog] action={} targetType={} targetId={} operatorId={}",
            action, targetType, targetId, operatorId);
    }
}
