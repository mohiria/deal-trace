package com.dealtrace.dashboard;

import com.dealtrace.common.ApiResponse;
import com.dealtrace.dashboard.dto.DashboardView;
import com.dealtrace.dashboard.service.DashboardService;
import com.dealtrace.security.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard 指标看板端点（spec dashboard / PRD §7.12）。
 *
 * <p>单一只读 {@code GET /api/dashboard}：按登录主体角色自动分流口径（Admin 全局 / Sales 个人），
 * 不接受客户端 owner / 视角参数。鉴权沿用既有 security 链（{@code anyRequest().authenticated()}）：
 * 未登录 401；Admin 与 Sales 均放行，无角色 403（design D6）。纯读，不写状态、不写系统日志。
 */
@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    public ApiResponse<DashboardView> dashboard(@AuthenticationPrincipal AccountPrincipal principal) {
        return ApiResponse.ok(dashboardService.load(principal));
    }
}
