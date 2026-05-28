package com.dealtrace.account.entity;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum Role {
    ADMIN,
    SALES;

    @EnumValue
    private final String value = name();

    public String getValue() {
        return value;
    }
}
