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
import org.springframework.test.web.servlet.ResultMatcher;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec ADDED 标记赢单（9 Scenario）+ contract spec（赢单原子生成合同 / 成交销售取值 / 公海 NULL / 事务失败不残留）。
 */
@AutoConfigureMockMvc
class LeadWinTest extends IntegrationTest {

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
    private Account admin;
    private Long customerId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM contract");
        leadMapper.delete(null);
        customerMapper.delete(null);
        accountMapper.delete(null);

        salesA = insertAccount("win-a@dealtrace.test", Role.SALES);
        salesB = insertAccount("win-b@dealtrace.test", Role.SALES);
        admin = insertAccount("win-admin@dealtrace.test", Role.ADMIN);

        Customer c = new Customer();
        c.setName("Win Customer");
        c.setUsci(VALID_USCI);
        c.setCreatedAt(LocalDateTime.now());
        customerMapper.insert(c);
        customerId = c.getId();
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

    private Long insertLead(Long ownerId, LeadStage stage) {
        Lead l = new Lead();
        l.setCustomerId(customerId);
        l.setBusinessYear((short) LocalDate.now().getYear());
        l.setBusinessType(BusinessType.BIM_CONSULTING);
        l.setContactName("X");
        l.setContactPhone("13800000000");
        l.setOwnerSalesId(ownerId);
        l.setStage(stage);
        l.setCreatedAt(LocalDateTime.now());
        leadMapper.insert(l);
        return l.getId();
    }

