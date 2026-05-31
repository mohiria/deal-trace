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

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec R7：详情 + 列表权限隔离。
 */
@AutoConfigureMockMvc
class LeadControllerDetailListTest extends IntegrationTest {

    private static final String VALID_USCI = "91110000123456789Q";

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private LeadMapper leadMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private Account admin;
    private Account salesA;
    private Account salesB;
    private Long customerId;
    private Long leadOfAId;
    private Long leadOfBId;
    private Long leadInPoolId;

    @BeforeEach
    void seed() {
        accountMapper.delete(null);
        customerMapper.delete(null);

        admin = insertAccount("detail-admin@dealtrace.test", "Admin", Role.ADMIN);
        salesA = insertAccount("detail-a@dealtrace.test", "Sales A", Role.SALES);
        salesB = insertAccount("detail-b@dealtrace.test", "Sales B", Role.SALES);

        Customer c = new Customer();
        c.setName("Detail Test Customer");
        c.setUsci(VALID_USCI);
        c.setCreatedAt(LocalDateTime.now());
        customerMapper.insert(c);
        customerId = c.getId();

        leadOfAId = insertLead(salesA.getId(), BusinessType.BIM_CONSULTING);
        leadOfBId = insertLead(salesB.getId(), BusinessType.BIM_TRAINING);
        leadInPoolId = insertLead(null, BusinessType.CUSTOM_DEVELOPMENT);
    }

    private Account insertAccount(String email, String name, Role role) {
        Account a = new Account();
        a.setEmail(email);
        a.setName(name);
        a.setRole(role);
        a.setStatus(AccountStatus.ENABLED);
        a.setPasswordHash(passwordEncoder.encode("p@ssw0rd"));
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);
        return a;
    }

    private Long insertLead(Long ownerId, BusinessType type) {
        Lead l = new Lead();
        l.setCustomerId(customerId);
        l.setBusinessYear((short) LocalDate.now().getYear());
        l.setBusinessType(type);
        l.setContactName("X");
        l.setContactPhone("13800000000");
        l.setOwnerSalesId(ownerId);
        l.setStage(LeadStage.UNTOUCHED);
        l.setCreatedAt(LocalDateTime.now());
        leadMapper.insert(l);
        return l.getId();
    }

    @Test
    void admin_canViewAnyLead() throws Exception {
        String token = jwtService.generateToken(admin);
        mockMvc.perform(get("/leads/" + leadOfAId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.id").value(leadOfAId))
            .andExpect(jsonPath("$.data.customerName").value("Detail Test Customer"))
            .andExpect(jsonPath("$.data.customerUsci").value(VALID_USCI));
    }

    @Test
    void ownedLeadDetail_includesOwnerSalesName() throws Exception {
        String token = jwtService.generateToken(admin);
        mockMvc.perform(get("/leads/" + leadOfAId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ownerSalesId").value(salesA.getId()))
            .andExpect(jsonPath("$.data.ownerSalesName").value("Sales A"));
    }

    @Test
    void poolLeadDetail_ownerSalesNameIsNull() throws Exception {
        String token = jwtService.generateToken(admin);
        mockMvc.perform(get("/leads/" + leadInPoolId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ownerSalesId").value(nullValue()))
            .andExpect(jsonPath("$.data.ownerSalesName").value(nullValue()));
    }

    @Test
    void mineList_includesOwnerSalesName() throws Exception {
        String token = jwtService.generateToken(salesA);
        mockMvc.perform(get("/leads/mine")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].ownerSalesName").value("Sales A"));
    }

    @Test
    void sales_canViewOwnLead() throws Exception {
        String token = jwtService.generateToken(salesA);
        mockMvc.perform(get("/leads/" + leadOfAId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(leadOfAId));
    }

    @Test
    void sales_viewingOtherSalesLead_returns404() throws Exception {
        String token = jwtService.generateToken(salesA);
        mockMvc.perform(get("/leads/" + leadOfBId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("线索不存在"));
    }

    @Test
    void sales_viewingPoolLead_returns404() throws Exception {
        String token = jwtService.generateToken(salesA);
        mockMvc.perform(get("/leads/" + leadInPoolId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void nonExistentLead_returns404() throws Exception {
        String token = jwtService.generateToken(salesA);
        mockMvc.perform(get("/leads/999999")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("线索不存在"));
    }

    @Test
    void mine_returnsOnlyOwnLeads() throws Exception {
        String token = jwtService.generateToken(salesA);
        mockMvc.perform(get("/leads/mine")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(leadOfAId));
    }

    @Test
    void admin_listsAll_includingPool() throws Exception {
        String token = jwtService.generateToken(admin);
        mockMvc.perform(get("/leads")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    void sales_listsAll_rejectedWith403() throws Exception {
        String token = jwtService.generateToken(salesA);
        mockMvc.perform(get("/leads")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
