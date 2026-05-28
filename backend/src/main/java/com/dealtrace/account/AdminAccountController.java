package com.dealtrace.account;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dealtrace.account.dto.AccountView;
import com.dealtrace.account.dto.CreateAccountRequest;
import com.dealtrace.account.dto.UpdateAccountStatusRequest;
import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.common.ApiResponse;
import com.dealtrace.common.BusinessException;
import com.dealtrace.common.ErrorCode;
import com.dealtrace.security.AccountPrincipal;
import com.dealtrace.systemlog.SystemLogPort;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spec R5 + R6（Admin 创建 Sales / 列出账号）。
 * 路径 {@code /admin/**} 在 {@link com.dealtrace.security.SecurityConfig} 强制 ROLE_ADMIN。
 */
@RestController
@RequestMapping("/admin/accounts")
public class AdminAccountController {

    private final AccountMapper accountMapper;
    private final PasswordEncoder passwordEncoder;
    private final SystemLogPort systemLogPort;

    public AdminAccountController(AccountMapper accountMapper,
                                  PasswordEncoder passwordEncoder,
                                  SystemLogPort systemLogPort) {
        this.accountMapper = accountMapper;
        this.passwordEncoder = passwordEncoder;
        this.systemLogPort = systemLogPort;
    }

    @PostMapping
    public ApiResponse<AccountView> createSales(
            @AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody CreateAccountRequest request) {
        if (!"SALES".equals(request.role())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "角色非法，仅支持 SALES");
        }
        Long duplicate = accountMapper.selectCount(
            new QueryWrapper<Account>().eq("email", request.email())
        );
        if (duplicate != null && duplicate > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "邮箱已存在");
        }
        Account a = new Account();
        a.setEmail(request.email());
        a.setName(request.name());
        a.setRole(Role.SALES);
        a.setStatus(AccountStatus.ENABLED);
        a.setPasswordHash(passwordEncoder.encode(request.password()));
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);

        systemLogPort.record("ACCOUNT_CREATE", "ACCOUNT", a.getId(), principal.id());

        return ApiResponse.ok(AccountView.from(a));
    }

    @GetMapping
    public ApiResponse<List<AccountView>> list() {
        List<Account> all = accountMapper.selectList(
            new QueryWrapper<Account>().orderByAsc("created_at")
        );
        return ApiResponse.ok(all.stream().map(AccountView::from).toList());
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<AccountView> updateStatus(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody UpdateAccountStatusRequest request) {
        AccountStatus target;
        try {
            target = AccountStatus.valueOf(request.status());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "status 取值非法");
        }
        if (id.equals(principal.id()) && target == AccountStatus.DISABLED) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不可停用自己");
        }
        Account account = accountMapper.selectById(id);
        if (account == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "账号不存在");
        }
        if (account.getStatus() == target) {
            return ApiResponse.ok(AccountView.from(account));
        }
        account.setStatus(target);
        account.setUpdatedAt(LocalDateTime.now());
        account.setDisabledAt(target == AccountStatus.DISABLED ? LocalDateTime.now() : null);
        accountMapper.updateById(account);

        String action = target == AccountStatus.DISABLED ? "ACCOUNT_DISABLE" : "ACCOUNT_ENABLE";
        systemLogPort.record(action, "ACCOUNT", account.getId(), principal.id());

        return ApiResponse.ok(AccountView.from(account));
    }
}
