package com.dealtrace.lead.entity;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 流失原因枚举（PRD §7.11.2）。固定 5 项，DB 列以中文字符串持久化（仿 BusinessType / LeadStage）。
 * {@link #OTHER} 时流失说明必填（校验在 service 层）。
 */
public enum LoseReason {

    PRICE_TOO_HIGH("价格过高"),
    CHOSE_COMPETITOR("选择竞品"),
    NO_CLEAR_NEED("无明确需求"),
    UNREACHABLE("联系不上"),
    OTHER("其他");

    @EnumValue
    private final String dbValue;

    LoseReason(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }

    /** 按中文标签反向匹配；不匹配返回 null —— service 层据此抛 VALIDATION_ERROR。 */
    public static LoseReason fromDbValue(String label) {
        if (label == null) {
            return null;
        }
        for (LoseReason r : values()) {
            if (r.dbValue.equals(label)) {
                return r;
            }
        }
        return null;
    }
}
