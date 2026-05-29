package com.dealtrace.lead;

import com.dealtrace.common.ApiResponse;
import com.dealtrace.common.BusinessException;
import com.dealtrace.common.ErrorCode;
import com.dealtrace.customer.entity.Customer;
import com.dealtrace.customer.repository.CustomerMapper;
import com.dealtrace.lead.dto.CreateLeadRequest;
import com.dealtrace.lead.dto.DuplicateCheckResponse;
import com.dealtrace.lead.dto.LeadView;
import com.dealtrace.lead.entity.BusinessType;
import com.dealtrace.lead.entity.Lead;
import com.dealtrace.lead.service.LeadDuplicateService;
import com.dealtrace.lead.service.LeadService;
import com.dealtrace.security.AccountPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 业务线索端点（design D4 / D9）。
 *
 * <p>本 controller 在 lead-core change 阶段仅提供 5 个端点；公海视图 / 认领 / 退回 /
 * Admin 三剑客 / 阶段切换 / 赢单 / 流失由后续 change 各自在本 controller 或专属 controller
 * 上扩展。
 */
@RestController
@RequestMapping("/leads")
public class LeadController {

    private final LeadService leadService;
    private final LeadDuplicateService duplicateService;
    private final CustomerMapper customerMapper;

    public LeadController(LeadService leadService,
                          LeadDuplicateService duplicateService,
                          CustomerMapper customerMapper) {
        this.leadService = leadService;
        this.duplicateService = duplicateService;
        this.customerMapper = customerMapper;
    }

    @PostMapping
    public ApiResponse<LeadView> create(
            @AuthenticationPrincipal AccountPrincipal principal,
            @RequestBody CreateLeadRequest request) {
        Lead lead = leadService.create(request, principal);
        Customer customer = customerMapper.selectById(lead.getCustomerId());
        return ApiResponse.ok(LeadView.of(lead, customer));
    }

    @GetMapping("/duplicate-check")
    public ApiResponse<DuplicateCheckResponse> duplicateCheck(
            @AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam Long customerId,
            @RequestParam String businessType) {
        BusinessType type = BusinessType.fromDbValue(businessType);
        if (type == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "业务类型非法");
        }
        int year = LocalDate.now().getYear();
        DuplicateCheckResponse resp = duplicateService.check(year, customerId, type);
        return ApiResponse.ok(resp);
    }

    @GetMapping("/mine")
    public ApiResponse<List<LeadView>> mine(@AuthenticationPrincipal AccountPrincipal principal) {
        List<Lead> rows = leadService.myLeads(principal);
        return ApiResponse.ok(toViews(rows));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<LeadView>> listAll(@AuthenticationPrincipal AccountPrincipal principal) {
        List<Lead> rows = leadService.allLeads();
        return ApiResponse.ok(toViews(rows));
    }

    @GetMapping("/{id}")
    public ApiResponse<LeadView> detail(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable Long id) {
        Lead lead = leadService.detailFor(id, principal);
        Customer customer = customerMapper.selectById(lead.getCustomerId());
        return ApiResponse.ok(LeadView.of(lead, customer));
    }

    private List<LeadView> toViews(List<Lead> rows) {
        Map<Long, Customer> customers = leadService.loadCustomers(rows);
        return rows.stream()
            .map(l -> LeadView.of(l, customers.get(l.getCustomerId())))
            .toList();
    }
}
