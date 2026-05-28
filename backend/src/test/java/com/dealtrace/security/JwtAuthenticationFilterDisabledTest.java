package com.dealtrace.security;

import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.auth.JwtService;
import com.dealtrace.common.MultiTransactionalIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec R2 / Scenario "停用账号的有效令牌在下次请求被拒"。
 *
 * <p>该场景要求 status 更新在独立事务提交后才被 Filter 看到，故继承
 * {@link MultiTransactionalIntegrationTest}（propagation=NOT_SUPPORTED），
 * 每个步骤在自己的事务边界提交。{@code @AfterEach} 清空 account 表保证测试隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
class JwtAuthenticationFilterDisabledTest extends MultiTransactionalIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private JwtService jwtService;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    protected Set<String> tablesToTruncate() {
        return Set.of("account");
    }

    @AfterEach
    void waitForCleanup() {
        // 借父类 @AfterEach 完成 TRUNCATE
    }

    @Test
    void disabledAfterIssueDeniesNextRequest() throws Exception {
        Account a = new Account();
        a.setEmail("to-be-disabled@dealtrace.test");
        a.setName("ToBeDisabled");
        a.setRole(Role.SALES);
        a.setStatus(AccountStatus.ENABLED);
        a.setPasswordHash(encoder.encode("p@ssw0rd"));
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);

        String token = jwtService.generateToken(a);

        // 改状态为 DISABLED 并提交（NOT_SUPPORTED → 直接走 DataSource auto-commit）
        a.setStatus(AccountStatus.DISABLED);
        a.setUpdatedAt(LocalDateTime.now());
        a.setDisabledAt(LocalDateTime.now());
        accountMapper.updateById(a);

        mockMvc.perform(get("/test/protected")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").value("账号已停用"));
    }
}
