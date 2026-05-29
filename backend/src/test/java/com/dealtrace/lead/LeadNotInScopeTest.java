package com.dealtrace.lead;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Spec R9：lead-core 阶段不存在公海视图 / 认领 / 退回 / Admin 三剑客 / 阶段切换 /
 * 赢单 / 流失等端点。后续 change 落地时移除对应 reverse assertion 即可。
 */
@AutoConfigureMockMvc
class LeadNotInScopeTest extends IntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private JwtService jwtService;

    private String token;

    @BeforeEach
    void seed() {
        accountMapper.delete(null);
        Account a = new Account();
        a.setEmail("not-in-scope-admin@dealtrace.test");
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

    private void assertNotSuccess(MvcResult result) throws Exception {
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        assertThat(status).as("应为非 200").isNotEqualTo(200);
        assertThat(body).doesNotContain("\"code\":\"SUCCESS\"");
    }

    @Test
    void claim_isNotMapped() throws Exception {
        MvcResult r = mockMvc.perform(post("/leads/1/claim")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andReturn();
        assertNotSuccess(r);
    }

    @Test
    void release_isNotMapped() throws Exception {
        MvcResult r = mockMvc.perform(post("/leads/1/release")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType("application/json")
                .content("{\"note\":\"x\"}"))
            .andReturn();
        assertNotSuccess(r);
    }

    @Test
    void assign_isNotMapped() throws Exception {
        MvcResult r = mockMvc.perform(post("/leads/1/assign")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType("application/json")
                .content("{\"ownerSalesId\":1}"))
            .andReturn();
        assertNotSuccess(r);
    }

    @Test
    void stageChange_isNotMapped() throws Exception {
        MvcResult r = mockMvc.perform(patch("/leads/1/stage")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType("application/json")
                .content("{\"stage\":\"初步沟通\"}"))
            .andReturn();
        assertNotSuccess(r);
    }

    @Test
    void win_isNotMapped() throws Exception {
        MvcResult r = mockMvc.perform(post("/leads/1/win")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType("application/json")
                .content("{\"amount\":10000,\"signDate\":\"2026-05-29\"}"))
            .andReturn();
        assertNotSuccess(r);
    }

    @Test
    void lose_isNotMapped() throws Exception {
        MvcResult r = mockMvc.perform(post("/leads/1/lose")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType("application/json")
                .content("{\"loseReason\":\"价格过高\"}"))
            .andReturn();
        assertNotSuccess(r);
    }
}
