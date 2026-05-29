package com.dealtrace.dashboard;

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
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dashboard API/集成测试（真 MySQL 8.4，spec dashboard 全 5 Requirement）。
 *
 * <p>覆盖：未登录 401；Admin/Sales 四指标口径差异；公海数双视角同值；今日/本月毫秒边界；
 * 金额空集=0；流失率分母0=null / 仅赢单=0；无主线索不计 Sales；停用 Sales 历史事件计入全局；
 * 不写系统日志。
 *
 * <p>时间窗以「当前真实 now」为基准构造数据：今日用 now 当天，本月用 now 当月，跨界用上月末 /
 * 下月初 / 昨日。owner / deal_sales_id / won_at / lost_at / created_at 用 JdbcTemplate 精确写入。
 * 遵守项目约定：不在回滚事务内 raw TRUNCATE（用 DELETE / mapper.delete）。
 */
@AutoConfigureMockMvc
class DashboardControllerTest extends IntegrationTest {

    private static final String VALID_USCI = "91110000123456789Q";

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

    private final LocalDateTime now = LocalDateTime.now();
    private final LocalDateTime todayStart = now.toLocalDate().atStartOfDay();
    private final LocalDateTime monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay();
    private final LocalDateTime midThisMonth = monthStart.plusDays(10).plusHours(9);

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM contract");
        leadMapper.delete(null);
        customerMapper.delete(null);
        accountMapper.delete(null);

        salesA = insertAccount("dash-a@dealtrace.test", Role.SALES, AccountStatus.ENABLED);
        salesB = insertAccount("dash-b@dealtrace.test", Role.SALES, AccountStatus.ENABLED);
        admin = insertAccount("dash-admin@dealtrace.test", Role.ADMIN, AccountStatus.ENABLED);

