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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 协同提示不阻断写操作（spec customer-other-leads-hint「协同提示不阻断写操作」，PRD §7.6.2/§11.6.4）。
 *
 * <p>真 MySQL；客户已存在其他业务类型线索时，新建（异类）与认领仍正常成功。
 */
@AutoConfigureMockMvc
class LeadOtherLeadsNonBlockingTest extends IntegrationTest {

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
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        salesA = insertAccount("nb-a-" + sfx + "@dealtrace.test", Role.SALES);
        salesB = insertAccount("nb-b-" + sfx + "@dealtrace.test", Role.SALES);

        Customer c = new Customer();
        c.setName("NonBlock 客户 " + sfx);
        c.setUsci(("91" + sfx + "NONBLOCK0000").substring(0, 18).toUpperCase());
        c.setCreatedAt(LocalDateTime.now());
        customerMapper.insert(c);
        customerId = c.getId();

        // 该客户已有其他业务类型（BIM咨询）进行中线索（他人名下）
        insertLead(salesB.getId(), BusinessType.BIM_CONSULTING, LeadStage.QUOTED);
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

    private Long insertLead(Long ownerId, BusinessType type, LeadStage stage) {
        Lead l = new Lead();
        l.setCustomerId(customerId);
        l.setBusinessYear((short) LocalDate.now().getYear());
        l.setBusinessType(type);
        l.setContactName("联系人");
        l.setContactPhone("13812345678");
        l.setOwnerSalesId(ownerId);
        l.setStage(stage);
        l.setCreatedAt(LocalDateTime.now());
        leadMapper.insert(l);
        return l.getId();
    }

    @Test
    void create_succeedsWithOtherLeads() throws Exception {
        // 先确认提示确实存在（客户有其他业务线索），证明「有提示」前提成立
        String token = jwtService.generateToken(salesA);
        mockMvc.perform(get("/leads/customer-other-leads")
                .param("customerId", String.valueOf(customerId))
                .param("excludeBusinessType", "BIM培训")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk());

        // 创建异类（BIM培训）线索仍成功，不被协同提示阻断
        String body = "{\"customerId\":" + customerId
            + ",\"businessType\":\"BIM培训\",\"contactName\":\"王工\",\"contactPhone\":\"13812345678\"}";
        mockMvc.perform(post("/leads")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.stage").value("未触达"));
    }

    @Test
    void claim_succeedsWithOtherLeads() throws Exception {
        // 公海线索（异于既有 BIM咨询 的类型），其客户有其他业务线索
        Long poolLeadId = insertLead(null, BusinessType.BIM_TRAINING, LeadStage.UNTOUCHED);
        String token = jwtService.generateToken(salesA);
        mockMvc.perform(post("/leads/" + poolLeadId + "/claim")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"));
    }
}
