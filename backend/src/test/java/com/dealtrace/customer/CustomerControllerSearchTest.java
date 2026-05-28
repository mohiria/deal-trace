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
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec R6 搜索 / 列表端点集成测试。
 *
 * <p>本类用单事务（自动回滚）：每次插入大批量 customer 行不会污染下一个测试。
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

    /** 用合法但批量生成的 USCI 占位（仅用于本测试 schema 合法性，不参与 USCI 校验路径）。 */
    private String fakeUsci(int seq) {
        // 不需要校验位合法（这些是直接 INSERT，绕过 service 校验），但需要保持 18 位 unique
        String s = String.format("%018d", seq);
        return s.substring(0, 18);
    }

    // ---- spec R6 场景 1 ----
    @Test
    void noKeyword_returnsLatest50Rows_orderedDesc() throws Exception {
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        for (int i = 1; i <= 60; i++) {
            insertCustomer("公司" + i, fakeUsci(i), base.plusMinutes(i));
        }

        MvcResult result = mockMvc.perform(get("/customers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.length()").value(50))
            // 倒序：第一个应该是 i=60 那个（最新）
            .andExpect(jsonPath("$.data[0].name").value("公司60"))
            // 不应包含最早的 10 条（公司1 ~ 公司10）
            .andExpect(jsonPath("$.data[49].name").value("公司11"))
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("\"name\":\"公司1\",");
        assertThat(body).doesNotContain("\"name\":\"公司10\",");
    }

    // ---- spec R6 场景 2 ----
    @Test
    void keywordMatchesNameSubstring() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        insertCustomer("中国建筑设计研究院", fakeUsci(1), now.minusMinutes(3));
        insertCustomer("北京建筑科学院", fakeUsci(2), now.minusMinutes(2));
        insertCustomer("上海某科技", fakeUsci(3), now.minusMinutes(1));

        mockMvc.perform(get("/customers").param("keyword", "建筑")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            // 两个匹配项都含 "建筑"
            .andExpect(jsonPath("$.data[0].name").value(org.hamcrest.Matchers.containsString("建筑")))
            .andExpect(jsonPath("$.data[1].name").value(org.hamcrest.Matchers.containsString("建筑")));
    }

    // ---- spec R6 场景 3 ----
    @Test
    void keywordMatchesUsciSubstring() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        insertCustomer("公司 Y", "91110000123456789Q", now.minusMinutes(1));
        insertCustomer("无关公司", "91500000747150374X", now);

        mockMvc.perform(get("/customers").param("keyword", "91110000")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].name").value("公司 Y"));
    }

    // ---- spec R6 场景 4 ----
    @Test
    void keywordNoMatch_returnsEmptyArray() throws Exception {
        insertCustomer("公司 X", fakeUsci(1), LocalDateTime.now());

        mockMvc.perform(get("/customers").param("keyword", "不存在的关键词xyz999")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ---- spec R6 + design D2 (keyword trim 等价于无 keyword) ----
    @Test
    void blankKeyword_equalsNoKeyword() throws Exception {
        insertCustomer("X1", fakeUsci(1), LocalDateTime.now());
        insertCustomer("X2", fakeUsci(2), LocalDateTime.now());

        mockMvc.perform(get("/customers").param("keyword", "   ")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));
    }

    // ---- spec R6 场景 5 (匿名 401) ----
    @Test
    void anonymous_rejectedWith401() throws Exception {
        mockMvc.perform(get("/customers"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
