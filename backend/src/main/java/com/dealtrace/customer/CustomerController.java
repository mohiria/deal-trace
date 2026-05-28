package com.dealtrace.customer;

import com.dealtrace.common.ApiResponse;
import com.dealtrace.customer.dto.CreateCustomerRequest;
import com.dealtrace.customer.dto.CustomerView;
import com.dealtrace.customer.entity.Customer;
import com.dealtrace.customer.service.CustomerService;
import com.dealtrace.security.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Customer 端点（design D8）：POST 创建、GET 搜索 / 列表。
 * 路径 {@code /customers/**} 落在 SecurityConfig 的「其余 authenticated()」分支
 * —— Admin / Sales 同等可调用，匿名 401（auth-account EntryPoint 处理）。
 */
@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    public ApiResponse<CustomerView> create(
            @AuthenticationPrincipal AccountPrincipal principal,
            @RequestBody CreateCustomerRequest request) {
        Customer created = customerService.create(request);
        return ApiResponse.ok(CustomerView.from(created));
    }

    @GetMapping
    public ApiResponse<List<CustomerView>> search(
            @AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam(required = false) String keyword) {
        List<Customer> rows = customerService.search(keyword);
        return ApiResponse.ok(rows.stream().map(CustomerView::from).toList());
    }
}
