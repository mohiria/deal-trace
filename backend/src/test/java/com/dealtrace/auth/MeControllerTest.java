package com.dealtrace.auth;

import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.common.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec R3（当前用户查询自身信息）。
 */
@AutoConfigureMockMvc
class MeControllerTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void cleanAccounts() {
        accountMapper.delete(null);
    }

    @Test
    void authenticatedUserSeesOwnProfileWithoutSensitiveFields() throws Exception {
        Account a = new Account();
        a.setEmail("me@dealtrace.test");
        a.setName("Me User");
        a.setRole(Role.SALES);
        a.setStatus(AccountStatus.ENABLED);
        a.setPasswordHash(passwordEncoder.encode("p@ssw0rd"));
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);

        String token = jwtService.generateToken(a);

        MvcResult result = mockMvc.perform(get("/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.id").value(a.getId()))
            .andExpect(jsonPath("$.data.email").value("me@dealtrace.test"))
            .andExpect(jsonPath("$.data.name").value("Me User"))
            .andExpect(jsonPath("$.data.role").value("SALES"))
            .andExpect(jsonPath("$.data.status").value("ENABLED"))
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
            .as("/me 响应体不得包含 passwordHash / token 等敏感字段")
            .doesNotContain("passwordHash")
            .doesNotContain("\"token\"")
            .doesNotContain(a.getPasswordHash());
    }
}
