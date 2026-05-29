package com.dealtrace.lead;

import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.auth.JwtService;
import com.dealtrace.common.IntegrationTest;
import com.dealtrace.customer.entity.Customer;
import com.dealtrace.customer.repository.CustomerMapper;
import com.dealtrace.lead.entity.BusinessType;
import com.dealtrace.lead.entity.Lead;
import com.dealtrace.lead.entity.LeadStage;
import com.dealtrace.lead.repository.LeadMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec ADDED 退回：成功 stage 不变 / 缺备注 400 / 非自己名下 404 / 已结束 400 / LEAD_RELEASE 日志含备注。
 */
@AutoConfigureMockMvc
class LeadReleaseTest extends IntegrationTest {

    private static final String VALID_USCI = "91110000123456789Q";

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private LeadMapper leadMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Account salesA;
    private Account salesB;
    private Long customerId;
    private Long ownByAId;
    private Long ownByBId;
    private Long endedOwnByAId;

    @BeforeEach
    void seed() {
        leadMapper.delete(null);
        customerMapper.delete(null);
        accountMapper.delete(null);

        salesA = insertAccount("release-a@dealtrace.test", Role.SALES);
        salesB = insertAccount("release-b@dealtrace.test", Role.SALES);

        Customer c = new Customer();
        c.setName("Release Customer");
        c.setUsci(VALID_USCI);
        c.setCreatedAt(LocalDateTime.now());
        customerMapper.insert(c);
        customerId = c.getId();

        ownByAId = insertLead(salesA.getId(), LeadStage.QUOTED, BusinessType.BIM_CONSULTING);
        ownByBId = insertLead(salesB.getId(), LeadStage.UNTOUCHED, BusinessType.BIM_TRAINING);
        endedOwnByAId = insertLead(salesA.getId(), LeadStage.WON, BusinessType.CUSTOM_DEVELOPMENT);
    }

    private Account insertAccount(String email, Role role) {
        Account a = new Account();
        a.setEmail(email);
        a.setName(email);
        a.setRole(role);
        a.setStatus(AccountStatus.ENABLED);
        a.setPasswordHash(passwordEncoder.encode("p@ssw0rd"));
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);
        return a;
    }

    private Long insertLead(Long ownerId, LeadStage stage, BusinessType type) {
        Lead l = new Lead();
        l.setCustomerId(customerId);
        l.setBusinessYear((short) LocalDate.now().getYear());
        l.setBusinessType(type);
        l.setContactName("X");
        l.setContactPhone("13800000000");
        l.setOwnerSalesId(ownerId);
        l.setStage(stage);
        l.setCreatedAt(LocalDateTime.now());
        leadMapper.insert(l);
        return l.getId();
    }

    private void release(Long id, String body, String token,
                         org.springframework.test.web.servlet.ResultMatcher... matchers) throws Exception {
        var rb = post("/leads/" + id + "/release")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
        var actions = mockMvc.perform(rb);
        for (var m : matchers) actions = actions.andExpect(m);
    }

    @Test
    void release_ownLead_succeeds_ownerCleared_stagePreserved() throws Exception {
        String token = jwtService.generateToken(salesA);
        release(ownByAId, "{\"releaseNote\":\"客户暂无预算\"}", token,
            status().isOk(),
            jsonPath("$.code").value("SUCCESS"),
            jsonPath("$.data.ownerSalesId").doesNotExist(),
            jsonPath("$.data.stage").value("方案报价"));

        Lead reloaded = leadMapper.selectById(ownByAId);
        assertThat(reloaded.getOwnerSalesId()).isNull();
        assertThat(reloaded.getStage()).isEqualTo(LeadStage.QUOTED);
    }

    @Test
    void release_missingNote_returns400() throws Exception {
        String token = jwtService.generateToken(salesA);
        release(ownByAId, "{\"releaseNote\":\"   \"}", token,
            status().isBadRequest(),
            jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(leadMapper.selectById(ownByAId).getOwnerSalesId()).isEqualTo(salesA.getId());
    }

    @Test
    void release_otherSalesLead_returns404() throws Exception {
        String token = jwtService.generateToken(salesA);
        release(ownByBId, "{\"releaseNote\":\"试图退回他人线索\"}", token,
            status().isNotFound(),
            jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void release_endedLead_returns400() throws Exception {
        String token = jwtService.generateToken(salesA);
        release(endedOwnByAId, "{\"releaseNote\":\"合法备注\"}", token,
            status().isBadRequest(),
            jsonPath("$.code").value("LEAD_ENDED_READONLY"));
    }

    @Test
    void release_writesLeadReleaseSystemLogWithNote() throws Exception {
        String token = jwtService.generateToken(salesA);
        release(ownByAId, "{\"releaseNote\":\"客户暂无预算\"}", token, status().isOk());

        List<Map<String, Object>> logs = jdbcTemplate.queryForList(
            "SELECT action, target_id, operator_id, lead_id, summary FROM system_log WHERE lead_id = ?",
            ownByAId);
        assertThat(logs).hasSize(1);
        Map<String, Object> log = logs.get(0);
        assertThat(log.get("action")).isEqualTo("LEAD_RELEASE");
        assertThat(((Number) log.get("target_id")).longValue()).isEqualTo(ownByAId);
        assertThat(((Number) log.get("operator_id")).longValue()).isEqualTo(salesA.getId());
        assertThat(((Number) log.get("lead_id")).longValue()).isEqualTo(ownByAId);
        assertThat((String) log.get("summary")).contains("客户暂无预算");
    }
}
