package com.dealtrace.auth.dto;

import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;

public record MeResponse(Long id, String email, String name, Role role, AccountStatus status) {
}
