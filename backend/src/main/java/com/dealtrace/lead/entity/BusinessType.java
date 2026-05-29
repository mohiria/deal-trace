package com.dealtrace.lead.entity;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 业务类型枚举（PRD §7.3）。固定 3 项；DB 列以中文字符串持久化（与 Account.role / status 一致）。
 */
public enum BusinessType {

    BIM_CONSULTING("BIM咨询"),
    BIM_TRAINING("BIM培训"),
    CUSTOM_DEVELOPMENT("定制开发");

    @EnumValue
    private final String dbValue;

    BusinessType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }

    /**
     * 按 PRD 中文标签反向匹配；用于 DTO 入参解析。
     * 不匹配返回 null —— service 层据此抛 VALIDATION_ERROR。
     */
    public static BusinessType fromDbValue(String label) {
        if (label == null) {
            return null;
        }
        for (BusinessType t : values()) {
            if (t.dbValue.equals(label)) {
                return t;
            }
        }
        return null;
    }
}
