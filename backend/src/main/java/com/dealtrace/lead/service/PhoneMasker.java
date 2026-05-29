package com.dealtrace.lead.service;

/**
 * 联系电话脱敏（tech-arch §9.4）。供公海列表 Sales 视角使用；Admin 视角直返明文。
 *
 * <p>分级规则（按 trim 后字符串长度）：
 * <ul>
 *   <li>11 位手机号 → 前 3 + {@code ****} + 后 4（如 {@code 138****5678}）</li>
 *   <li>非 11 位且 ≥8 位（含连字符的座机）→ 前 3 + {@code ****} + 后 4</li>
 *   <li>&lt;8 位 → 仅展示后 2 位，其余以 {@code *} 占位</li>
 * </ul>
 * 中间一律固定为 4 个 {@code *}，不随被隐藏位数变化（与 §9.4 示例一致）。
 */
public final class PhoneMasker {

    private PhoneMasker() {}

    public static String mask(String phone) {
        if (phone == null) {
            return null;
        }
        String s = phone.strip();
        int len = s.length();
        if (len == 0) {
            return s;
        }
        if (len < 8) {
            int visible = Math.min(2, len);
            return "*".repeat(len - visible) + s.substring(len - visible);
        }
        return s.substring(0, 3) + "****" + s.substring(len - 4);
    }
}
