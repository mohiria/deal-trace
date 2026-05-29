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
 * Spec ADDED 转移：成功 / 停用拒 / 公海拒 / 同人拒 / 已结束 400 / LEAD_TRANSFER 日志含原+新归属。
 */
@AutoConfigureMockMvc
class LeadTransferTest extends IntegrationTest {

    private static final String VALID_USCI = "91110000123456789Q";

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private LeadMapper leadMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Account admin;
    private Account ownerSales;
    private Account targetSales;
    private Account disabledSales;
    private Long customerId;
    private Long ownedLeadId;
    private Long poolLeadId;
    private Long endedOwnedLeadId;

    @BeforeEach
    void seed() {
        leadMapper.delete(null);
        customerMapper.delete(null);
        accountMapper.delete(null);

        admin = insertAccount("transfer-admin@dealtrace.test", Role.ADMIN, AccountStatus.ENABLED);
        ownerSales = insertAccount("transfer-owner@dealtrace.test", Role.SALES, AccountStatus.ENABLED);
        targetSales = insertAccount("transfer-target@dealtrace.test", Role.SALES, AccountStatus.ENABLED);
        disabledSales = insertAccount("transfer-disabled@dealtrace.test", Role.SALES, AccountStatus.DISABLED);

        Customer c = new Customer();
        c.setName("Transfer Customer");
        c.setUsci(VALID_USCI);
        c.setCreatedAt(LocalDateTime.now());
        customerMapper.insert(c);
        customerId = c.getId();

        ownedLeadId = insertLead(ownerSales.getId(), LeadStage.NEGOTIATING, BusinessType.BIM_CONSULTING);
        poolLeadId = insertLead(null, LeadStage.UNTOUCHED, BusinessType.BIM_TRAINING);
        endedOwnedLeadId = insertLead(ownerSales.getId(), LeadStage.LOST, BusinessType.CUSTOM_DEVELOPMENT);
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

    private void transfer(Long id, Long salesId, String token,
                          org.springframework.test.web.servlet.ResultMatcher... matchers) throws Exception {
        var rb = post("/leads/" + id + "/transfer")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"salesId\":" + salesId + "}");
        var actions = mockMvc.perform(rb);
        for (var m : matchers) actions = actions.andExpect(m);
    }

    @Test
    void transfer_toAnotherEnabledSales_succeeds() throws Exception {
        String token = jwtService.generateToken(admin);
        transfer(ownedLeadId, targetSales.getId(), token,
            status().isOk(),
            jsonPath("$.code").value("SUCCESS"),
            jsonPath("$.data.ownerSalesId").value(targetSales.getId()),
            jsonPath("$.data.stage").value("商务谈判"));
        assertThat(leadMapper.selectById(ownedLeadId).getOwnerSalesId()).isEqualTo(targetSales.getId());
    }

    @Test
    void transfer_toDisabledSales_rejected() throws Exception {
        String token = jwtService.generateToken(admin);
        transfer(ownedLeadId, disabledSales.getId(), token,
            status().isBadRequest(),
            jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(leadMapper.selectById(ownedLeadId).getOwnerSalesId()).isEqualTo(ownerSales.getId());
    }

    @Test
    void transfer_poolLead_rejected() throws Exception {
        String token = jwtService.generateToken(admin);
        transfer(poolLeadId, targetSales.getId(), token,
            status().isBadRequest(),
            jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void transfer_toCurrentOwner_rejected() throws Exception {
        String token = jwtService.generateToken(admin);
        transfer(ownedLeadId, ownerSales.getId(), token,
            status().isBadRequest(),
            jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(leadMapper.selectById(ownedLeadId).getOwnerSalesId()).isEqualTo(ownerSales.getId());
    }

    @Test
    void transfer_endedLead_returns400() throws Exception {
        String token = jwtService.generateToken(admin);
        transfer(endedOwnedLeadId, targetSales.getId(), token,
            status().isBadRequest(),
            jsonPath("$.code").value("LEAD_ENDED_READONLY"));
    }

    @Test
    void transfer_writesLeadTransferSystemLogWithOwners() throws Exception {
        String token = jwtService.generateToken(admin);
        transfer(ownedLeadId, targetSales.getId(), token, status().isOk());

        List<Map<String, Object>> logs = jdbcTemplate.queryForList(
            "SELECT action, target_id, operator_id, summary FROM system_log WHERE lead_id = ?",
            ownedLeadId);
        assertThat(logs).hasSize(1);
        Map<String, Object> log = logs.get(0);
        assertThat(log.get("action")).isEqualTo("LEAD_TRANSFER");
        assertThat(((Number) log.get("target_id")).longValue()).isEqualTo(ownedLeadId);
        assertThat(((Number) log.get("operator_id")).longValue()).isEqualTo(admin.getId());
        assertThat((String) log.get("summary"))
            .contains(ownerSales.getEmail())
            .contains(targetSales.getEmail());
    }
}
