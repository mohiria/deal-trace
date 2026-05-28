package com.dealtrace.account.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateAccountRequest(
    @NotBlank @Email String email,
    @NotBlank String name,
    @NotBlank String password,
    @NotBlank String role
) {
}
