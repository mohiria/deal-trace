package com.dealtrace.customer.service;

import java.util.Locale;

/**
 * 统一社会信用代码（USCI）归一化 + GB 32100-2015 校验位校验。
 *
 * <p>Spec R3 + design D3：使用契约是「先 {@link #normalize} 再 {@link #isValid}」。
 * isValid 假设入参已归一化（trim + 大写），不做二次容忍——避免 CLAUDE.md 反直觉规则
 * 「USCI 顺序反了会把 `91110000abc...` 当不同记录」。
 *
 * <p>校验位算法：
 * <pre>
 *   sum = Σ (charset.indexOf(c_i) * weight_i)  for i in 0..16
 *   expected = (31 - sum mod 31) mod 31
 *   actual   = charset.indexOf(c_17)
 *   valid    = (expected == actual)
 * </pre>
 */
public final class UsciValidator {

    /** GB 32100-2015 校验位字符集：31 个字符，去掉易混淆的 I / O / S / V / Z。 */
    private static final String CHARSET = "0123456789ABCDEFGHJKLMNPQRTUWXY";

    /** 前 17 位的加权系数。 */
    private static final int[] WEIGHTS = {
        1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28
    };

    private UsciValidator() {}

    /**
     * 归一化：trim 首尾空白 + 字母统一转大写（Locale.ROOT 避免土耳其语 i/I 怪行为）。
     * {@code null} → {@code null}；空白字符串 → 空字符串。**不**校验长度或字符集。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.strip().toUpperCase(Locale.ROOT);
    }

    /**
     * 校验：长度 == 18、全部字符落在 {@link #CHARSET} 集合、第 18 位与算法计算结果一致。
     * 假设入参已 {@link #normalize}；传入小写字母 / 含空白等情况一律返回 {@code false}。
     */
    public static boolean isValid(String normalized) {
        if (normalized == null || normalized.length() != 18) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            int v = CHARSET.indexOf(normalized.charAt(i));
            if (v < 0) {
                return false;
            }
            sum += v * WEIGHTS[i];
        }
        int expected = (31 - sum % 31) % 31;
        int actual = CHARSET.indexOf(normalized.charAt(17));
        return expected == actual;
    }
}
