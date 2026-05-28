package com.dealtrace.account.entity;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum AccountStatus {
    ENABLED,
    DISABLED;

    @EnumValue
    private final String value = name();

    public String getValue() {
        return value;
    }
}
