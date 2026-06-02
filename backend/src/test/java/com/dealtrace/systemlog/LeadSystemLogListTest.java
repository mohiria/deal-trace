package com.dealtrace.systemlog;

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
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * view-system-log spec R1/R3/R4：GET /leads/{id}/logs 读权限隔离、倒序、姓名/标签/金额组装、freetext fallback。
 *
 * <p>隔离策略（不污染共享库）：仅 INSERT 测试自造数据并按本测试 lead_id 过滤断言，**不** DELETE/TRUNCATE 任何表；
 * {@link IntegrationTest} 的 {@code @Rollback} 在方法结束回滚全部插入。唯一邮箱避免与残留冲突。
 */
@AutoConfigureMockMvc
class LeadSystemLogListTest extends IntegrationTest {

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
        String u = UUID.randomUUID().toString().substring(0, 8);
        salesA = insertAccount("vslog-a-" + u + "@dealtrace.test", "销售甲", Role.SALES);
        salesB = insertAccount("vslog-b-" + u + "@dealtrace.test", "销售乙", Role.SALES);
        admin = insertAccount("vslog-admin-" + u + "@dealtrace.test", "管理员", Role.ADMIN);

        Customer c = new Customer();
        c.setName("Log Customer " + u);
        c.setUsci((u + u + "ZZ").substring(0, 18).toUpperCase()); // 唯一 18 位，避免与共享库残留撞 UNIQUE
        c.setCreatedAt(LocalDateTime.now());
        customerMapper.insert(c);
        customerId = c.getId();
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

    private void insertLog(String action, String targetType, Long targetId, Long operatorId,
                           Long leadId, String summary, String detailJson, LocalDateTime createdAt) {
        jdbcTemplate.update(
            "INSERT INTO system_log (action, target_type, target_id, operator_id, lead_id, summary, detail, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            action, targetType, targetId, operatorId, leadId, summary, detailJson, createdAt);
    }

    private String token(Account a) {
        return "Bearer " + jwtService.generateToken(a);
    }

    // ---- R1：ADMIN 任意线索倒序；仅本线索条目、不含 account 事件；R3 组装 ----
    @Test
    void admin_readsAnyLead_descOnlyThisLead_assembled() throws Exception {
        Long leadId = insertLead(salesA.getId(), LeadStage.WON);
        insertLog("LEAD_CREATE", "LEAD", leadId, salesA.getId(), leadId, "创建",
            "{\"ownerSalesId\":" + salesA.getId() + "}", LocalDateTime.of(2026, 1, 1, 9, 0));
        insertLog("LEAD_TRANSFER", "LEAD", leadId, admin.getId(), leadId, "转移",
            "{\"fromOwnerId\":" + salesB.getId() + ",\"toOwnerId\":" + salesA.getId() + "}",
            LocalDateTime.of(2026, 1, 2, 9, 0));
        insertLog("LEAD_WIN", "LEAD", leadId, salesA.getId(), leadId, "赢单",
            "{\"contractAmount\":\"1234.50\",\"signedDate\":\"2026-05-31\"}",
            LocalDateTime.of(2026, 1, 3, 9, 0));
        // 干扰项：account 事件（lead_id=NULL）不应出现在线索维度
        insertLog("ACCOUNT_DISABLE", "ACCOUNT", admin.getId(), admin.getId(), null, null, null,
            LocalDateTime.of(2026, 1, 4, 9, 0));

        mockMvc.perform(get("/leads/" + leadId + "/logs").header(HttpHeaders.AUTHORIZATION, token(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.length()").value(3))
            // 倒序：win → transfer → create
            .andExpect(jsonPath("$.data[0].action").value("LEAD_WIN"))
            .andExpect(jsonPath("$.data[0].actionLabel").value("标记赢单"))
            .andExpect(jsonPath("$.data[0].operatorName").value("销售甲"))
            .andExpect(jsonPath("$.data[0].detail.contractAmount").value("1234.50"))
            .andExpect(jsonPath("$.data[1].action").value("LEAD_TRANSFER"))
            .andExpect(jsonPath("$.data[1].detail.fromOwnerName").value("销售乙"))
            .andExpect(jsonPath("$.data[1].detail.toOwnerName").value("销售甲"))
            .andExpect(jsonPath("$.data[1].operatorName").value("管理员"))
            .andExpect(jsonPath("$.data[2].action").value("LEAD_CREATE"));
    }

    // ---- R3：operator 为 NULL → "系统"；R4：detail 为 NULL → 回退 summary ----
    @Test
    void systemOperatorAndFreetextFallback() throws Exception {
        Long leadId = insertLead(salesA.getId(), LeadStage.CONTACTED);
        insertLog("LEAD_CREATE", "LEAD", leadId, null, leadId, "历史 freetext 摘要", null,
            LocalDateTime.of(2026, 2, 1, 9, 0));

        mockMvc.perform(get("/leads/" + leadId + "/logs").header(HttpHeaders.AUTHORIZATION, token(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].operatorName").value("系统"))
            .andExpect(jsonPath("$.data[0].summaryFallback").value("历史 freetext 摘要"))
            .andExpect(jsonPath("$.data[0].detail").value(nullValue()));
    }

    // ---- R1：SALES 自己名下成功 ----
    @Test
    void sales_readsOwnLead_succeeds() throws Exception {
        Long leadId = insertLead(salesA.getId(), LeadStage.CONTACTED);
        insertLog("LEAD_CLAIM", "LEAD", leadId, salesA.getId(), leadId, "认领",
            "{\"fromOwnerId\":null,\"toOwnerId\":" + salesA.getId() + "}", LocalDateTime.of(2026, 1, 1, 9, 0));

        mockMvc.perform(get("/leads/" + leadId + "/logs").header(HttpHeaders.AUTHORIZATION, token(salesA)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].detail.fromOwnerName").value("公海"))
            .andExpect(jsonPath("$.data[0].detail.toOwnerName").value("销售甲"));
    }

    // ---- R1：SALES 读他人名下 → NOT_FOUND 不泄漏 ----
    @Test
    void sales_readsOtherSalesLead_returns404() throws Exception {
        Long leadId = insertLead(salesA.getId(), LeadStage.CONTACTED);
        mockMvc.perform(get("/leads/" + leadId + "/logs").header(HttpHeaders.AUTHORIZATION, token(salesB)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.data").value(nullValue()));
    }

    // ---- R1：SALES 读公海 → NOT_FOUND ----
    @Test
    void sales_readsPoolLead_returns404() throws Exception {
        Long leadId = insertLead(null, LeadStage.UNTOUCHED);
        mockMvc.perform(get("/leads/" + leadId + "/logs").header(HttpHeaders.AUTHORIZATION, token(salesB)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---- R1：不存在线索 → NOT_FOUND ----
    @Test
    void nonexistentLead_returns404() throws Exception {
        mockMvc.perform(get("/leads/999999999/logs").header(HttpHeaders.AUTHORIZATION, token(admin)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
