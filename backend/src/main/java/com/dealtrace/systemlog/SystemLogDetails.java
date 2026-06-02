package com.dealtrace.systemlog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统日志结构化 {@code detail} 载荷构造器（view-system-log / system-log spec ADDED）。
 *
 * <p>每个工厂方法对应一类业务事件，产出供 {@link SystemLogPort#record} 持久化为 JSON 的引用 map。
 * 关键约定（spec：存稳定引用而非展示串）：
 * <ul>
 *   <li>归属一律存账号主键 id（公海以 {@code null} 表示），<b>不</b>存邮箱 / 姓名等可变展示值；</li>
 *   <li>阶段 / 流失原因存枚举码；</li>
 *   <li>金额存精确数值的 {@code toPlainString()}（<b>不</b>用浮点），读出侧再做千分位展示。</li>
 * </ul>
 * 读出侧据此按<b>当前</b>账号信息解析姓名（design D4）。
 */
public final class SystemLogDetails {

    private SystemLogDetails() {
    }

    /** 归属变更（认领 / 回收 / 分配 / 转移）：公海侧传 null。 */
    public static Map<String, Object> ownerChange(Long fromOwnerId, Long toOwnerId) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("fromOwnerId", fromOwnerId);
        d.put("toOwnerId", toOwnerId);
        return d;
    }

    /** 退回公海：归属变更 + 退回备注。 */
    public static Map<String, Object> release(Long fromOwnerId, String note) {
        Map<String, Object> d = ownerChange(fromOwnerId, null);
        d.put("releaseNote", note);
        return d;
    }

    /** 阶段变更：原 / 新阶段枚举码。 */
    public static Map<String, Object> stageChange(String fromStageCode, String toStageCode) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("fromStage", fromStageCode);
        d.put("toStage", toStageCode);
        return d;
    }

    /** 赢单：精确金额（plain string）+ 签订日期（ISO）。 */
    public static Map<String, Object> win(BigDecimal contractAmount, LocalDate signedDate) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("contractAmount", contractAmount == null ? null : contractAmount.toPlainString());
        d.put("signedDate", signedDate == null ? null : signedDate.toString());
        return d;
    }

    /** 流失：原因枚举码 + 说明。 */
    public static Map<String, Object> lose(String loseReasonCode, String loseNote) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("loseReason", loseReasonCode);
        d.put("loseNote", loseNote);
        return d;
    }

    /** 新建线索：归属 ownerSalesId（公海 null）+ 客户名 + 业务类型。 */
    public static Map<String, Object> leadCreate(Long ownerSalesId, String customerName, String businessType) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("ownerSalesId", ownerSalesId);
        d.put("customerName", customerName);
        d.put("businessType", businessType);
        return d;
    }

    /** 创建账号：姓名 + 角色码。 */
    public static Map<String, Object> accountCreate(String accountName, String roleCode) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("accountName", accountName);
        d.put("role", roleCode);
        return d;
    }

    /** 账号启用 / 停用：姓名 + 新状态码。 */
    public static Map<String, Object> accountStatus(String accountName, String statusCode) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("accountName", accountName);
        d.put("status", statusCode);
        return d;
    }
}
