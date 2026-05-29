package com.dealtrace.lead.entity;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 线索阶段枚举（PRD §7.7）。前 4 项为非结束阶段（active），后 2 项为结束阶段。
 */
public enum LeadStage {

    UNTOUCHED("未触达", true),
    CONTACTED("初步沟通", true),
    QUOTED("方案报价", true),
    NEGOTIATING("商务谈判", true),
    WON("已赢单", false),
    LOST("已流失", false);

    @EnumValue
    private final String dbValue;
    private final boolean active;

    LeadStage(String dbValue, boolean active) {
        this.dbValue = dbValue;
        this.active = active;
    }

    public String getDbValue() {
        return dbValue;
    }

    /** active = 未触达 / 初步沟通 / 方案报价 / 商务谈判（PRD §8.2.2）。 */
    public boolean isActive() {
        return active;
    }

    public static LeadStage fromDbValue(String label) {
        if (label == null) {
            return null;
        }
        for (LeadStage s : values()) {
            if (s.dbValue.equals(label)) {
                return s;
            }
        }
        return null;
    }
}
