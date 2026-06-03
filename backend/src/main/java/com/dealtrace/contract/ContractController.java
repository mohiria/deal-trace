package com.dealtrace.contract;

import com.dealtrace.common.ApiResponse;
import com.dealtrace.contract.dto.ContractPageView;
import com.dealtrace.contract.dto.ContractQuery;
import com.dealtrace.contract.service.ContractReadService;
import com.dealtrace.security.AccountPrincipal;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 合同记录浏览端点（contract-view spec R1/R2）。
 *
 * <p>挂在 {@code /contracts}（非 {@code /admin/**}）：ADMIN 与 SALES 均可访问（{@link com.dealtrace.security.SecurityConfig}
 * 对该路径仅要求已认证），匿名由认证入口拒为 {@code 401 UNAUTHORIZED}。可见范围与收窄由
 * {@link ContractReadService} 依角色裁决（SALES 仅本人）。仅 GET、无任何写方法（spec R4）。
 */
@RestController
@RequestMapping("/contracts")
public class ContractController {

    private final ContractReadService contractReadService;

    public ContractController(ContractReadService contractReadService) {
        this.contractReadService = contractReadService;
    }

    @GetMapping
    public ApiResponse<ContractPageView> list(
            @AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam(required = false) Long dealSalesId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate signedDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate signedDateTo,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        ContractQuery query = new ContractQuery(dealSalesId, signedDateFrom, signedDateTo, keyword, page, size);
        return ApiResponse.ok(contractReadService.list(query, principal));
    }
}
