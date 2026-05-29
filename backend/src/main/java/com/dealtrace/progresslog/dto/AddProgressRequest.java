package com.dealtrace.progresslog.dto;

/**
 * 新增进度入参（progress-log / PRD §7.8）。
 * {@code method} 以中文标签接收，service 层经 {@link com.dealtrace.progresslog.entity.TrackMethod#fromDbValue}
 * 解析（缺失 / 非枚举 → VALIDATION_ERROR）；{@code content} trim 后必填非空。
 * {@code trackTime} / {@code trackerId} 不在入参——服务端派生（design D2）。
 * 不加校验注解，空 / 非法均由 service 层判 VALIDATION_ERROR（与 WinLeadRequest 模式一致）。
 */
public record AddProgressRequest(String method, String content) {
}
