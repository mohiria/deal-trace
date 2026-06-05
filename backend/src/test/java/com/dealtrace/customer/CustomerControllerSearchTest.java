package com.dealtrace.customer;

import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.auth.JwtService;
import com.dealtrace.common.IntegrationTest;
import com.dealtrace.customer.entity.Customer;
import com.dealtrace.customer.repository.CustomerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 客户搜索 / 列表端点集成测试（customer spec「客户搜索 / 列表统一端点」MODIFIED：服务端分页 + keyword 全表）。
 *
 * <p>响应 {@code data} 为分页信封 {@code { items, total, page, size }}；keyword 对全表匹配后分页。
 * 单事务自动回滚：每次插入大批量 customer 行不污染下一个测试。
 */
@AutoConfigureMockMvc
class CustomerControllerSearchTest extends IntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private AccountMapper accountMapper;
    @Autowired private JwtService jwtService;

    private String token;

    @BeforeEach
    void seed() {
        accountMapper.delete(null);
        Account sales = new Account();
        sales.setEmail("search-sales@dealtrace.test");
        sales.setName("Sales");
        sales.setRole(Role.SALES);
        sales.setStatus(AccountStatus.ENABLED);
        sales.setPasswordHash("dummy-hash-for-search-test-only-do-not-use-for-login-please");
        LocalDateTime now = LocalDateTime.now();
        sales.setCreatedAt(now);
        sales.setUpdatedAt(now);
        accountMapper.insert(sales);
        token = jwtService.generateToken(sales);

        customerMapper.delete(null);
    }

    private void insertCustomer(String name, String usci, LocalDateTime createdAt) {
        Customer c = new Customer();
        c.setName(name);
        c.setUsci(usci);
        c.setCreatedAt(createdAt);
        customerMapper.insert(c);
    }

    /** 用合法 18 位占位 USCI（直接 INSERT，绕过 service 校验，仅需 unique）。 */
    private String fakeUsci(int seq) {
        return String.format("%018d", seq);
    }

    // ---- 无关键词：分页信封，total = 全表数 ----
    @Test
    void noKeyword_paginatedEnvelope_totalIsAll() throws Exception {
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        for (int i = 1; i <= 60; i++) {
            insertCustomer("公司" + i, fakeUsci(i), base.plusMinutes(i));
        }

        mockMvc.perform(get("/customers").param("page", "1").param("size", "20")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.items.length()").value(20))
            .andExpect(jsonPath("$.data.total").value(60))
            .andExpect(jsonPath("$.data.page").value(1))
            .andExpect(jsonPath("$.data.size").value(20))
            // 倒序：第一个应是 i=60（最新）
            .andExpect(jsonPath("$.data.items[0].name").value("公司60"));
    }

    // ---- 翻页可访问首页之外（page3 与 page1 无交集）----
    @Test
    void paging_reachesBeyondFirstPage() throws Exception {
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        for (int i = 1; i <= 60; i++) {
            insertCustomer("公司" + i, fakeUsci(i), base.plusMinutes(i));
        }

        // desc：page1=公司60..公司41，page3=公司20..公司1
        mockMvc.perform(get("/customers").param("page", "3").param("size", "20")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(20))
            .andExpect(jsonPath("$.data.total").value(60))
            .andExpect(jsonPath("$.data.page").value(3))
            .andExpect(jsonPath("$.data.items[0].name").value("公司20"));
    }

    // ---- keyword 命中 name 子串，且命中项位于全表靠后分页位仍被搜出 ----
    @Test
    void keywordMatchesName_acrossWholeTable() throws Exception {
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        // 唯一含「建筑」者 created_at 最早（无 keyword 时落在最后一页之外的位置）
        insertCustomer("某某建筑设计研究院", fakeUsci(1), base.plusMinutes(1));
        for (int i = 2; i <= 60; i++) {
            insertCustomer("普通公司" + i, fakeUsci(i), base.plusMinutes(i));
        }

        mockMvc.perform(get("/customers").param("keyword", "建筑").param("page", "1").param("size", "20")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].name").value(org.hamcrest.Matchers.containsString("建筑")));
    }

    // ---- keyword 命中 USCI 子串 ----
    @Test
    void keywordMatchesUsciSubstring() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        insertCustomer("公司 Y", "91110000123456789Q", now.minusMinutes(1));
        insertCustomer("无关公司", "91500000747150374X", now);

        mockMvc.perform(get("/customers").param("keyword", "91110000")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].name").value("公司 Y"));
    }

    // ---- keyword 无命中：空 items + total 0，HTTP200 ----
    @Test
    void keywordNoMatch_emptyItemsTotalZero() throws Exception {
        insertCustomer("公司 X", fakeUsci(1), LocalDateTime.now());

        mockMvc.perform(get("/customers").param("keyword", "不存在的关键词xyz999")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.items").isArray())
            .andExpect(jsonPath("$.data.items.length()").value(0))
            .andExpect(jsonPath("$.data.total").value(0));
    }

    // ---- 空白 keyword 等价无 keyword ----
    @Test
    void blankKeyword_equalsNoKeyword() throws Exception {
        insertCustomer("X1", fakeUsci(1), LocalDateTime.now());
        insertCustomer("X2", fakeUsci(2), LocalDateTime.now());

        mockMvc.perform(get("/customers").param("keyword", "   ")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(2));
    }

    // ---- 匿名 401 ----
    @Test
    void anonymous_rejectedWith401() throws Exception {
        mockMvc.perform(get("/customers"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