        Customer c = new Customer();
        c.setName("Dash Customer");
        c.setUsci(VALID_USCI);
        c.setCreatedAt(now);
        customerMapper.insert(c);
        customerId = c.getId();
    }

    // ============================ R1：访问与角色 ============================

    @Test
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/dashboard")).andExpect(status().isUnauthorized());
    }

    @Test
    void bothRoles_return200_withAllMetrics_noSystemLog() throws Exception {
        long logsBefore = systemLogCount();
        for (Account who : new Account[]{admin, salesA}) {
            getDashboard(who)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.todayNewLeadCount").exists())
                .andExpect(jsonPath("$.data.openSeaUnclaimedCount").exists())
                .andExpect(jsonPath("$.data.monthlyWonAmount").exists())
                .andExpect(jsonPath("$.data.monthlyEndedEventCount").exists());
        }
        // 纯只读不写日志
        org.assertj.core.api.Assertions.assertThat(systemLogCount()).isEqualTo(logsBefore);
    }

    // ============================ R2：今日新增 ============================

    @Test
    void today_adminCountsGlobal_salesCountsOwnOnly() throws Exception {
        // 今日：A 2 条、B 1 条、无主 1 条
        leadCreatedAt(salesA.getId(), LeadStage.UNTOUCHED, todayStart.plusHours(1));
        leadCreatedAt(salesA.getId(), LeadStage.CONTACTED, todayStart.plusHours(2));
        leadCreatedAt(salesB.getId(), LeadStage.UNTOUCHED, todayStart.plusHours(3));
        leadCreatedAt(null, LeadStage.UNTOUCHED, todayStart.plusHours(4));

        getDashboard(admin).andExpect(jsonPath("$.data.todayNewLeadCount").value(4));
        getDashboard(salesA).andExpect(jsonPath("$.data.todayNewLeadCount").value(2));
    }

    @Test
    void today_yesterdayBoundaryExcluded_midnightIncluded() throws Exception {
        leadCreatedAt(salesA.getId(), LeadStage.UNTOUCHED, todayStart.minusNanos(1_000_000)); // 昨日23:59:59.999
        leadCreatedAt(salesA.getId(), LeadStage.UNTOUCHED, todayStart);                       // 今日00:00:00.000

        getDashboard(admin).andExpect(jsonPath("$.data.todayNewLeadCount").value(1));
        getDashboard(salesA).andExpect(jsonPath("$.data.todayNewLeadCount").value(1));
    }

    // ============================ R3：公海待认领 ============================

    @Test
    void openSea_countsNullOwnerActive_ownedAndEndedExcluded_salesEqualsAdmin() throws Exception {
        leadCreatedAt(null, LeadStage.UNTOUCHED, midThisMonth);   // 计
        leadCreatedAt(null, LeadStage.CONTACTED, midThisMonth);   // 计
        leadCreatedAt(null, LeadStage.QUOTED, midThisMonth);      // 计
        leadCreatedAt(null, LeadStage.NEGOTIATING, midThisMonth); // 计
        leadCreatedAt(null, LeadStage.LOST, midThisMonth);        // 已结束，不计
        leadCreatedAt(salesA.getId(), LeadStage.UNTOUCHED, midThisMonth); // 有主，不计

        getDashboard(admin).andExpect(jsonPath("$.data.openSeaUnclaimedCount").value(4));
        getDashboard(salesA).andExpect(jsonPath("$.data.openSeaUnclaimedCount").value(4)); // 两视角同值
    }

    // ============================ R4：本月赢单金额 ============================

    @Test
    void wonAmount_adminSumsGlobal_salesByDealSales_ownerlessGlobalOnly() throws Exception {
        wonLead(salesA.getId(), salesA.getId(), midThisMonth, "80000.00"); // A
        wonLead(salesB.getId(), salesB.getId(), midThisMonth, "60000.00"); // B
        wonLead(null, null, midThisMonth, "10000.50");                     // 无主（公海单 Admin 赢单）

        getDashboard(admin).andExpect(jsonPath("$.data.monthlyWonAmount").value(150000.50));
        getDashboard(salesA).andExpect(jsonPath("$.data.monthlyWonAmount").value(80000.00));
    }

    @Test
    void wonAmount_anchorsOnWonAtNotSignedDate_crossMonth() throws Exception {
        // 签订日期上月，但赢单事件 won_at 在本月 → 计入本月
        Long leadId = leadCreatedAt(salesA.getId(), LeadStage.WON, monthStart.minusDays(5));
        setWonAt(leadId, midThisMonth);
        insertContract(leadId, "55000.00", monthStart.minusDays(3).toLocalDate(), salesA.getId(), midThisMonth);

        getDashboard(admin).andExpect(jsonPath("$.data.monthlyWonAmount").value(55000.00));
    }

    @Test
    void wonAmount_emptyIsZero() throws Exception {
        getDashboard(admin).andExpect(jsonPath("$.data.monthlyWonAmount").value(0));
        getDashboard(salesA).andExpect(jsonPath("$.data.monthlyWonAmount").value(0));
    }

    @Test
    void wonAmount_disabledSalesStillCountedInGlobal() throws Exception {
        wonLead(salesA.getId(), salesA.getId(), midThisMonth, "12345.00");
        accountMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Account>()
            .eq("id", salesA.getId()).set("status", AccountStatus.DISABLED.name()));

        getDashboard(admin).andExpect(jsonPath("$.data.monthlyWonAmount").value(12345.00));
    }

    @Test
    void wonAmount_lastMonthExcluded() throws Exception {
        wonLead(salesA.getId(), salesA.getId(), monthStart.minusNanos(1_000_000), "999.00"); // 上月末
        getDashboard(admin).andExpect(jsonPath("$.data.monthlyWonAmount").value(0));
    }

    // ============================ R5：本月流失率 ============================

    @Test
    void lossRate_adminGlobal_twoLostThreeWon() throws Exception {
        wonLead(salesA.getId(), salesA.getId(), midThisMonth, "100.00");
        wonLead(salesA.getId(), salesA.getId(), midThisMonth, "100.00");
        wonLead(salesB.getId(), salesB.getId(), midThisMonth, "100.00");
        lostLead(salesA.getId(), midThisMonth);
        lostLead(salesB.getId(), midThisMonth);

        getDashboard(admin)
            .andExpect(jsonPath("$.data.monthlyLostEventCount").value(2))
            .andExpect(jsonPath("$.data.monthlyEndedEventCount").value(5))
            .andExpect(jsonPath("$.data.monthlyLossRate").value(0.4));
    }

    @Test
    void lossRate_salesByEventOwner() throws Exception {
        // A：1 流失 + 3 赢单；B：若干（不应进 A 口径）
        wonLead(salesA.getId(), salesA.getId(), midThisMonth, "100.00");
        wonLead(salesA.getId(), salesA.getId(), midThisMonth, "100.00");
        wonLead(salesA.getId(), salesA.getId(), midThisMonth, "100.00");
        lostLead(salesA.getId(), midThisMonth);
        wonLead(salesB.getId(), salesB.getId(), midThisMonth, "100.00");
        lostLead(salesB.getId(), midThisMonth);

        getDashboard(salesA)
            .andExpect(jsonPath("$.data.monthlyLostEventCount").value(1))
            .andExpect(jsonPath("$.data.monthlyEndedEventCount").value(4))
            .andExpect(jsonPath("$.data.monthlyLossRate").value(0.25));
    }

    @Test
    void lossRate_zeroDenominatorIsNull() throws Exception {
        getDashboard(admin)
            .andExpect(jsonPath("$.data.monthlyEndedEventCount").value(0))
            .andExpect(jsonPath("$.data.monthlyLossRate").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void lossRate_onlyWonIsZeroNotNull() throws Exception {
        wonLead(salesA.getId(), salesA.getId(), midThisMonth, "100.00");
        wonLead(salesA.getId(), salesA.getId(), midThisMonth, "100.00");
        getDashboard(admin)
            .andExpect(jsonPath("$.data.monthlyEndedEventCount").value(2))
            .andExpect(jsonPath("$.data.monthlyLossRate").value(0.0));
    }

    @Test
    void lossRate_ownerlessLostGlobalOnly() throws Exception {
        lostLead(null, midThisMonth); // 无主流失
        getDashboard(admin)
            .andExpect(jsonPath("$.data.monthlyLostEventCount").value(1))
            .andExpect(jsonPath("$.data.monthlyEndedEventCount").value(1));
        getDashboard(salesA)
            .andExpect(jsonPath("$.data.monthlyLostEventCount").value(0))
            .andExpect(jsonPath("$.data.monthlyEndedEventCount").value(0))
            .andExpect(jsonPath("$.data.monthlyLossRate").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void lossRate_disabledSalesEndedEventsStillInGlobal() throws Exception {
        wonLead(salesA.getId(), salesA.getId(), midThisMonth, "100.00");
        lostLead(salesA.getId(), midThisMonth);
        accountMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Account>()
            .eq("id", salesA.getId()).set("status", AccountStatus.DISABLED.name()));

        getDashboard(admin)
            .andExpect(jsonPath("$.data.monthlyEndedEventCount").value(2))
            .andExpect(jsonPath("$.data.monthlyLostEventCount").value(1));
    }

    // ============================ helpers ============================

    private ResultActions getDashboard(Account who) throws Exception {
        return mockMvc.perform(get("/dashboard")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateToken(who)));
    }

    private Account insertAccount(String email, Role role, AccountStatus status) {
        Account a = new Account();
        a.setEmail(email);
        a.setName(email);
        a.setRole(role);
        a.setStatus(status);
        a.setPasswordHash(passwordEncoder.encode("p@ssw0rd"));
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);
        return a;
    }

    /** 插入线索并精确设定 created_at。 */
    private Long leadCreatedAt(Long ownerId, LeadStage stage, LocalDateTime createdAt) {
        Lead l = new Lead();
        l.setCustomerId(customerId);
        l.setBusinessYear((short) createdAt.getYear());
        l.setBusinessType(BusinessType.BIM_CONSULTING);
        l.setContactName("X");
        l.setContactPhone("13800000000");
        l.setOwnerSalesId(ownerId);
        l.setStage(stage);
        l.setCreatedAt(createdAt);
        leadMapper.insert(l);
        return l.getId();
    }

    /** 赢单线索 + 合同：owner 为流失/事件归属，dealSalesId 为赢单时刻归属快照。 */
    private void wonLead(Long ownerId, Long dealSalesId, LocalDateTime wonAt, String amount) {
        Long leadId = leadCreatedAt(ownerId, LeadStage.WON, wonAt);
        setWonAt(leadId, wonAt);
        insertContract(leadId, amount, wonAt.toLocalDate(), dealSalesId, wonAt);
    }

    /** 流失线索：owner 即事件时归属（终态冻结）。 */
    private void lostLead(Long ownerId, LocalDateTime lostAt) {
        Long leadId = leadCreatedAt(ownerId, LeadStage.LOST, lostAt);
        jdbcTemplate.update("UPDATE `lead` SET lost_at = ? WHERE id = ?", lostAt, leadId);
    }

    private void setWonAt(Long leadId, LocalDateTime wonAt) {
        jdbcTemplate.update("UPDATE `lead` SET won_at = ? WHERE id = ?", wonAt, leadId);
    }

    private void insertContract(Long leadId, String amount, LocalDate signedDate,
                                Long dealSalesId, LocalDateTime createdAt) {
        jdbcTemplate.update(
            "INSERT INTO contract (lead_id, contract_amount, signed_date, deal_sales_id, created_at) "
                + "VALUES (?, ?, ?, ?, ?)",
            leadId, new BigDecimal(amount), signedDate, dealSalesId, createdAt);
    }

    private long systemLogCount() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM system_log", Long.class);
        return n == null ? 0 : n;
    }
}
