package com.dealtrace.dashboard.service;

import com.dealtrace.account.entity.Role;
import com.dealtrace.dashboard.dto.DashboardView;
import com.dealtrace.dashboard.repository.DashboardMapper;
import com.dealtrace.dashboard.service.DashboardService.TimeWindow;
import com.dealtrace.security.AccountPrincipal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 纯单元测试（无 Spring 上下文）：时间窗计算（design D4）、金额空集归一、流失率分母 0→null / 分子 0→0（design D3）。
 * 取数 SQL 正确性归 API/集成层（真 MySQL），此处仅锁纯函数逻辑。
 */
class DashboardServiceTest {

    // ---- 6.1 时间窗：今日 [00:00, 次日00:00) ----
    @Test
    void today_isStartOfDayToNextDayExclusive() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 29, 13, 47, 12, 345_000_000);
        TimeWindow w = DashboardService.today(now);
        assertThat(w.from()).isEqualTo(LocalDateTime.of(2026, 5, 29, 0, 0, 0, 0));
        assertThat(w.to()).isEqualTo(LocalDateTime.of(2026, 5, 30, 0, 0, 0, 0));
    }

    // ---- 6.1 时间窗：本月 [1号00:00, 下月1号00:00) ----
    @Test
    void thisMonth_isFirstOfMonthToFirstOfNextMonthExclusive() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 29, 13, 47, 12);
        TimeWindow w = DashboardService.thisMonth(now);
        assertThat(w.from()).isEqualTo(LocalDateTime.of(2026, 5, 1, 0, 0, 0, 0));
        assertThat(w.to()).isEqualTo(LocalDateTime.of(2026, 6, 1, 0, 0, 0, 0));
    }

    // ---- 6.1 跨年月：12 月本月窗止于次年 1 月 1 号 ----
    @Test
    void thisMonth_decemberRollsToNextYear() {
        TimeWindow w = DashboardService.thisMonth(LocalDateTime.of(2026, 12, 15, 9, 0));
        assertThat(w.from()).isEqualTo(LocalDateTime.of(2026, 12, 1, 0, 0));
        assertThat(w.to()).isEqualTo(LocalDateTime.of(2027, 1, 1, 0, 0));
    }

    // ---- 6.1 金额空集（SUM 返回 null）归一为 0 ----
    @Test
    void wonAmount_emptyNormalizedToZero() {
        DashboardMapper mapper = mock(DashboardMapper.class);
        when(mapper.sumMonthlyWonAmount(any(), any(), any())).thenReturn(null);
        DashboardView v = new DashboardService(mapper)
            .load(admin(), LocalDateTime.of(2026, 5, 29, 10, 0));
        assertThat(v.monthlyWonAmount()).isEqualByComparingTo("0");
        assertThat(v.monthlyWonAmount()).isNotNull();
    }

    // ---- 6.1 流失率分母 0 → null ----
    @Test
    void lossRate_zeroDenominatorIsNull() {
        DashboardMapper mapper = mock(DashboardMapper.class);
        when(mapper.countMonthlyWonEvents(any(), any(), any())).thenReturn(0L);
        when(mapper.countMonthlyLostEvents(any(), any(), any())).thenReturn(0L);
        DashboardView v = new DashboardService(mapper)
            .load(admin(), LocalDateTime.of(2026, 5, 29, 10, 0));
        assertThat(v.monthlyLossRate()).isNull();
        assertThat(v.monthlyEndedEventCount()).isZero();
    }

    // ---- 6.1 流失率分子 0 但分母>0 → 0（非 null）----
    @Test
    void lossRate_onlyWonIsZeroNotNull() {
        DashboardMapper mapper = mock(DashboardMapper.class);
        when(mapper.countMonthlyWonEvents(any(), any(), any())).thenReturn(2L);
        when(mapper.countMonthlyLostEvents(any(), any(), any())).thenReturn(0L);
        DashboardView v = new DashboardService(mapper)
            .load(admin(), LocalDateTime.of(2026, 5, 29, 10, 0));
        assertThat(v.monthlyLossRate()).isNotNull().isEqualByComparingTo("0");
        assertThat(v.monthlyEndedEventCount()).isEqualTo(2);
    }

    // ---- 6.1 流失率正常 2/5 = 0.4 ----
    @Test
    void lossRate_computesRatio() {
        DashboardMapper mapper = mock(DashboardMapper.class);
        when(mapper.countMonthlyWonEvents(any(), any(), any())).thenReturn(3L);
        when(mapper.countMonthlyLostEvents(any(), any(), any())).thenReturn(2L);
        DashboardView v = new DashboardService(mapper)
            .load(admin(), LocalDateTime.of(2026, 5, 29, 10, 0));
        assertThat(v.monthlyLossRate()).isEqualByComparingTo("0.4");
        assertThat(v.monthlyLostEventCount()).isEqualTo(2);
        assertThat(v.monthlyEndedEventCount()).isEqualTo(5);
    }

    // ---- 6.1 角色分流：Admin 传 me=null，Sales 传 me=id ----
    @Test
    void scope_adminPassesNullMe_salesPassesOwnId() {
        DashboardMapper mapper = mock(DashboardMapper.class);
        LocalDateTime now = LocalDateTime.of(2026, 5, 29, 10, 0);

        new DashboardService(mapper).load(admin(), now);
        // Admin: me 必须为 null（全局）
        org.mockito.Mockito.verify(mapper).countTodayNew(any(), any(), eq((Long) null));

        new DashboardService(mapper).load(sales(7L), now);
        // Sales: me 必须为本人 id（个人）
        org.mockito.Mockito.verify(mapper).countTodayNew(any(), any(), eq(7L));
        // 公海数恒全局：两次调用都不带 me（无参方法）
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(2)).countOpenSeaUnclaimed();
    }

    private AccountPrincipal admin() {
        return new AccountPrincipal(1L, "admin@dealtrace.test", Role.ADMIN);
    }

    private AccountPrincipal sales(long id) {
        return new AccountPrincipal(id, "sales@dealtrace.test", Role.SALES);
    }
}
