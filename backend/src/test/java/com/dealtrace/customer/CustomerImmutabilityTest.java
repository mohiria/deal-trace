package com.dealtrace.customer;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Spec R6 negative：PUT / PATCH / DELETE 不应映射成功路径（MVP 不支持编辑/删除）。
 *
 * <p>断言：返回状态非 200 SUCCESS。具体可能是 404（NoHandlerFoundException）或 405
 * （Method Not Allowed）—— 关键是端点未被实现且未被错误暴露。
 */
@AutoConfigureMockMvc
class CustomerImmutabilityTest extends IntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private JwtService jwtService;

    private String token;

    @BeforeEach
    void seed() {
        accountMapper.delete(null);
        Account a = new Account();
        a.setEmail("immutability-test@dealtrace.test");
        a.setName("X");
        a.setRole(Role.ADMIN);
        a.setStatus(AccountStatus.ENABLED);
        a.setPasswordHash("placeholder-hash-do-not-use-for-login-please-32+");
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);
        token = jwtService.generateToken(a);
    }

    @Test
    void put_isNotMapped() throws Exception {
        MvcResult result = mockMvc.perform(put("/customers/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType("application/json")
                .content("{\"name\":\"x\",\"usci\":\"y\"}"))
            .andReturn();
        // 非 200，且响应不是 success
        assertThat(result.getResponse().getStatus()).isNotEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("\"code\":\"SUCCESS\"");
    }

    @Test
    void patch_isNotMapped() throws Exception {
        MvcResult result = mockMvc.perform(patch("/customers/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType("application/json")
                .content("{\"name\":\"x\"}"))
            .andReturn();
        assertThat(result.getResponse().getStatus()).isNotEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("\"code\":\"SUCCESS\"");
    }

    @Test
    void delete_isNotMapped() throws Exception {
        MvcResult result = mockMvc.perform(delete("/customers/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andReturn();
        assertThat(result.getResponse().getStatus()).isNotEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("\"code\":\"SUCCESS\"");
    }
}
