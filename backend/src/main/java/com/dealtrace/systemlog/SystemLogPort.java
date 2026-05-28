package com.dealtrace.systemlog;

/**
 * 系统日志端口。auth-account change 仅落 NoOp 实现 ({@link Slf4jSystemLogPort})；
 * 真实 JDBC 落盘由 progress-log change 替换为 JdbcSystemLogPort（见项目记忆 systemlog-port-noop）。
 */
public interface SystemLogPort {

    /**
     * 记录一条系统日志。
     *
     * @param action     业务动作标识，如 "ACCOUNT_CREATE" / "ACCOUNT_DISABLE" / "ACCOUNT_ENABLE"
     * @param targetType 目标实体类型，如 "ACCOUNT"
     * @param targetId   目标实体主键
     * @param operatorId 操作人账号 id（系统自动操作可传 null）
     */
    void record(String action, String targetType, Long targetId, Long operatorId);
}
