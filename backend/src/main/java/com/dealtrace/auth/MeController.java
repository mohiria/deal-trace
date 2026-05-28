package com.dealtrace.auth;

import com.dealtrace.account.entity.Account;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.auth.dto.MeResponse;
import com.dealtrace.common.ApiResponse;
import com.dealtrace.security.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class MeController {

    private final AccountMapper accountMapper;

    public MeController(AccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal AccountPrincipal principal) {
        Account a = accountMapper.selectById(principal.id());
        return ApiResponse.ok(new MeResponse(
            a.getId(), a.getEmail(), a.getName(), a.getRole(), a.getStatus()
        ));
    }
}
