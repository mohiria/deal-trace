package com.dealtrace.contract;

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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * contract-view spec R1/R2/R3/R4：GET /contracts。
 *
 * <p>匿名 401；ADMIN 全量倒序 + 三类筛选 + 公海赢单展示；SALES 仅本人、传他人 dealSalesId 仍被收窄。
 * 隔离：唯一客户名 keyword 在共享库上确定性断言，不 DELETE/TRUNCATE（{@code @Rollback} 回滚）。
 */
@AutoConfigureMockMvc
class ContractControllerListTest extends IntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private LeadMapper leadMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String customerName;
    private Account admin;
    private Account s7;
    private Account s8;
    private Long leadBim;

    @BeforeEach
    void seed() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        customerName = "CONTRACTVIEW-" + u;

        admin = insertAccount("cv-admin-" + u + "@dealtrace.test", "管理员-" + u, Role.ADMIN);
        s7 = insertAccount("cv-s7-" + u + "@dealtrace.test", "销售七-" + u, Role.SALES);
        s8 = insertAccount("cv-s8-" + u + "@dealtrace.test", "销售八-" + u, Role.SALES);

        Long customerId = insertCustomer(customerName, usci(u));
        leadBim = insertLead(customerId, BusinessType.BIM_CONSULTING, s7.getId());
        Long leadTrain = insertLead(customerId, BusinessType.BIM_TRAINING, s8.getId());
        Long leadCustom = insertLead(customerId, BusinessType.CUSTOM_DEVELOPMENT, null);

        insertContract(leadBim, "120000.50", LocalDate.of(2026, 5, 10), s7.getId(), LocalDateTime.of(2026, 5, 10, 10, 0));
        insertContract(leadTrain, "50000.00", LocalDate.of(2026, 4, 30), s8.getId(), LocalDateTime.of(2026, 5, 11, 10, 0));
        insertContract(leadCustom, "80000.00", LocalDate.of(2026, 6, 1), null, LocalDateTime.of(2026, 5, 12, 10, 0));
    }

    private String token(Account a) {
        return "Bearer " + jwtService.generateToken(a);
    }

    // ---- R1：匿名 → 401 UNAUTHORIZED，无数据 ----
    @Test
    void anonymous_unauthorized() throws Exception {
        mockMvc.perform(get("/contracts"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.data").value(nullValue()));
    }

    // ---- R1：ADMIN 全量倒序；公海赢单展示"公海赢单"；金额精确字符串 ----
    @Test
    void admin_listsAllByKeyword_descWithPoolDealLabel() throws Exception {
        mockMvc.perform(get("/contracts")
                .param("keyword", customerName)
                .header(HttpHeaders.AUTHORIZATION, token(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.items.length()").value(3))
            .andExpect(jsonPath("$.data.total").value(3))
            // 倒序首条为公海赢单（created 05-12）
            .andExpect(jsonPath("$.data.items[0].dealSalesId").value(nullValue()))
            .andExpect(jsonPath("$.data.items[0].dealSalesName").value("公海赢单"))
            // 末条为 BIM 咨询（created 05-10）：金额精确、成交销售当前姓名
            .andExpect(jsonPath("$.data.items[2].businessType").value("BIM咨询"))
            .andExpect(jsonPath("$.data.items[2].contractAmount").value("120000.50"))
            .andExpect(jsonPath("$.data.items[2].dealSalesName").value(s7.getName()));
    }

    // ---- R1：ADMIN 按成交销售筛选 ----
    @Test
    void admin_filterByDealSales() throws Exception {
        mockMvc.perform(get("/contracts")
                .param("keyword", customerName)
                .param("dealSalesId", String.valueOf(s7.getId()))
                .header(HttpHeaders.AUTHORIZATION, token(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].leadId").value(leadBim));
    }

    // ---- R1：ADMIN 按签订日期闭区间筛选 ----
    @Test
    void admin_filterBySignedDateRange() throws Exception {
        mockMvc.perform(get("/contracts")
                .param("keyword", customerName)
                .param("signedDateFrom", "2026-05-01")
                .param("signedDateTo", "2026-05-31")
                .header(HttpHeaders.AUTHORIZATION, token(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].leadId").value(leadBim));
    }

    // ---- R2：SALES 仅看自己成交（排除他人与公海赢单）----
    @Test
    void sales_seesOnlyOwn() throws Exception {
        mockMvc.perform(get("/contracts")
                .param("keyword", customerName)
                .header(HttpHeaders.AUTHORIZATION, token(s7)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].leadId").value(leadBim))
            .andExpect(jsonPath("$.data.items[0].dealSalesId").value(s7.getId()));
    }

    // ---- R2：SALES 传他人 dealSalesId 仍被收窄为本人 ----
    @Test
    void sales_otherDealSalesParamNarrowedToSelf() throws Exception {
        mockMvc.perform(get("/contracts")
                .param("keyword", customerName)
                .param("dealSalesId", String.valueOf(s8.getId()))
                .header(HttpHeaders.AUTHORIZATION, token(s7)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].dealSalesId").value(s7.getId()));
    }

    // ---- 造数辅助 ----

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

    private Long insertLead(Long customerId, BusinessType type, Long ownerId) {
        Lead l = new Lead();
        l.setCustomerId(customerId);
        l.setBusinessYear((short) 2026);
        l.setBusinessType(type);
        l.setContactName("联系人");
        l.setContactPhone("13800000000");
        l.setOwnerSalesId(ownerId);
        l.setStage(LeadStage.WON);
        l.setCreatedAt(LocalDateTime.now());
        leadMapper.insert(l);
        return l.getId();
    }

    private void insertContract(Long leadId, String amount, LocalDate signedDate, Long dealSalesId, LocalDateTime createdAt) {
        jdbcTemplate.update(
            "INSERT INTO contract (lead_id, contract_amount, signed_date, deal_sales_id, created_at) VALUES (?, ?, ?, ?, ?)",
            leadId, new BigDecimal(amount), signedDate, dealSalesId, createdAt);
    }

    private static String usci(String u) {
        String base = ("91110000" + u + "000000000000").toUpperCase();
        return base.substring(0, 18);
    }
}
