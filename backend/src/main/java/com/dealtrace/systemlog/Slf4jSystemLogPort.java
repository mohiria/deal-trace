package com.dealtrace.systemlog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link SystemLogPort} 的 NoOp 实现：只写 SLF4J INFO 日志，不落 DB。
 *
 * <p>system-log change 已落地 {@link JdbcSystemLogPort}（{@code @Primary}）接管默认注入位；
 * 本类保留 {@code @Component} 仅作单元测试轻量替身与运维回滚兜底（删除 JdbcSystemLogPort
 * 的 {@code @Primary} 即可让本 NoOp 自动顶上）。运行时 {@code @Autowired SystemLogPort}
 * 注入的是 JdbcSystemLogPort，本类的 {@code record} 不会被业务路径调用。
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
