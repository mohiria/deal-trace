package com.dealtrace.lead.dto;

/**
 * 分配入参（spec ADDED 分配）。{@code salesId} 指向目标 ENABLED SALES；
 * 合法性（存在 / 角色 / 状态）校验在 service 层。
 */
public record AssignLeadRequest(Long salesId) {
}
