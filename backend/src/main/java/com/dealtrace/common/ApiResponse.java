package com.dealtrace.common;

/**
 * 统一 API 响应信封（tech-arch §6.2 / platform-foundation R1）。
 *
 * <p>{@code code} 为 {@link ErrorCode} 名称的字符串形式；{@code message} 为人类可读消息；
 * {@code data} 为业务负载（无返回值时为 {@code null}）。
 */
public record ApiResponse<T>(String code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.name(), "OK", data);
    }

    public static ApiResponse<Void> error(ErrorCode code, String message) {
        return new ApiResponse<>(code.name(), message, null);
    }
}
