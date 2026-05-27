package com.dealtrace.controller;

import com.dealtrace.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查端点（platform-foundation R3）。匿名可访问，仅用于探活，不接入业务数据源。
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.ok(Map.of("status", "UP"));
    }
}
