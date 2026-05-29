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
 * Spec R6：查重预检端点。
 */
@AutoConfigureMockMvc
class LeadDuplicateCheckTest extends IntegrationTest {

    private static final String VALID_USCI = "91110000123456789Q";

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private LeadMapper leadMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String token;
    private Long customerId;

    @BeforeEach
    void seed() {
        accountMapper.delete(null);
        customerMapper.delete(null);

        Account sales = new Account();
        sales.setEmail("dup-check-sales@dealtrace.test");
        sales.setName("S");
        sales.setRole(Role.SALES);
        sales.setStatus(AccountStatus.ENABLED);
        sales.setPasswordHash(passwordEncoder.encode("p@ssw0rd"));
        LocalDateTime now = LocalDateTime.now();
        sales.setCreatedAt(now);
        sales.setUpdatedAt(now);
        accountMapper.insert(sales);
        token = jwtService.generateToken(sales);

        Customer c = new Customer();
        c.setName("Dup Check Customer");
        c.setUsci(VALID_USCI);
        c.setCreatedAt(now);
        customerMapper.insert(c);
        customerId = c.getId();
    }

    private void insertLead(BusinessType type, LeadStage stage, String loseReason) {
        Lead l = new Lead();
        l.setCustomerId(customerId);
        l.setBusinessYear((short) LocalDate.now().getYear());
        l.setBusinessType(type);
        l.setContactName("X");
        l.setContactPhone("13800000000");
        l.setStage(stage);
        l.setCreatedAt(LocalDateTime.now());
        if (stage == LeadStage.LOST) {
            l.setLostAt(LocalDateTime.now());
            l.setLoseReason(loseReason);
        }
        leadMapper.insert(l);
    }

    @Test
    void emptyTriplet_canCreateTrue() throws Exception {
        mockMvc.perform(get("/leads/duplicate-check")
                .param("customerId", String.valueOf(customerId))
                .param("businessType", "BIM咨询")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.canCreate").value(true))
            .andExpect(jsonPath("$.data.blockingReason").doesNotExist())
            .andExpect(jsonPath("$.data.historicalLost.length()").value(0));
    }

    @Test
    void activeBlocks_withActiveCode() throws Exception {
        insertLead(BusinessType.BIM_CONSULTING, LeadStage.QUOTED, null);
        mockMvc.perform(get("/leads/duplicate-check")
                .param("customerId", String.valueOf(customerId))
                .param("businessType", "BIM咨询")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.canCreate").value(false))
            .andExpect(jsonPath("$.data.blockingReason").value("DUPLICATE_ACTIVE_LEAD"));
    }

    @Test
    void onlyLost_canCreateTrueWithHistory() throws Exception {
        insertLead(BusinessType.BIM_CONSULTING, LeadStage.LOST, "价格过高");
        insertLead(BusinessType.BIM_CONSULTING, LeadStage.LOST, "选择竞品");
        mockMvc.perform(get("/leads/duplicate-check")
                .param("customerId", String.valueOf(customerId))
                .param("businessType", "BIM咨询")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.canCreate").value(true))
            .andExpect(jsonPath("$.data.blockingReason").doesNotExist())
            .andExpect(jsonPath("$.data.historicalLost.length()").value(2));
    }

    @Test
    void doesNotWritePersistedData() throws Exception {
        Long leadCountBefore = leadMapper.selectCount(null);
        Long syslogCountBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM system_log", Long.class);

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/leads/duplicate-check")
                    .param("customerId", String.valueOf(customerId))
                    .param("businessType", "BIM咨询")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        }

        assertThat(leadMapper.selectCount(null)).isEqualTo(leadCountBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM system_log", Long.class))
            .isEqualTo(syslogCountBefore);
    }
}
