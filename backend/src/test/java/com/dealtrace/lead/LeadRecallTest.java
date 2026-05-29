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
 * Spec ADDED 回收：成功 stage 不变 / 已在公海拒 / 已结束 400 / LEAD_RECALL 日志含原+新归属。
 */
@AutoConfigureMockMvc
class LeadRecallTest extends IntegrationTest {

    private static final String VALID_USCI = "91110000123456789Q";

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private LeadMapper leadMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Account admin;
    private Account sales;
    private Long customerId;
    private Long ownedLeadId;
    private Long poolLeadId;
    private Long endedOwnedLeadId;

    @BeforeEach
    void seed() {
        leadMapper.delete(null);
        customerMapper.delete(null);
        accountMapper.delete(null);

        admin = insertAccount("recall-admin@dealtrace.test", Role.ADMIN);
        sales = insertAccount("recall-sales@dealtrace.test", Role.SALES);

        Customer c = new Customer();
        c.setName("Recall Customer");
        c.setUsci(VALID_USCI);
        c.setCreatedAt(LocalDateTime.now());
        customerMapper.insert(c);
        customerId = c.getId();

        ownedLeadId = insertLead(sales.getId(), LeadStage.QUOTED, BusinessType.BIM_CONSULTING);
        poolLeadId = insertLead(null, LeadStage.UNTOUCHED, BusinessType.BIM_TRAINING);
        endedOwnedLeadId = insertLead(sales.getId(), LeadStage.WON, BusinessType.CUSTOM_DEVELOPMENT);
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

    private void recall(Long id, String token,
                        org.springframework.test.web.servlet.ResultMatcher... matchers) throws Exception {
        var actions = mockMvc.perform(post("/leads/" + id + "/recall")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
        for (var m : matchers) actions = actions.andExpect(m);
    }

    @Test
    void recall_ownedLead_succeeds_ownerCleared_stagePreserved() throws Exception {
        String token = jwtService.generateToken(admin);
        recall(ownedLeadId, token,
            status().isOk(),
            jsonPath("$.code").value("SUCCESS"),
            jsonPath("$.data.ownerSalesId").doesNotExist(),
            jsonPath("$.data.stage").value("方案报价"));

        Lead reloaded = leadMapper.selectById(ownedLeadId);
        assertThat(reloaded.getOwnerSalesId()).isNull();
        assertThat(reloaded.getStage()).isEqualTo(LeadStage.QUOTED);
    }

    @Test
    void recall_poolLead_rejected() throws Exception {
        String token = jwtService.generateToken(admin);
        recall(poolLeadId, token,
            status().isBadRequest(),
            jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void recall_endedLead_returns400() throws Exception {
        String token = jwtService.generateToken(admin);
        recall(endedOwnedLeadId, token,
            status().isBadRequest(),
            jsonPath("$.code").value("LEAD_ENDED_READONLY"));
        assertThat(leadMapper.selectById(endedOwnedLeadId).getOwnerSalesId()).isEqualTo(sales.getId());
    }

    @Test
    void recall_writesLeadRecallSystemLogWithOwners() throws Exception {
        String token = jwtService.generateToken(admin);
        recall(ownedLeadId, token, status().isOk());

        List<Map<String, Object>> logs = jdbcTemplate.queryForList(
            "SELECT action, target_id, operator_id, summary FROM system_log WHERE lead_id = ?",
            ownedLeadId);
        assertThat(logs).hasSize(1);
        Map<String, Object> log = logs.get(0);
        assertThat(log.get("action")).isEqualTo("LEAD_RECALL");
        assertThat(((Number) log.get("target_id")).longValue()).isEqualTo(ownedLeadId);
        assertThat(((Number) log.get("operator_id")).longValue()).isEqualTo(admin.getId());
        assertThat((String) log.get("summary"))
            .contains(sales.getEmail())
            .contains("公海");
    }
}
