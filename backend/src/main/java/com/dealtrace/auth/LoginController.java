package com.dealtrace.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.auth.dto.LoginRequest;
import com.dealtrace.auth.dto.LoginResponse;
import com.dealtrace.common.ApiResponse;
import com.dealtrace.common.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spec R1（邮箱与密码登录）。
 *
 * <p>邮箱不存在 / 密码错统一返回 UNAUTHORIZED + 同一 message（"邮箱或密码错误"，防账号枚举）；
 * 停用账号返回 UNAUTHORIZED + message="账号已停用"。
 */
@RestController
@RequestMapping("/auth")
public class LoginController {

    private static final String INVALID_CREDENTIALS_MESSAGE = "邮箱或密码错误";
    private static final String ACCOUNT_DISABLED_MESSAGE = "账号已停用";

    private final AccountMapper accountMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public LoginController(AccountMapper accountMapper,
                           PasswordEncoder passwordEncoder,
                           JwtService jwtService) {
        this.accountMapper = accountMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<?>> login(@Valid @RequestBody LoginRequest request) {
        Account account = accountMapper.selectOne(
            new QueryWrapper<Account>().eq("email", request.email())
        );
        if (account == null) {
            return unauthorized(INVALID_CREDENTIALS_MESSAGE);
        }
        if (account.getStatus() == AccountStatus.DISABLED) {
            return unauthorized(ACCOUNT_DISABLED_MESSAGE);
        }
        if (!passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            return unauthorized(INVALID_CREDENTIALS_MESSAGE);
        }
        String token = jwtService.generateToken(account);
        return ResponseEntity.ok(ApiResponse.ok(
            new LoginResponse(token, account.getEmail(), account.getName(), account.getRole())
        ));
    }

    private ResponseEntity<ApiResponse<?>> unauthorized(String message) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiResponse.error(ErrorCode.UNAUTHORIZED, message));
    }
}
