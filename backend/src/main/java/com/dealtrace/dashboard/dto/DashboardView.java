package com.dealtrace.dashboard.dto;

import java.math.BigDecimal;

/**
 * Dashboard 指标看板响应（spec dashboard / PRD §7.12）。
 *
 * <p>四项指标：今日新增、当前公海待认领、本月赢单总金额、本月流失率。同一结构对 Admin（全局口径）
 * 与 Sales（个人口径）通用，由 service 按 {@code principal.role()} 决定取数过滤；调用方不传视角。
 *
 * <p>语义约定（design D3）：
 * <ul>
 *   <li>{@code monthlyWonAmount} 空集（本月无赢单）归一为 {@code 0}（"0.00"），非 null。</li>
 *   <li>{@code monthlyLossRate} 当本月结束事件数为 0 时为 {@code null}（前端渲染 "--"）；
 *       分母 &gt; 0 而分子为 0 时为 {@code 0}（0%），非 null。</li>
 *   <li>{@code monthlyLostEventCount} / {@code monthlyEndedEventCount} 为流失率原始分子 / 分母，
 *       便于测试与前端调试；分母即「本月赢单事件数 + 本月流失事件数」。</li>
 * </ul>
 */
public record DashboardView(
    long todayNewLeadCount,
    long openSeaUnclaimedCount,
    BigDecimal monthlyWonAmount,
    BigDecimal monthlyLossRate,
    long monthlyLostEventCount,
    long monthlyEndedEventCount
) {
}
