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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec R6（Admin 查询账号列表）。
 */
@AutoConfigureMockMvc
class AdminAccountControllerListTest extends IntegrationTest {

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
        admin = insert("admin-list@dealtrace.test", "AdminList", Role.ADMIN, AccountStatus.ENABLED);
        sales = insert("sales-en@dealtrace.test", "SalesEnabled", Role.SALES, AccountStatus.ENABLED);
        insert("sales-dis@dealtrace.test", "SalesDisabled", Role.SALES, AccountStatus.DISABLED);
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

    @Test
    void adminListsAllAccountsWithoutPasswordHash() throws Exception {
        String token = jwtService.generateToken(admin);

        MvcResult result = mockMvc.perform(get("/admin/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[0].id").isNumber())
            .andExpect(jsonPath("$.data[0].email").exists())
            .andExpect(jsonPath("$.data[0].name").exists())
            .andExpect(jsonPath("$.data[0].role").exists())
            .andExpect(jsonPath("$.data[0].status").exists())
            .andExpect(jsonPath("$.data[0].createdAt").exists())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
            .as("列表响应不得包含 passwordHash 字段或原始哈希值")
            .doesNotContain("passwordHash")
            .doesNotContain(admin.getPasswordHash())
            .doesNotContain(sales.getPasswordHash());
    }

    @Test
    void salesCannotListAccounts() throws Exception {
        String token = jwtService.generateToken(sales);
        mockMvc.perform(get("/admin/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
