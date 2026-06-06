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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 线索列表（mine / all）服务端分页 + keyword 全表下推（lead spec「线索详情与列表的权限隔离」MODIFIED）。
 * 响应 {@code data} 为分页信封；keyword 对全量（含 join customer 名/USCI、联系人）匹配后分页。
 */
@AutoConfigureMockMvc
class LeadListPaginationTest extends IntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private LeadMapper leadMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private Account admin;
    private Account salesA;

    @BeforeEach
    void seed() {
        leadMapper.delete(null);
        accountMapper.delete(null);
        customerMapper.delete(null);
        admin = insertAccount("page-admin@dealtrace.test", "Admin", Role.ADMIN);
        salesA = insertAccount("page-a@dealtrace.test", "Sales A", Role.SALES);
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

    private Long insertCustomer(String name, String usci) {
        Customer c = new Customer();
        c.setName(name);
        c.setUsci(usci);
        c.setCreatedAt(LocalDateTime.now());
        customerMapper.insert(c);
        return c.getId();
    }

    private void insertLead(Long ownerId, Long customerId, String contactName, LocalDateTime createdAt) {
        Lead l = new Lead();
        l.setCustomerId(customerId);
        l.setBusinessYear((short) LocalDate.now().getYear());
        l.setBusinessType(BusinessType.BIM_CONSULTING);
        l.setContactName(contactName);
        l.setContactPhone("13800000000");
        l.setOwnerSalesId(ownerId);
        l.setStage(LeadStage.UNTOUCHED);
        l.setCreatedAt(createdAt);
        leadMapper.insert(l);
    }

    private String usci(int seq) {
        return String.format("%018d", seq);
    }

    @Test
    void mine_paginated_page2() throws Exception {
        Long cust = insertCustomer("普通客户", usci(1));
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        for (int i = 1; i <= 25; i++) {
            insertLead(salesA.getId(), cust, "联系人" + i, base.plusMinutes(i));
        }
        String token = jwtService.generateToken(salesA);
        mockMvc.perform(get("/leads/mine").param("page", "2").param("size", "20")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(5))
            .andExpect(jsonPath("$.data.total").value(25))
            .andExpect(jsonPath("$.data.page").value(2))
            .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    void mine_keywordMatchesCustomerName_acrossWholeSet() throws Exception {
        // 唯一关联「星河」客户的线索 created_at 最早，无 keyword 时落在首页之外
        Long special = insertCustomer("星河设计院", usci(1));
        Long plain = insertCustomer("普通客户", usci(2));
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        insertLead(salesA.getId(), special, "甲", base.plusMinutes(1));
        for (int i = 2; i <= 30; i++) {
            insertLead(salesA.getId(), plain, "联系人" + i, base.plusMinutes(i));
        }
        String token = jwtService.generateToken(salesA);
        mockMvc.perform(get("/leads/mine").param("keyword", "星河").param("page", "1").param("size", "20")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].customerName").value("星河设计院"));
    }

    @Test
    void mine_keywordMatchesContactName() throws Exception {
        Long cust = insertCustomer("普通客户", usci(1));
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        insertLead(salesA.getId(), cust, "独特联系人赵六", base.plusMinutes(1));
        insertLead(salesA.getId(), cust, "其他人", base.plusMinutes(2));
        String token = jwtService.generateToken(salesA);
        mockMvc.perform(get("/leads/mine").param("keyword", "赵六")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].contactName").value("独特联系人赵六"));
    }

    @Test
    void admin_all_keywordMatchesUsci() throws Exception {
        Long match = insertCustomer("某客户", "91110000123456789Q");
        Long other = insertCustomer("无关客户", usci(2));
        insertLead(salesA.getId(), match, "甲", LocalDateTime.now().minusMinutes(2));
        insertLead(null, other, "乙", LocalDateTime.now().minusMinutes(1));
        String token = jwtService.generateToken(admin);
        mockMvc.perform(get("/leads").param("keyword", "91110000")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].customerUsci").value("91110000123456789Q"));
    }

    @Test
    void admin_all_pageOfAllPoolLeads_doesNotError() throws Exception {
        // 回归：一整页线索全部无归属（公海）时，loadOwnerNames 返回空映射；
        // 旧实现对每行做 ownerNames.get(null)，若空映射为不可变 Map.of() 则 NPE→500。
        Long cust = insertCustomer("公海客户", usci(1));
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        for (int i = 1; i <= 3; i++) {
            insertLead(null, cust, "公海联系人" + i, base.plusMinutes(i));
        }
        String token = jwtService.generateToken(admin);
        mockMvc.perform(get("/leads").param("page", "1").param("size", "20")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(3))
            .andExpect(jsonPath("$.data.items.length()").value(3))
            .andExpect(jsonPath("$.data.items[0].ownerSalesId").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].customerName").value("公海客户"));
    }

    @Test
    void sales_listAll_stillForbidden() throws Exception {
        String token = jwtService.generateToken(salesA);
        mockMvc.perform(get("/leads")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
