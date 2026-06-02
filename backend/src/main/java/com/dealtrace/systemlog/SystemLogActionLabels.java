package com.dealtrace.systemlog;

import java.util.Map;

/**
 * 系统日志 {@code action} → 中文动作标签的集中映射（view-system-log design D5）。
 *
 * <p>单点维护：新增 action 时只改这里，读出侧 DTO 直接回传 {@code actionLabel}，
 * 避免 magic string 散落前端。未登记的 action 回退展示原始 action 串。
 */
public final class SystemLogActionLabels {

    private static final Map<String, String> LABELS = Map.ofEntries(
        Map.entry("ACCOUNT_CREATE", "创建账号"),
        Map.entry("ACCOUNT_ENABLE", "启用账号"),
        Map.entry("ACCOUNT_DISABLE", "停用账号"),
        Map.entry("LEAD_CREATE", "创建线索"),
        Map.entry("LEAD_CLAIM", "认领"),
        Map.entry("LEAD_RELEASE", "退回公海"),
        Map.entry("LEAD_ASSIGN", "分配"),
        Map.entry("LEAD_RECALL", "回收"),
        Map.entry("LEAD_TRANSFER", "转移"),
        Map.entry("LEAD_STAGE_CHANGE", "阶段变更"),
        Map.entry("LEAD_WIN", "标记赢单"),
        Map.entry("LEAD_LOSE", "标记流失")
    );

    private SystemLogActionLabels() {
    }

    /** 返回 action 的中文标签；未登记则回退原始 action。 */
    public static String labelOf(String action) {
        if (action == null) {
            return null;
        }
        return LABELS.getOrDefault(action, action);
    }
}
