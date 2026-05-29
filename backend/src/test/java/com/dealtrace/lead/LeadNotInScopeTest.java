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
 * Spec R9（经 lead-ownership MODIFY）：阶段切换 / 赢单 / 流失端点仍未在当前 capability 暴露。
 *
 * <p>公海视图 / 认领 / 退回 / 分配 / 回收 / 转移已由 lead-ownership change 实现，
 * 对应 reverse assertion（claim / release / assign isNotMapped）已按 spec ADDED 要求移除——
 * 权威依据：{@code openspec/changes/lead-ownership/specs/lead/spec.md} 的 ADDED 认领/退回/分配
 * requirement 与 MODIFIED「不在范围内的能力」requirement。stage / win / lose 留待 lead-stage /
 * lead-closure change 各自落地时再移除。
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
