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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SecurityConfig path-level 规则的决策表覆盖（tasks §11.2）。
 */
@AutoConfigureMockMvc
class SecurityPathRuleTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private Account admin;
    private Account sales;

    @BeforeEach
    void seed() {
        accountMapper.delete(null);
        admin = insert("path-admin@dealtrace.test", "PathAdmin", Role.ADMIN);
        sales = insert("path-sales@dealtrace.test", "PathSales", Role.SALES);
    }

    private Account insert(String email, String name, Role role) {
        Account a = new Account();
        a.setEmail(email);
        a.setName(name);
        a.setRole(role);
        a.setStatus(AccountStatus.ENABLED);
        a.setPasswordHash(passwordEncoder.encode("p@ssw0rd"));
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);
        return a;
    }

    @Test
    void anonymousLoginPermitted() throws Exception {
        // 凭证错误是预期的，但 status 必须不是 401（permitAll）；登录失败是 401 但 code=UNAUTHORIZED 而非 Spring 默认 EntryPoint。
        // 这里用合法 body + 不存在邮箱：login controller 自身返回 401 UNAUTHORIZED；
        // 关键是请求**进入了** controller（即 permitAll 生效），未被 SecurityConfig 提前 401。
        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody@dealtrace.test\",\"password\":\"p@ssw0rd\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").value("邮箱或密码错误"));
    }

    @Test
    void anonymousAdminListReturns401() throws Exception {
        mockMvc.perform(get("/admin/accounts"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void salesAdminListReturns403() throws Exception {
        String token = jwtService.generateToken(sales);
        mockMvc.perform(get("/admin/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void salesMeReturns200() throws Exception {
        String token = jwtService.generateToken(sales);
        mockMvc.perform(get("/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    void adminAdminListReturns200() throws Exception {
        String token = jwtService.generateToken(admin);
        mockMvc.perform(get("/admin/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"));
    }
}
