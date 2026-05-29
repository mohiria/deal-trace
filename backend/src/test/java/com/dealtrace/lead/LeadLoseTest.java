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
 * Spec ADDED 标记流失（8 Scenario）：成功 / 其他缺说明 400 / 其他带说明成功 / 非法枚举 400 /
 * 非自己名下 404 / Admin 公海成功 / 已结束只读 / LEAD_LOSE 日志含原因+说明。
 */
@AutoConfigureMockMvc
class LeadLoseTest extends IntegrationTest {

    private static final String VALID_USCI = "91110000123456789Q";

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private LeadMapper leadMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Account salesA;
    private Account admin;
    private Long customerId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM contract");
        leadMapper.delete(null);
        customerMapper.delete(null);
        accountMapper.delete(null);

        salesA = insertAccount("lose-a@dealtrace.test", Role.SALES);
        admin = insertAccount("lose-admin@dealtrace.test", Role.ADMIN);

        Customer c = new Customer();
        c.setName("Lose Customer");
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

    private void lose(Long id, String body, String token, ResultMatcher... matchers) throws Exception {
        var rb = post("/leads/" + id + "/lose")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
        var actions = mockMvc.perform(rb);
        for (ResultMatcher m : matchers) actions = actions.andExpect(m);
    }

    // ---- 4.1 / S1：SALES 流失自己单成功 ----
    @Test
    void sales_losesOwnLead_succeeds() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.QUOTED);
        lose(id, "{\"loseReason\":\"价格过高\"}", jwtService.generateToken(salesA),
            status().isOk(), jsonPath("$.code").value("SUCCESS"));

        Lead reloaded = leadMapper.selectById(id);
        assertThat(reloaded.getStage()).isEqualTo(LeadStage.LOST);
        assertThat(reloaded.getLostAt()).isNotNull();
        assertThat(reloaded.getLoseReason()).isEqualTo("价格过高");
    }

    // ---- 4.2 / S2：原因=其他缺说明 → 400 ----
    @Test
    void loseOtherWithoutNote_returnsValidationError() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.CONTACTED);
        lose(id, "{\"loseReason\":\"其他\"}", jwtService.generateToken(salesA),
            status().isBadRequest(), jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(leadMapper.selectById(id).getStage()).isEqualTo(LeadStage.CONTACTED);

        Long id2 = insertLead(salesA.getId(), LeadStage.CONTACTED);
        lose(id2, "{\"loseReason\":\"其他\",\"loseNote\":\"   \"}", jwtService.generateToken(salesA),
            status().isBadRequest(), jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ---- 4.2 / S3：原因=其他带说明成功 ----
    @Test
    void loseOtherWithNote_succeeds() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.CONTACTED);
        lose(id, "{\"loseReason\":\"其他\",\"loseNote\":\"客户内部重组\"}", jwtService.generateToken(salesA),
            status().isOk(), jsonPath("$.code").value("SUCCESS"));
        Lead reloaded = leadMapper.selectById(id);
        assertThat(reloaded.getStage()).isEqualTo(LeadStage.LOST);
        assertThat(reloaded.getLoseReason()).isEqualTo("其他");
        assertThat(reloaded.getLoseNote()).isEqualTo("客户内部重组");
    }

    // ---- 4.3 / S4：非法枚举 → 400 ----
    @Test
    void loseInvalidReason_returnsValidationError() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.QUOTED);
        lose(id, "{\"loseReason\":\"foo\"}", jwtService.generateToken(salesA),
            status().isBadRequest(), jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(leadMapper.selectById(id).getStage()).isEqualTo(LeadStage.QUOTED);
    }

    // ---- 4.3 / S5：SALES 流失他人 → 404 ----
    @Test
    void sales_losesOtherSalesLead_returns404() throws Exception {
        Account salesB = insertAccount("lose-b@dealtrace.test", Role.SALES);
        Long id = insertLead(salesB.getId(), LeadStage.QUOTED);
        lose(id, "{\"loseReason\":\"价格过高\"}", jwtService.generateToken(salesA),
            status().isNotFound(), jsonPath("$.code").value("NOT_FOUND"));
        assertThat(leadMapper.selectById(id).getStage()).isEqualTo(LeadStage.QUOTED);
    }

    // ---- 4.3 / S6：ADMIN 流失公海单成功 ----
    @Test
    void admin_losesPoolLead_succeeds() throws Exception {
        Long id = insertLead(null, LeadStage.UNTOUCHED);
        lose(id, "{\"loseReason\":\"联系不上\"}", jwtService.generateToken(admin),
            status().isOk(), jsonPath("$.code").value("SUCCESS"));
        assertThat(leadMapper.selectById(id).getStage()).isEqualTo(LeadStage.LOST);
    }

    // ---- 4.3 / S7：对已结束线索流失 → 只读 ----
    @Test
    void loseEndedLead_returnsReadonly() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.WON);
        lose(id, "{\"loseReason\":\"价格过高\"}", jwtService.generateToken(salesA),
            status().isBadRequest(), jsonPath("$.code").value("LEAD_ENDED_READONLY"));
        assertThat(leadMapper.selectById(id).getStage()).isEqualTo(LeadStage.WON);
    }

    // ---- 4.4 / S8：LEAD_LOSE 日志含原因+说明 ----
    @Test
    void lose_writesLeadLoseSystemLog_withReasonAndNote() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.QUOTED);
        lose(id, "{\"loseReason\":\"其他\",\"loseNote\":\"客户内部重组\"}",
            jwtService.generateToken(salesA), status().isOk());

        List<Map<String, Object>> logs = jdbcTemplate.queryForList(
            "SELECT action, target_type, target_id, operator_id, lead_id, summary "
                + "FROM system_log WHERE lead_id = ? AND action = 'LEAD_LOSE'", id);
        assertThat(logs).hasSize(1);
        Map<String, Object> log = logs.get(0);
        assertThat(log.get("target_type")).isEqualTo("LEAD");
        assertThat(((Number) log.get("target_id")).longValue()).isEqualTo(id);
        assertThat(((Number) log.get("operator_id")).longValue()).isEqualTo(salesA.getId());
        assertThat(((Number) log.get("lead_id")).longValue()).isEqualTo(id);
        assertThat((String) log.get("summary")).contains("其他").contains("客户内部重组");
    }
}
