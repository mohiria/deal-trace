package com.dealtrace.auth.dto;

import com.dealtrace.account.entity.Role;

public record LoginResponse(String token, String email, String name, Role role) {
}
