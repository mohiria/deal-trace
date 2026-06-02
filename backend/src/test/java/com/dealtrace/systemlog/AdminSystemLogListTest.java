package com.dealtrace.systemlog;

import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.auth.JwtService;
import com.dealtrace.common.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * view-system-log spec R2：GET /admin/system-logs 全局浏览（ADMIN-only、分页倒序、含 account 事件、筛选）。
 *
 * <p>隔离策略：仅 INSERT 测试自造数据；对全局结果用<b>唯一 action</b> 过滤以在共享库上确定性断言；
 * 不 DELETE/TRUNCATE，{@code @Rollback} 回滚。SALES→403 为路径级守卫（{@code /admin/**}），无数据依赖。
 */
@AutoConfigureMockMvc
class AdminSystemLogListTest extends IntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Account admin;
    private Account sales;
    private String uniqueAction;

    @BeforeEach
    void seed() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        admin = insertAccount("vsg-admin-" + u + "@dealtrace.test", Role.ADMIN);
        sales = insertAccount("vsg-sales-" + u + "@dealtrace.test", Role.SALES);
        uniqueAction = "ZZ_VIEWLOG_" + u;
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

    private void insertLog(String action, String targetType, Long targetId, Long leadId, LocalDateTime createdAt) {
        jdbcTemplate.update(
            "INSERT INTO system_log (action, target_type, target_id, operator_id, lead_id, summary, detail, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            action, targetType, targetId, admin.getId(), leadId, null, null, createdAt);
    }

    private String token(Account a) {
        return "Bearer " + jwtService.generateToken(a);
    }

    // ---- R2：SALES 访问全局端点 → 403 FORBIDDEN，无数据 ----
    @Test
    void sales_forbidden() throws Exception {
        mockMvc.perform(get("/admin/system-logs").header(HttpHeaders.AUTHORIZATION, token(sales)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.data").value(nullValue()));
    }

    // ---- R2：ADMIN 分页倒序，按唯一 action 过滤含 account 事件（lead_id=NULL）----
    @Test
    void admin_listsFilteredDesc_includesAccountEvent() throws Exception {
        insertLog(uniqueAction, "LEAD", 777L, 777L, LocalDateTime.of(2026, 1, 1, 9, 0));
        insertLog(uniqueAction, "ACCOUNT", admin.getId(), null, LocalDateTime.of(2026, 1, 2, 9, 0));

        mockMvc.perform(get("/admin/system-logs")
                .param("action", uniqueAction)
                .header(HttpHeaders.AUTHORIZATION, token(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.total").value(2))
            // 倒序：后插的 account 事件在前；account 事件 lead_id 为 NULL，证明全局含 account 事件
            .andExpect(jsonPath("$.data.items[0].targetType").value("ACCOUNT"))
            .andExpect(jsonPath("$.data.items[0].leadId").value(nullValue()))
            .andExpect(jsonPath("$.data.items[1].targetType").value("LEAD"))
            .andExpect(jsonPath("$.data.page").value(1));
    }

    // ---- R2：target_type 过滤 ----
    @Test
    void admin_filtersByTargetType() throws Exception {
        insertLog(uniqueAction, "LEAD", 888L, 888L, LocalDateTime.of(2026, 1, 1, 9, 0));
        insertLog(uniqueAction, "ACCOUNT", admin.getId(), null, LocalDateTime.of(2026, 1, 2, 9, 0));

        mockMvc.perform(get("/admin/system-logs")
                .param("action", uniqueAction)
                .param("targetType", "LEAD")
                .header(HttpHeaders.AUTHORIZATION, token(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].targetType").value("LEAD"));
    }

    // ---- R2：分页元信息 ----
    @Test
    void admin_returnsPagingMetadata() throws Exception {
        mockMvc.perform(get("/admin/system-logs")
                .param("size", "5")
                .header(HttpHeaders.AUTHORIZATION, token(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.size").value(5))
            .andExpect(jsonPath("$.data.page").value(1))
            .andExpect(jsonPath("$.data.total").value(greaterThanOrEqualTo(0)));
    }
}
