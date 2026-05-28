package com.dealtrace.auth;

import com.dealtrace.account.entity.Account;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.auth.dto.ChangePasswordRequest;
import com.dealtrace.common.ApiResponse;
import com.dealtrace.common.ErrorCode;
import com.dealtrace.security.AccountPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * Spec R4（当前用户修改自身密码）。
 *
 * <p>旧密码错 → 401 + message="原密码错误"；新密码 trim 后空 → 400 + VALIDATION_ERROR；
 * 成功 → 200 + 更新 password_hash + updated_at。
 */
@RestController
@RequestMapping("/auth")
public class ChangePasswordController {

    private final AccountMapper accountMapper;
    private final PasswordEncoder passwordEncoder;

    public ChangePasswordController(AccountMapper accountMapper, PasswordEncoder passwordEncoder) {
        this.accountMapper = accountMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<?>> changePassword(
            @AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request) {
        if (request.newPassword().trim().isEmpty()) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.VALIDATION_ERROR, "新密码不可为空"));
        }
        Account account = accountMapper.selectById(principal.id());
        if (account == null || !passwordEncoder.matches(request.oldPassword(), account.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ErrorCode.UNAUTHORIZED, "原密码错误"));
        }
        account.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        account.setUpdatedAt(LocalDateTime.now());
        accountMapper.updateById(account);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
