package com.dealtrace.account;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec R5（Admin 创建 Sales 账号）+ R8 / scenario 1（创建路径密码哈希存储）。
 */
@AutoConfigureMockMvc
class AdminAccountControllerCreateTest extends IntegrationTest {

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
        admin = insert("root-admin@dealtrace.test", "Root Admin", Role.ADMIN, AccountStatus.ENABLED);
        sales = insert("normal-sales@dealtrace.test", "Sales User", Role.SALES, AccountStatus.ENABLED);
    }

    private Account insert(String email, String name, Role role, AccountStatus status) {
        Account a = new Account();
        a.setEmail(email);
        a.setName(name);
        a.setRole(role);
        a.setStatus(status);
        a.setPasswordHash(passwordEncoder.encode("p@ssw0rd"));
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);
        return a;
    }

    private RequestBuilder createReq(String body, String token) {
        var rb = post("/admin/accounts")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
        if (token != null) {
            rb = rb.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return rb;
    }

    private String body(String email, String name, String password, String role) {
        return "{\"email\":\"" + email + "\",\"name\":\"" + name +
            "\",\"password\":\"" + password + "\",\"role\":\"" + role + "\"}";
    }

    @Test
    void adminCreatesSalesSuccessfully() throws Exception {
        String token = jwtService.generateToken(admin);

        MvcResult result = mockMvc.perform(createReq(
                body("new-sales@dealtrace.test", "New Sales", "s@les123", "SALES"), token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.id").isNumber())
            .andExpect(jsonPath("$.data.email").value("new-sales@dealtrace.test"))
            .andExpect(jsonPath("$.data.name").value("New Sales"))
            .andExpect(jsonPath("$.data.role").value("SALES"))
            .andExpect(jsonPath("$.data.status").value("ENABLED"))
            .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody)
            .as("创建响应不得回传 password / passwordHash 字段")
            .doesNotContain("password")
            .doesNotContain("passwordHash");
    }

    @Test
    void salesAndAnonymousCannotCreateAccount() throws Exception {
        String salesToken = jwtService.generateToken(sales);

        mockMvc.perform(createReq(body("x@dealtrace.test", "X", "p@ssw0rd", "SALES"), salesToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(createReq(body("y@dealtrace.test", "Y", "p@ssw0rd", "SALES"), null))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        Long countX = accountMapper.selectCount(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Account>()
                .eq("email", "x@dealtrace.test"));
        Long countY = accountMapper.selectCount(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Account>()
                .eq("email", "y@dealtrace.test"));
        assertThat(countX).as("Sales 调创建端点不得插入账号").isZero();
        assertThat(countY).as("匿名调创建端点不得插入账号").isZero();
    }

    @Test
    void duplicateEmailRejected() throws Exception {
        String token = jwtService.generateToken(admin);

        mockMvc.perform(createReq(
                body("normal-sales@dealtrace.test", "Dup", "p@ssw0rd", "SALES"), token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("邮箱已存在"));
    }

    @Test
    void nonSalesRoleRejected() throws Exception {
        String token = jwtService.generateToken(admin);

        mockMvc.perform(createReq(
                body("another-admin@dealtrace.test", "Bad", "p@ssw0rd", "ADMIN"), token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void blankRequiredFieldRejected() throws Exception {
        String token = jwtService.generateToken(admin);

        mockMvc.perform(createReq(body("", "Some", "p@ssw0rd", "SALES"), token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(createReq(body("z@dealtrace.test", "", "p@ssw0rd", "SALES"), token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(createReq(body("w@dealtrace.test", "Some", "", "SALES"), token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
