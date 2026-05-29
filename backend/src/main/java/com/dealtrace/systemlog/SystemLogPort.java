package com.dealtrace.systemlog;

/**
 * 系统日志端口。系统事件经此接口落到 {@code system_log} 表（生产实现 {@link JdbcSystemLogPort}），
 * 或仅写应用日志（fallback {@link Slf4jSystemLogPort}）。
 *
 * <p>5 参 {@link #record(String, String, Long, Long, String)} 为主方法（lead-core change 引入）；
 * 4 参 {@link #record(String, String, Long, Long)} 通过 default 委派为 summary=null，
 * 兼容 account 事件等无摘要场景，调用方零修改。
 */
public interface SystemLogPort {

    /**
     * 记录一条系统日志（含摘要）。
     *
     * @param action     业务动作标识，如 "ACCOUNT_DISABLE" / "LEAD_CREATE" / "LEAD_STAGE_CHANGE"
     * @param targetType 目标实体类型，如 "ACCOUNT" / "LEAD"
     * @param targetId   目标实体主键
     * @param operatorId 操作人账号 id（系统自动操作传 null）
     * @param summary    操作摘要（如 lead 事件含客户名 / 业务类型 / 归属），可为 null
     */
    void record(String action, String targetType, Long targetId, Long operatorId, String summary);

    /**
     * 4 参兼容方法：内部委派为 5 参 + summary=null。
     * 既有 account 事件调用方（{@code AdminAccountController}）通过此 default 方法自动适配，
     * 零代码修改。
     */
    default void record(String action, String targetType, Long targetId, Long operatorId) {
        record(action, targetType, targetId, operatorId, null);
    }
}
