package com.dealtrace.account.dto;

import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;

import java.time.LocalDateTime;

/**
 * 对外 DTO：剥离 passwordHash 等敏感字段。
 */
public record AccountView(
    Long id,
    String email,
    String name,
    Role role,
    AccountStatus status,
    LocalDateTime createdAt
) {
    public static AccountView from(Account a) {
        return new AccountView(a.getId(), a.getEmail(), a.getName(), a.getRole(), a.getStatus(), a.getCreatedAt());
    }
}
