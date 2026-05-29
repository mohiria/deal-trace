package com.dealtrace.dashboard.service;

import com.dealtrace.account.entity.Role;
import com.dealtrace.dashboard.dto.DashboardView;
import com.dealtrace.dashboard.repository.DashboardMapper;
import com.dealtrace.security.AccountPrincipal;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Dashboard 指标编排（spec dashboard / PRD §7.12，纯只读）。
 *
 * <p>角色分流（design D2）：Admin → 全局口径（{@code me=null}）；Sales → 个人口径（{@code me=principal.id()}）。
 * 例外：当前公海待认领数对两角色恒为全局（PRD §7.12-9）。
 *
 * <p>归属语义双轨（PRD §7.12-12）：存量指标（今日新增、公海数）按当前归属；事件指标（本月赢单金额、
 * 流失率）按事件发生时归属——赢单走 {@code contract.deal_sales_id} 快照，流失走已冻结的
 * {@code lead.owner_sales_id}（design D1）。
 *
 * <p>时间窗（design D4）：服务端本地时间，闭起开止。今日 {@code [今天00:00, 次日00:00)}；
 * 本月 {@code [本月1号00:00, 下月1号00:00)}。不读客户端时钟。
 *
 * <p>归一（design D3）：赢单金额空集 → {@code 0}；流失率分母为 0 → {@code null}（前端渲染 "--"），
 * 分母 &gt; 0 而分子为 0 → {@code 0}（0%）。
 */
@Service
public class DashboardService {

    /** 流失率小数精度：保留 4 位（如 0.4000 / 0.2500），HALF_UP。前端按需格式化为百分比。 */
    static final int LOSS_RATE_SCALE = 4;

    private final DashboardMapper dashboardMapper;

    public DashboardService(DashboardMapper dashboardMapper) {
        this.dashboardMapper = dashboardMapper;
    }

    public DashboardView load(AccountPrincipal principal) {
        return load(principal, LocalDateTime.now());
    }

    /** 以指定 now 计算（供单元测试注入固定时钟）。 */
    DashboardView load(AccountPrincipal principal, LocalDateTime now) {
        Long me = (principal.role() == Role.SALES) ? principal.id() : null;

        TimeWindow today = today(now);
        TimeWindow month = thisMonth(now);

        long todayNew = dashboardMapper.countTodayNew(today.from(), today.to(), me);
        long openSea = dashboardMapper.countOpenSeaUnclaimed(); // 恒全局，两视角同值
        BigDecimal wonAmount = normalizeAmount(
            dashboardMapper.sumMonthlyWonAmount(month.from(), month.to(), me));

        long wonEvents = dashboardMapper.countMonthlyWonEvents(month.from(), month.to(), me);
        long lostEvents = dashboardMapper.countMonthlyLostEvents(month.from(), month.to(), me);
        long endedEvents = wonEvents + lostEvents;
        BigDecimal lossRate = lossRate(lostEvents, endedEvents);

        return new DashboardView(todayNew, openSea, wonAmount, lossRate, lostEvents, endedEvents);
    }

    /** SQL SUM 空集返回 null → 归一为 0（"0.00"）。 */
    private BigDecimal normalizeAmount(BigDecimal raw) {
        return raw == null ? BigDecimal.ZERO.setScale(2) : raw;
    }

    /** 分母 0 → null（无法计算）；否则 lost/ended，保留 {@link #LOSS_RATE_SCALE} 位。 */
    private BigDecimal lossRate(long lostEvents, long endedEvents) {
        if (endedEvents == 0) {
            return null;
        }
        return BigDecimal.valueOf(lostEvents)
            .divide(BigDecimal.valueOf(endedEvents), LOSS_RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** 今日窗口 [今天00:00:00.000, 次日00:00:00.000)。 */
    static TimeWindow today(LocalDateTime now) {
        LocalDateTime from = now.toLocalDate().atStartOfDay();
        return new TimeWindow(from, from.plusDays(1));
    }

    /** 本月窗口 [本月1号00:00:00.000, 下月1号00:00:00.000)。 */
    static TimeWindow thisMonth(LocalDateTime now) {
        LocalDate first = now.toLocalDate().withDayOfMonth(1);
        LocalDateTime from = first.atStartOfDay();
        return new TimeWindow(from, first.plusMonths(1).atStartOfDay());
    }

    /** 闭起开止时间窗 [from, to)。 */
    record TimeWindow(LocalDateTime from, LocalDateTime to) {
    }
}
