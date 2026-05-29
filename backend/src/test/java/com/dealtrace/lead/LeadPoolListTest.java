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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec ADDED 公海列表：Sales 脱敏 / Admin 明文 / 仅含未结束无归属 / 无副作用。
 */
@AutoConfigureMockMvc
class LeadPoolListTest extends IntegrationTest {

    private static final String VALID_USCI = "91110000123456789Q";
    private static final String POOL_PHONE = "13812345678";

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private LeadMapper leadMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private Account admin;
    private Account salesA;
    private Long customerId;
    private Long poolUntouchedId;

    @BeforeEach
    void seed() {
        leadMapper.delete(null);
        customerMapper.delete(null);
        accountMapper.delete(null);

        admin = insertAccount("pool-admin@dealtrace.test", Role.ADMIN);
        salesA = insertAccount("pool-a@dealtrace.test", Role.SALES);

        Customer c = new Customer();
        c.setName("Pool Customer");
        c.setUsci(VALID_USCI);
        c.setCreatedAt(LocalDateTime.now());
        customerMapper.insert(c);
        customerId = c.getId();

        poolUntouchedId = insertLead(null, LeadStage.UNTOUCHED, BusinessType.BIM_CONSULTING, POOL_PHONE);
        insertLead(salesA.getId(), LeadStage.QUOTED, BusinessType.BIM_TRAINING, "13900000000");
        insertLead(null, LeadStage.LOST, BusinessType.CUSTOM_DEVELOPMENT, "13700000000");
        insertLead(null, LeadStage.WON, BusinessType.CUSTOM_DEVELOPMENT, "13600000000");
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

    private Long insertLead(Long ownerId, LeadStage stage, BusinessType type, String phone) {
        Lead l = new Lead();
        l.setCustomerId(customerId);
        l.setBusinessYear((short) LocalDate.now().getYear());
        l.setBusinessType(type);
        l.setContactName("X");
        l.setContactPhone(phone);
        l.setOwnerSalesId(ownerId);
        l.setStage(stage);
        l.setCreatedAt(LocalDateTime.now());
        leadMapper.insert(l);
        return l.getId();
    }

    @Test
    void sales_seesMaskedPhone() throws Exception {
        String token = jwtService.generateToken(salesA);
        MvcResult result = mockMvc.perform(get("/leads/pool")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(poolUntouchedId))
            .andExpect(jsonPath("$.data[0].contactPhone").value("138****5678"))
            .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(POOL_PHONE);
    }

    @Test
    void admin_seesPlaintextPhone() throws Exception {
        String token = jwtService.generateToken(admin);
        mockMvc.perform(get("/leads/pool")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].contactPhone").value(POOL_PHONE));
    }

    @Test
    void poolOnlyContainsUnendedUnownedLeads() throws Exception {
        String token = jwtService.generateToken(admin);
        mockMvc.perform(get("/leads/pool")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(poolUntouchedId));
    }

    @Test
    void poolQueryHasNoSideEffects() throws Exception {
        long leadsBefore = leadMapper.selectCount(null);
        String token = jwtService.generateToken(salesA);
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/leads/pool")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        }
        assertThat(leadMapper.selectCount(null)).isEqualTo(leadsBefore);
    }
}
