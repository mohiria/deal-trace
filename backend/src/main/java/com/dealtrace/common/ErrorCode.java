package com.dealtrace.common;

/**
 * 错误码枚举。
 *
 * <p>scaffold-monorepo 阶段仅落 tech-arch §6.3 通用类目（前 6 项）；业务专属错误码
 * 由对应 capability spec 在 apply 时追加：
 * <ul>
 *   <li>{@link #DUPLICATE_CUSTOMER}：customer change（PRD §7.2 / §8.1）</li>
 *   <li>{@link #DUPLICATE_ACTIVE_LEAD} / {@link #DUPLICATE_WON_LEAD}：lead-core change（PRD §8.2）</li>
 *   <li>LEAD_ALREADY_CLAIMED：未来 lead-ownership change</li>
 *   <li>LEAD_ENDED_READONLY：未来 lead-closure change</li>
 *   <li>ACCOUNT_DISABLED：当前 auth-account 通过 JWT 过滤层 401 + message 表达，未引入独立 code</li>
 * </ul>
 */
public enum ErrorCode {
    SUCCESS,
    VALIDATION_ERROR,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    INTERNAL_ERROR,
    DUPLICATE_CUSTOMER,
    DUPLICATE_ACTIVE_LEAD,
    DUPLICATE_WON_LEAD
}
