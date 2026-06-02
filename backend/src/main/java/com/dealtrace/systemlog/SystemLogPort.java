package com.dealtrace.systemlog;

import java.util.Map;

/**
 * 系统日志端口。系统事件经此接口落到 {@code system_log} 表（生产实现 {@link JdbcSystemLogPort}），
 * 或仅写应用日志（fallback {@link Slf4jSystemLogPort}）。
 *
 * <p>6 参 {@link #record(String, String, Long, Long, String, Map)} 为主方法（view-system-log change 引入
 * 结构化 {@code detail} 载荷）；5 参与 4 参重载通过 default 委派为 {@code detail=null} / 兼 {@code summary=null}，
 * 既有调用方（account 等）零修改。
 *
 * <p>{@code detail} 存稳定引用（归属 ownerId、阶段枚举码、精确金额字符串、原因码）而非展示串，
 * 供读出侧按当前账号信息组装展示（system-log spec：结构化 detail 持久化 / 可空非破坏演进）。
 */
public interface SystemLogPort {

    /**
     * 记录一条系统日志（含结构化 detail）。
     *
     * @param action     业务动作标识，如 "ACCOUNT_DISABLE" / "LEAD_CREATE" / "LEAD_STAGE_CHANGE"
     * @param targetType 目标实体类型，如 "ACCOUNT" / "LEAD"
     * @param targetId   目标实体主键
     * @param operatorId 操作人账号 id（系统自动操作传 null）
     * @param summary    人读摘要 freetext（保留为读出侧 fallback），可为 null
     * @param detail     结构化引用载荷（持久化为 JSON），可为 null
     */
    void record(String action, String targetType, Long targetId, Long operatorId,
                String summary, Map<String, Object> detail);

    /** 5 参兼容：委派为 6 参 + detail=null（无结构化 detail 的场景）。 */
    default void record(String action, String targetType, Long targetId, Long operatorId, String summary) {
        record(action, targetType, targetId, operatorId, summary, null);
    }

    /** 4 参兼容：委派为 6 参 + summary=null + detail=null（既有 account 事件调用方零修改）。 */
    default void record(String action, String targetType, Long targetId, Long operatorId) {
        record(action, targetType, targetId, operatorId, null, null);
    }
}
