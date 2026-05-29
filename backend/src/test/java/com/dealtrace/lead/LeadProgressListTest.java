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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * progress-log spec ADDED R9：进度列表读取与可见性（ADMIN 任意 / SALES 自己名下含已结束 / 他人公海 404 /
 * 按 track_time 倒序 / 无副作用）。
 */
@AutoConfigureMockMvc
class LeadProgressListTest extends IntegrationTest {

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
        jdbcTemplate.update("DELETE FROM progress_log");
        leadMapper.delete(null);
        customerMapper.delete(null);
        accountMapper.delete(null);

        salesA = insertAccount("plist-a@dealtrace.test", Role.SALES);
        salesB = insertAccount("plist-b@dealtrace.test", Role.SALES);
        admin = insertAccount("plist-admin@dealtrace.test", Role.ADMIN);

        Customer c = new Customer();
        c.setName("List Customer");
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

    private void insertProgress(Long leadId, String method, String content, Long trackerId, LocalDateTime trackTime) {
        jdbcTemplate.update(
            "INSERT INTO progress_log (lead_id, method, content, tracker_id, track_time) VALUES (?, ?, ?, ?, ?)",
            leadId, method, content, trackerId, trackTime);
    }

    // ---- 4.1：ADMIN 读任意线索（他人名下）进度，按 track_time 倒序 ----
    @Test
    void admin_readsAnyLead_descByTrackTime() throws Exception {
        Long id = insertLead(salesB.getId(), LeadStage.QUOTED);
        insertProgress(id, "电话", "c1", salesB.getId(), LocalDateTime.of(2026, 1, 1, 9, 0));
        insertProgress(id, "微信", "c2", salesB.getId(), LocalDateTime.of(2026, 1, 2, 9, 0));
        insertProgress(id, "拜访", "c3", salesB.getId(), LocalDateTime.of(2026, 1, 3, 9, 0));

        mockMvc.perform(get("/leads/" + id + "/progress")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateToken(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[0].content").value("c3"))
            .andExpect(jsonPath("$.data[1].content").value("c2"))
            .andExpect(jsonPath("$.data[2].content").value("c1"));
    }

    // ---- 4.2：SALES 读自己名下进度成功（含已结束线索仍可读）----
    @Test
    void sales_readsOwnActiveLead_succeeds() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.CONTACTED);
        insertProgress(id, "电话", "own", salesA.getId(), LocalDateTime.of(2026, 1, 1, 9, 0));

        mockMvc.perform(get("/leads/" + id + "/progress")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateToken(salesA)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].content").value("own"));
    }

    @Test
    void sales_readsOwnEndedLead_succeeds() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.WON);
        insertProgress(id, "电话", "ended", salesA.getId(), LocalDateTime.of(2026, 1, 1, 9, 0));

        mockMvc.perform(get("/leads/" + id + "/progress")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateToken(salesA)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1));
    }

    // ---- 4.3：SALES 读他人 / 公海 → 404 ----
    @Test
    void sales_readsOtherSalesLead_returns404() throws Exception {
        Long id = insertLead(salesB.getId(), LeadStage.CONTACTED);
        mockMvc.perform(get("/leads/" + id + "/progress")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateToken(salesA)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void sales_readsPoolLead_returns404() throws Exception {
        Long id = insertLead(null, LeadStage.UNTOUCHED);
        mockMvc.perform(get("/leads/" + id + "/progress")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateToken(salesA)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---- 4.4：读端点无副作用、不写 system_log ----
    @Test
    void read_hasNoSideEffects() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.CONTACTED);
        insertProgress(id, "电话", "c", salesA.getId(), LocalDateTime.of(2026, 1, 1, 9, 0));
        Long progBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM progress_log WHERE lead_id = ?", Long.class, id);
        Long logBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM system_log WHERE lead_id = ?", Long.class, id);

        mockMvc.perform(get("/leads/" + id + "/progress")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateToken(salesA)))
            .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM progress_log WHERE lead_id = ?", Long.class, id)).isEqualTo(progBefore);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM system_log WHERE lead_id = ?", Long.class, id)).isEqualTo(logBefore);
    }
}