    private Long insertWonLeadWithContract(Long ownerId) {
        Long id = insertLead(ownerId, LeadStage.WON);
        leadMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Lead>()
            .eq("id", id).set("won_at", LocalDateTime.now()));
        jdbcTemplate.update(
            "INSERT INTO contract (lead_id, contract_amount, signed_date, deal_sales_id, created_at) "
                + "VALUES (?, ?, ?, ?, ?)",
            id, new java.math.BigDecimal("100.00"), LocalDate.of(2026, 1, 1), ownerId, LocalDateTime.now());
        return id;
    }

    private void win(Long id, String body, String token, ResultMatcher... matchers) throws Exception {
        var rb = post("/leads/" + id + "/win")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
        var actions = mockMvc.perform(rb);
        for (ResultMatcher m : matchers) actions = actions.andExpect(m);
    }

    private long contractCount(Long leadId) {
        Long n = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM contract WHERE lead_id = ?", Long.class, leadId);
        return n == null ? 0 : n;
    }

    // ---- 3.1 / S1：SALES 赢自己单成功 ----
    @Test
    void sales_winsOwnLead_succeeds_contractGenerated() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.NEGOTIATING);
        win(id, "{\"contractAmount\":\"120000.00\",\"signedDate\":\"2026-05-20\"}",
            jwtService.generateToken(salesA),
            status().isOk(), jsonPath("$.code").value("SUCCESS"));

        Lead reloaded = leadMapper.selectById(id);
        assertThat(reloaded.getStage()).isEqualTo(LeadStage.WON);
        assertThat(reloaded.getWonAt()).isNotNull();
        assertThat(contractCount(id)).isEqualTo(1);
    }

    // ---- 3.2 / S2+S3：ADMIN 赢他人名下成功，成交销售=owner ----
    @Test
    void admin_winsOtherSalesLead_dealSalesIsOwner() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.QUOTED);
        win(id, "{\"contractAmount\":\"5000.50\",\"signedDate\":\"2026-05-20\"}",
            jwtService.generateToken(admin), status().isOk());

        Map<String, Object> contract = jdbcTemplate.queryForMap(
            "SELECT deal_sales_id FROM contract WHERE lead_id = ?", id);
        assertThat(((Number) contract.get("deal_sales_id")).longValue()).isEqualTo(salesA.getId());
        assertThat(leadMapper.selectById(id).getStage()).isEqualTo(LeadStage.WON);
    }

    // ---- 3.2 / S4：ADMIN 赢公海单成功，成交销售 NULL ----
    @Test
    void admin_winsPoolLead_dealSalesIsNull() throws Exception {
        Long id = insertLead(null, LeadStage.UNTOUCHED);
        win(id, "{\"contractAmount\":\"999.99\",\"signedDate\":\"2026-05-20\"}",
            jwtService.generateToken(admin), status().isOk());

        Map<String, Object> contract = jdbcTemplate.queryForMap(
            "SELECT deal_sales_id FROM contract WHERE lead_id = ?", id);
        assertThat(contract.get("deal_sales_id")).isNull();
    }

    // ---- 3.4 / S?：SALES 赢他人/公海 → 404 ----
    @Test
    void sales_winsOtherSalesLead_returns404_noContract() throws Exception {
        Long id = insertLead(salesB.getId(), LeadStage.QUOTED);
        win(id, "{\"contractAmount\":\"100.00\",\"signedDate\":\"2026-05-20\"}",
            jwtService.generateToken(salesA),
            status().isNotFound(), jsonPath("$.code").value("NOT_FOUND"));
        assertThat(leadMapper.selectById(id).getStage()).isEqualTo(LeadStage.QUOTED);
        assertThat(contractCount(id)).isEqualTo(0);
    }

    // ---- 3.4 / S5：赢已结束 → LEAD_ENDED_READONLY ----
    @Test
    void winEndedLead_returnsReadonly() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.LOST);
        win(id, "{\"contractAmount\":\"100.00\",\"signedDate\":\"2026-05-20\"}",
            jwtService.generateToken(salesA),
            status().isBadRequest(), jsonPath("$.code").value("LEAD_ENDED_READONLY"));
    }

    // ---- 3.4 / S6：二次赢单 → 只读，合同仍 1 ----
    @Test
    void winAlreadyWonLead_returnsReadonly_contractStillOne() throws Exception {
        Long id = insertWonLeadWithContract(salesA.getId());
        win(id, "{\"contractAmount\":\"200.00\",\"signedDate\":\"2026-05-21\"}",
            jwtService.generateToken(salesA),
            status().isBadRequest(), jsonPath("$.code").value("LEAD_ENDED_READONLY"));
        assertThat(contractCount(id)).isEqualTo(1);
    }

    // ---- 3.5 / S7：金额非法 → VALIDATION_ERROR ----
    @Test
    void winInvalidAmount_returnsValidationError_noContract() throws Exception {
        for (String amt : new String[]{"0", "-5", "100.123"}) {
            Long id = insertLead(salesA.getId(), LeadStage.NEGOTIATING);
            win(id, "{\"contractAmount\":\"" + amt + "\",\"signedDate\":\"2026-05-20\"}",
                jwtService.generateToken(salesA),
                status().isBadRequest(), jsonPath("$.code").value("VALIDATION_ERROR"));
            assertThat(leadMapper.selectById(id).getStage()).isEqualTo(LeadStage.NEGOTIATING);
            assertThat(contractCount(id)).isEqualTo(0);
        }
    }

    @Test
    void winMissingAmount_returnsValidationError() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.NEGOTIATING);
        win(id, "{\"signedDate\":\"2026-05-20\"}", jwtService.generateToken(salesA),
            status().isBadRequest(), jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(contractCount(id)).isEqualTo(0);
    }

    // ---- 3.5 / S8：签订日非法/缺失 → VALIDATION_ERROR ----
    @Test
    void winInvalidOrMissingSignedDate_returnsValidationError() throws Exception {
        Long id1 = insertLead(salesA.getId(), LeadStage.NEGOTIATING);
        win(id1, "{\"contractAmount\":\"100.00\",\"signedDate\":\"2026-13-40\"}",
            jwtService.generateToken(salesA),
            status().isBadRequest(), jsonPath("$.code").value("VALIDATION_ERROR"));

        Long id2 = insertLead(salesA.getId(), LeadStage.NEGOTIATING);
        win(id2, "{\"contractAmount\":\"100.00\"}", jwtService.generateToken(salesA),
            status().isBadRequest(), jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ---- 3.6 / S9：LEAD_WIN 日志含金额+签订日 ----
    @Test
    void win_writesLeadWinSystemLog_withAmountAndSignedDate() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.NEGOTIATING);
        win(id, "{\"contractAmount\":\"88888.88\",\"signedDate\":\"2026-05-20\"}",
            jwtService.generateToken(salesA), status().isOk());

        List<Map<String, Object>> logs = jdbcTemplate.queryForList(
            "SELECT action, target_type, target_id, operator_id, lead_id, summary "
                + "FROM system_log WHERE lead_id = ? AND action = 'LEAD_WIN'", id);
        assertThat(logs).hasSize(1);
        Map<String, Object> log = logs.get(0);
        assertThat(log.get("target_type")).isEqualTo("LEAD");
        assertThat(((Number) log.get("target_id")).longValue()).isEqualTo(id);
        assertThat(((Number) log.get("operator_id")).longValue()).isEqualTo(salesA.getId());
        assertThat(((Number) log.get("lead_id")).longValue()).isEqualTo(id);
        assertThat((String) log.get("summary")).contains("88888.88").contains("2026-05-20");
    }
}
