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
 * Spec ADDED 分配：成功 / 停用 Sales 拒 / 已有归属拒 / 已结束 400 / LEAD_ASSIGN 日志含原+新归属。
 */
@AutoConfigureMockMvc
class LeadAssignTest extends IntegrationTest {

    private static final String VALID_USCI = "91110000123456789Q";

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private LeadMapper leadMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Account admin;
    private Account enabledSales;
    private Account disabledSales;
    private Long customerId;
    private Long poolLeadId;
    private Long ownedLeadId;
    private Long endedPoolLeadId;

    @BeforeEach
    void seed() {
        leadMapper.delete(null);
        customerMapper.delete(null);
        accountMapper.delete(null);

        admin = insertAccount("assign-admin@dealtrace.test", Role.ADMIN, AccountStatus.ENABLED);
        enabledSales = insertAccount("assign-enabled@dealtrace.test", Role.SALES, AccountStatus.ENABLED);
        disabledSales = insertAccount("assign-disabled@dealtrace.test", Role.SALES, AccountStatus.DISABLED);

        Customer c = new Customer();
        c.setName("Assign Customer");
        c.setUsci(VALID_USCI);
        c.setCreatedAt(LocalDateTime.now());
        customerMapper.insert(c);
        customerId = c.getId();

        poolLeadId = insertLead(null, LeadStage.UNTOUCHED, BusinessType.BIM_CONSULTING);
        ownedLeadId = insertLead(enabledSales.getId(), LeadStage.QUOTED, BusinessType.BIM_TRAINING);
        endedPoolLeadId = insertLead(null, LeadStage.LOST, BusinessType.CUSTOM_DEVELOPMENT);
    }

    private Account insertAccount(String email, Role role, AccountStatus status) {
        Account a = new Account();
        a.setEmail(email);
        a.setName(email);
        a.setRole(role);
        a.setStatus(status);
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

    private void assign(Long id, Long salesId, String token,
                        org.springframework.test.web.servlet.ResultMatcher... matchers) throws Exception {
        var rb = post("/leads/" + id + "/assign")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"salesId\":" + salesId + "}");
        var actions = mockMvc.perform(rb);
        for (var m : matchers) actions = actions.andExpect(m);
    }

    @Test
    void assign_poolLeadToEnabledSales_succeeds() throws Exception {
        String token = jwtService.generateToken(admin);
        assign(poolLeadId, enabledSales.getId(), token,
            status().isOk(),
            jsonPath("$.code").value("SUCCESS"),
            jsonPath("$.data.ownerSalesId").value(enabledSales.getId()));
        assertThat(leadMapper.selectById(poolLeadId).getOwnerSalesId()).isEqualTo(enabledSales.getId());
    }

    @Test
    void assign_toDisabledSales_rejected() throws Exception {
        String token = jwtService.generateToken(admin);
        assign(poolLeadId, disabledSales.getId(), token,
            status().isBadRequest(),
            jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(leadMapper.selectById(poolLeadId).getOwnerSalesId()).isNull();
    }

    @Test
    void assign_alreadyOwnedLead_rejected() throws Exception {
        String token = jwtService.generateToken(admin);
        // 目标用合法 ENABLED Sales，隔离出"线索已有归属"这一拒绝原因（归属态校验在目标校验之前）
        assign(ownedLeadId, enabledSales.getId(), token,
            status().isBadRequest(),
            jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(leadMapper.selectById(ownedLeadId).getOwnerSalesId()).isEqualTo(enabledSales.getId());
    }

    @Test
    void assign_endedLead_returns400() throws Exception {
        String token = jwtService.generateToken(admin);
        assign(endedPoolLeadId, enabledSales.getId(), token,
            status().isBadRequest(),
            jsonPath("$.code").value("LEAD_ENDED_READONLY"));
    }

    @Test
    void assign_writesLeadAssignSystemLogWithOwners() throws Exception {
        String token = jwtService.generateToken(admin);
        assign(poolLeadId, enabledSales.getId(), token, status().isOk());

        List<Map<String, Object>> logs = jdbcTemplate.queryForList(
            "SELECT action, target_id, operator_id, summary FROM system_log WHERE lead_id = ?",
            poolLeadId);
        assertThat(logs).hasSize(1);
        Map<String, Object> log = logs.get(0);
        assertThat(log.get("action")).isEqualTo("LEAD_ASSIGN");
        assertThat(((Number) log.get("target_id")).longValue()).isEqualTo(poolLeadId);
        assertThat(((Number) log.get("operator_id")).longValue()).isEqualTo(admin.getId());
        assertThat((String) log.get("summary"))
            .contains("公海")
            .contains(enabledSales.getEmail());
    }
}
