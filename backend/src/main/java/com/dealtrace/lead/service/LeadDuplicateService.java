package com.dealtrace.lead.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dealtrace.common.ErrorCode;
import com.dealtrace.lead.dto.DuplicateCheckResponse;
import com.dealtrace.lead.dto.DuplicateCheckResponse.HistoricalLost;
import com.dealtrace.lead.entity.BusinessType;
import com.dealtrace.lead.entity.Lead;
import com.dealtrace.lead.entity.LeadStage;
import com.dealtrace.lead.repository.LeadMapper;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 业务线索查重三元组三态判断（spec R5 / R6 + design D6）。
 *
 * <p>三元组 = (businessYear, customerId, businessType)。状态决策表：
 * <pre>
 *  既有 stage 集合          决策
 *  ────────────────────────────────────────
 *  ∅                       允许（canCreate=true, blockingReason=null）
 *  全 LOST                 允许 + 提示历史流失
 *  含任一 active            拒：DUPLICATE_ACTIVE_LEAD
 *  含 WON                  拒：DUPLICATE_WON_LEAD
 * </pre>
 *
 * <p>并发场景（两调用者同时通过 check then insert）罕见允许双行进行中 —— spec R5
 * 显式接受。
 */
@Service
public class LeadDuplicateService {

    private final LeadMapper leadMapper;

    public LeadDuplicateService(LeadMapper leadMapper) {
        this.leadMapper = leadMapper;
    }

    /**
     * 业务查重 + 预检统一入口。返回带 historicalLost 的完整响应（创建端点丢弃 hist 数组，
     * 仅取 canCreate / blockingReason 用于阻塞判断）。
     */
    public DuplicateCheckResponse check(int businessYear, Long customerId, BusinessType type) {
        List<Lead> existing = leadMapper.selectList(new QueryWrapper<Lead>()
            .eq("business_year", businessYear)
            .eq("customer_id", customerId)
            .eq("business_type", type.getDbValue()));

        boolean hasActive = existing.stream().anyMatch(l -> l.getStage() != null && l.getStage().isActive());
        boolean hasWon = existing.stream().anyMatch(l -> l.getStage() == LeadStage.WON);

        String blockingReason = null;
        if (hasActive) {
            blockingReason = ErrorCode.DUPLICATE_ACTIVE_LEAD.name();
        } else if (hasWon) {
            blockingReason = ErrorCode.DUPLICATE_WON_LEAD.name();
        }

        List<HistoricalLost> hist = existing.stream()
            .filter(l -> l.getStage() == LeadStage.LOST)
            .sorted(Comparator.comparing(Lead::getLostAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .map(l -> new HistoricalLost(l.getLostAt(), l.getLoseReason(), l.getLoseNote()))
            .toList();

        return new DuplicateCheckResponse(blockingReason == null, blockingReason, hist);
    }
}
