package com.dealtrace.lead.service;

import java.util.regex.Pattern;

/**
 * 联系电话校验（spec R3 + design D7）。
 *
 * <p>支持两种格式：
 * <ul>
 *   <li>中国大陆 11 位手机号：第 1 位为 {@code 1}、第 2 位为 {@code 3-9}、其余 9 位为数字</li>
 *   <li>常见座机：可选 3-4 位区号（{@code 0} 开头）+ 7-8 位号码 + 可选分机号（1-5 位）</li>
 * </ul>
 *
 * <p>归一化策略与 {@link com.dealtrace.customer.service.UsciValidator} 一致：trim 在 isValid
 * 内部完成；上游可放心传 raw 字符串。海外号码格式 MVP 不支持（PRD §8.3.4）。
 */
public final class PhoneValidator {

    private static final Pattern MOBILE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern LANDLINE = Pattern.compile("^(0\\d{2,3}-)?\\d{7,8}(-\\d{1,5})?$");

    private PhoneValidator() {}

    public static boolean isValid(String phone) {
        if (phone == null) {
            return false;
        }
        String s = phone.strip();
        if (s.isEmpty()) {
            return false;
        }
        return MOBILE.matcher(s).matches() || LANDLINE.matcher(s).matches();
    }
}
