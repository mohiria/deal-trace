package com.dealtrace.progresslog.entity;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 跟踪方式枚举（PRD §7.8）。固定 4 项；DB 列以中文字符串持久化（仿 BusinessType / LeadStage / LoseReason）。
 * method 必填（design D8）；非法 / 缺失由 service 层据 {@link #fromDbValue(String)} 返回 null 抛 VALIDATION_ERROR。
 */
public enum TrackMethod {

    PHONE("电话"),
    WECHAT("微信"),
    VISIT("拜访"),
    OTHER("其他");

    @EnumValue
    private final String dbValue;

    TrackMethod(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }

    /** 按中文标签反向匹配；不匹配返回 null —— service 层据此抛 VALIDATION_ERROR。 */
    public static TrackMethod fromDbValue(String label) {
        if (label == null) {
            return null;
        }
        for (TrackMethod m : values()) {
            if (m.dbValue.equals(label)) {
                return m;
            }
        }
        return null;
    }
}
