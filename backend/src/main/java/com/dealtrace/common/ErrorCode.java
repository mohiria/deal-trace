package com.dealtrace.common;

/**
 * 错误码枚举。
 *
 * <p>scaffold-monorepo 阶段仅包含 tech-arch §6.3 通用类目；业务专属错误码
 * （DUPLICATE_CUSTOMER / DUPLICATE_ACTIVE_LEAD / LEAD_ALREADY_CLAIMED /
 * LEAD_ENDED_READONLY / ACCOUNT_DISABLED 等）由 bootstrap-dealtrace-mvp 的
 * 对应 capability spec 在 apply 时追加进本枚举。
 */
public enum ErrorCode {
    SUCCESS,
    VALIDATION_ERROR,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    INTERNAL_ERROR
}
