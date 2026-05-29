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
 * Spec ADDED 认领：成功 / 已被认领 409 / 已结束 400 / LEAD_CLAIM 日志。
 */
@AutoConfigureMockMvc
class LeadClaimTest extends IntegrationTest {

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
    private Long poolLeadId;
    private Long claimedLeadId;
    private Long endedPoolLeadId;

    @BeforeEach
    void seed() {
        leadMapper.delete(null);
        customerMapper.delete(null);
        accountMapper.delete(null);

        salesA = insertAccount("claim-a@dealtrace.test", Role.SALES);
        salesB = insertAccount("claim-b@dealtrace.test", Role.SALES);

        Customer c = new Customer();
        c.setName("Claim Customer");
        c.setUsci(VALID_USCI);
        c.setCreatedAt(LocalDateTime.now());
        customerMapper.insert(c);
        customerId = c.getId();

        poolLeadId = insertLead(null, LeadStage.CONTACTED, BusinessType.BIM_CONSULTING);
        claimedLeadId = insertLead(salesB.getId(), LeadStage.UNTOUCHED, BusinessType.BIM_TRAINING);
        endedPoolLeadId = insertLead(null, LeadStage.LOST, BusinessType.CUSTOM_DEVELOPMENT);
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

    @Test
    void claim_poolLead_succeeds_ownerBecomesSelf_stageUnchanged() throws Exception {
        String token = jwtService.generateToken(salesA);
        mockMvc.perform(post("/leads/" + poolLeadId + "/claim")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.ownerSalesId").value(salesA.getId()))
            .andExpect(jsonPath("$.data.stage").value("初步沟通"));

        Lead reloaded = leadMapper.selectById(poolLeadId);
        assertThat(reloaded.getOwnerSalesId()).isEqualTo(salesA.getId());
        assertThat(reloaded.getStage()).isEqualTo(LeadStage.CONTACTED);
    }

    @Test
    void claim_alreadyClaimedLead_returns409() throws Exception {
        String token = jwtService.generateToken(salesA);
        mockMvc.perform(post("/leads/" + claimedLeadId + "/claim")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LEAD_ALREADY_CLAIMED"));

        assertThat(leadMapper.selectById(claimedLeadId).getOwnerSalesId()).isEqualTo(salesB.getId());
    }

    @Test
    void claim_endedLead_returns400() throws Exception {
        String token = jwtService.generateToken(salesA);
        mockMvc.perform(post("/leads/" + endedPoolLeadId + "/claim")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("LEAD_ENDED_READONLY"));

        assertThat(leadMapper.selectById(endedPoolLeadId).getOwnerSalesId()).isNull();
    }

    @Test
    void claim_writesLeadClaimSystemLog() throws Exception {
        String token = jwtService.generateToken(salesA);
        mockMvc.perform(post("/leads/" + poolLeadId + "/claim")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk());

        // 服务在与本测试相同的事务内写 system_log，按 lead_id 过滤即可定位本次日志（@Rollback 撤销，零泄漏）
        List<Map<String, Object>> logs = jdbcTemplate.queryForList(
            "SELECT action, target_type, target_id, operator_id, lead_id FROM system_log WHERE lead_id = ?",
            poolLeadId);
        assertThat(logs).hasSize(1);
        Map<String, Object> log = logs.get(0);
        assertThat(log.get("action")).isEqualTo("LEAD_CLAIM");
        assertThat(log.get("target_type")).isEqualTo("LEAD");
        assertThat(((Number) log.get("target_id")).longValue()).isEqualTo(poolLeadId);
        assertThat(((Number) log.get("operator_id")).longValue()).isEqualTo(salesA.getId());
        assertThat(((Number) log.get("lead_id")).longValue()).isEqualTo(poolLeadId);
    }
}
