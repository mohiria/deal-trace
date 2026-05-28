package com.dealtrace.common;

/**
 * 业务异常基类。{@link GlobalExceptionHandler} 据 {@code errorCode} 映射 HTTP 状态。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
