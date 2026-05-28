package com.dealtrace.security;

import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.auth.JwtService;
import com.dealtrace.common.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec R2（访问令牌携带身份用于后续请求）单事务部分：
 * <ul>
 *   <li>有效 token → 受保护端点放行</li>
 *   <li>缺失 Authorization → 401（{@link SecurityScaffoldTest} 已覆盖匿名场景，本测验证带 ENABLED 账号链路）</li>
 *   <li>非法 token → 401 + message 不含 signature/expired/jwt 等技术细节</li>
 * </ul>
 *
 * <p>"停用后下次请求 401" 因需要跨事务可见，独立成 {@code JwtAuthenticationFilterDisabledTest}。
 */
@AutoConfigureMockMvc
class JwtAuthenticationFilterTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private JwtService jwtService;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void cleanAccounts() {
        accountMapper.delete(null);
    }

    private Account insertEnabledAccount() {
        Account a = new Account();
        a.setEmail("filter-user@dealtrace.test");
        a.setName("Filter User");
        a.setRole(Role.SALES);
        a.setStatus(AccountStatus.ENABLED);
        a.setPasswordHash(encoder.encode("p@ssw0rd"));
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);
        return a;
    }

    @Test
    void validTokenForEnabledAccountIsAdmitted() throws Exception {
        Account a = insertEnabledAccount();
        String token = jwtService.generateToken(a);

        mockMvc.perform(get("/test/protected")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk());
    }

    @Test
    void malformedTokenRejectedWithoutLeakingDetail() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/protected")
                .header(HttpHeaders.AUTHORIZATION, "Bearer this.is.not.a.real.jwt"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
            .as("401 响应不得泄漏 JWT 解析细节（signature / expired / claim 等技术词汇）")
            .doesNotContainIgnoringCase("signature")
            .doesNotContainIgnoringCase("expired")
            .doesNotContainIgnoringCase("malformed")
            .doesNotContainIgnoringCase("claim");
    }
}
