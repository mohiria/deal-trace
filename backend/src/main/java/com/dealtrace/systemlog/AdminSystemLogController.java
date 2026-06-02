package com.dealtrace.systemlog;

import com.dealtrace.common.ApiResponse;
import com.dealtrace.systemlog.dto.SystemLogPageView;
import com.dealtrace.systemlog.service.SystemLogReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局系统日志浏览端点（view-system-log spec R2）。
 *
 * <p>路径 {@code /admin/**} 在 {@link com.dealtrace.security.SecurityConfig} 强制 {@code ROLE_ADMIN}；
 * SALES / 匿名访问由路径级守卫拒为 {@code 403 FORBIDDEN}（{@code JsonAccessDeniedHandler}），无需方法级注解。
 * 倒序分页，支持可选 {@code action} / {@code targetType} 过滤；分页 size 由 service 强制上限。
 */
@RestController
@RequestMapping("/admin/system-logs")
public class AdminSystemLogController {

    private final SystemLogReadService systemLogReadService;

    public AdminSystemLogController(SystemLogReadService systemLogReadService) {
        this.systemLogReadService = systemLogReadService;
    }

    @GetMapping
    public ApiResponse<SystemLogPageView> list(
            @RequestParam(required = false) String action,
            @RequestParam(name = "targetType", required = false) String targetType,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(systemLogReadService.listGlobal(action, targetType, page, size));
    }
}
