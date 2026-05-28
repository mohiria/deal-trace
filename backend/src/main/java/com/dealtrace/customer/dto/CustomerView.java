package com.dealtrace.customer.dto;

import com.dealtrace.customer.entity.Customer;

import java.time.LocalDateTime;

/**
 * Spec R1：对外仅暴露 4 字段，**不**含联系人 / 归属销售等 lead 字段。
 */
public record CustomerView(
    Long id,
    String name,
    String usci,
    LocalDateTime createdAt
) {
    public static CustomerView from(Customer c) {
        return new CustomerView(c.getId(), c.getName(), c.getUsci(), c.getCreatedAt());
    }
}
