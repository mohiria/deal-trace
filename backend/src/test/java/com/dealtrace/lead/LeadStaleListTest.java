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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「我的长期未跟踪线索」查询（spec ADDED：我的长期未跟踪线索查询）。
 *
 * <p>{@code GET /leads/mine/stale} 返回调用者名下、未结束、且 lastTrackedAt 早于后端阈值
 * （或从未跟踪 NULL）的线索，按 lastTrackedAt 升序（NULL 视为最久在前），施加数量上限；无持久化副作用。
 */
@AutoConfigureMockMvc
class LeadStaleListTest extends IntegrationTest {

    private static final String VALID_USCI = "91110000123456789Q";

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private LeadMapper leadMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private Account salesA;
    private Account salesB;
    private Long customerId;

    @BeforeEach
    void seed() {
        accountMapper.delete(null);
        customerMapper.delete(null);

        salesA = insertAccount("stale-a@dealtrace.test", "SalesA", Role.SALES, AccountStatus.ENABLED);
        salesB = insertAccount("stale-b@dealtrace.test", "SalesB", Role.SALES, AccountStatus.ENABLED);

        Customer c = new Customer();
        c.setName("Stale Customer");
        c.setUsci(VALID_USCI);
        c.setCreatedAt(LocalDateTime.now());
        customerMapper.insert(c);
        customerId = c.getId();
    }

    private Account insertAccount(String email, String name, Role role, AccountStatus status) {
        Account a = new Account();
        a.setEmail(email);
        a.setName(name);
        a.setRole(role);
        a.setStatus(status);
        a.setPasswordHash(passwordEncoder.encode("p@ssw0rd"));
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);
        return a;
    }

    private Lead insertLead(Long owner, String contact, LeadStage stage, LocalDateTime lastTracked) {
        Lead l = new Lead();
        l.setCustomerId(customerId);
        l.setBusinessYear((short) java.time.LocalDate.now().getYear());
        l.setBusinessType(BusinessType.BIM_CONSULTING);
        l.setContactName(contact);
        l.setContactPhone("13800000000");
        l.setOwnerSalesId(owner);
        l.setStage(stage);
        l.setLastTrackedAt(lastTracked);
        l.setCreatedAt(LocalDateTime.now());
        leadMapper.insert(l);
        return l;
    }

    @Test
    void returnsOwnedActiveStaleLeads_orderedByLastTrackedAscNullFirst() throws Exception {
        LocalDateTime old = LocalDateTime.now().minusDays(30);
        LocalDateTime recent = LocalDateTime.now().minusHours(1);
        Lead l1 = insertLead(salesA.getId(), "L1-old", LeadStage.QUOTED, old);       // 命中
        insertLead(salesA.getId(), "L2-recent", LeadStage.QUOTED, recent);           // 阈值内排除
        Lead l3 = insertLead(salesA.getId(), "L3-never", LeadStage.UNTOUCHED, null); // 命中（NULL）
        insertLead(salesA.getId(), "L4-won", LeadStage.WON, old);                    // 已结束排除
        insertLead(salesB.getId(), "B-old", LeadStage.QUOTED, old);                  // 他人名下排除

        String token = jwtService.generateToken(salesA);
        mockMvc.perform(get("/leads/mine/stale").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.length()").value(2))
            // NULL（从未跟踪）视为最久，排在最前
            .andExpect(jsonPath("$.data[0].id").value(l3.getId()))
            .andExpect(jsonPath("$.data[0].contactName").value("L3-never"))
            .andExpect(jsonPath("$.data[1].id").value(l1.getId()))
            .andExpect(jsonPath("$.data[1].contactName").value("L1-old"));
    }

    @Test
    void noPersistenceSideEffect() throws Exception {
        insertLead(salesA.getId(), "L-old", LeadStage.QUOTED, LocalDateTime.now().minusDays(30));
        long leadsBefore = leadMapper.selectCount(null);

        String token = jwtService.generateToken(salesA);
        mockMvc.perform(get("/leads/mine/stale").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk());
        mockMvc.perform(get("/leads/mine/stale").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk());

        assertThat(leadMapper.selectCount(null)).isEqualTo(leadsBefore);
    }
}
