package com.dealtrace.account.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateAccountStatusRequest(@NotBlank String status) {
}
