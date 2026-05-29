package com.dealtrace.lead.service;

import com.dealtrace.account.entity.Role;
import com.dealtrace.common.BusinessException;
import com.dealtrace.common.ErrorCode;
import com.dealtrace.lead.entity.Lead;
import com.dealtrace.lead.entity.LeadStage;
import com.dealtrace.lead.repository.LeadMapper;
import com.dealtrace.security.AccountPrincipal;
import com.dealtrace.systemlog.SystemLogPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 线索阶段变更编排（spec ADDED 线索阶段变更）。
 *
 * <p>设计 D8：自包含实现，**不**依赖 {@link LeadOwnershipService} 的私有原语——避免为复用而放宽
 * 其成员可见性、保证归属服务字节级不变（零回归）。事务骨架与归属动作同构（先行锁后校验）：
 * {@link LeadMapper#selectByIdForUpdate} → 角色/归属校验 → 结束态只读校验 → 目标阶段解析 →
 * {@link LeadMapper#updateStage} + {@link SystemLogPort#record}。
 *
 * <p>错误优先级阶梯（design D4，决定测试断言顺序）：
 * <ol>
 *   <li>线索不存在 → NOT_FOUND</li>
 *   <li>SALES 且非自己名下（含公海、他人名下）→ NOT_FOUND（不泄漏存在性，ADMIN 跳过）</li>
 *   <li>当前阶段已结束 → LEAD_ENDED_READONLY（优先于目标非法）</li>
 *   <li>目标非法枚举 / 是结束阶段 → VALIDATION_ERROR</li>
 *   <li>目标 == 当前阶段（no-op）→ VALIDATION_ERROR</li>
 * </ol>
 */
@Service
public class LeadStageService {

    private final LeadMapper leadMapper;
    private final SystemLogPort systemLogPort;

    public LeadStageService(LeadMapper leadMapper, SystemLogPort systemLogPort) {
        this.leadMapper = leadMapper;
        this.systemLogPort = systemLogPort;
    }

    /** PATCH /api/leads/{id}/stage：在 4 个非结束阶段间任意跳转。 */
    @Transactional
    public Lead changeStage(Long leadId, String targetStageLabel, AccountPrincipal principal) {
        Lead lead = leadMapper.selectByIdForUpdate(leadId);
        // 1：不存在
        if (lead == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "线索不存在");
        }
        // 2：SALES 仅能改自己名下；公海与他人名下统一 NOT_FOUND，不泄漏存在性（ADMIN 跳过）
        if (principal.role() == Role.SALES
            && !Objects.equals(lead.getOwnerSalesId(), principal.id())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "线索不存在");
        }
        // 3：已结束只读，优先于目标合法性判断
        LeadStage current = lead.getStage();
        if (current != null && !current.isActive()) {
            throw new BusinessException(ErrorCode.LEAD_ENDED_READONLY, "线索已结束，不可修改阶段");
        }
        // 4：目标必须是合法的非结束阶段
        LeadStage target = LeadStage.fromDbValue(targetStageLabel == null ? null : targetStageLabel.strip());
        if (target == null || !target.isActive()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "目标阶段非法或不可直接切换");
        }
        // 5：no-op 拒绝
        if (target == current) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "目标阶段与当前阶段相同");
        }
        leadMapper.updateStage(leadId, target.getDbValue());
        lead.setStage(target);
        systemLogPort.record("LEAD_STAGE_CHANGE", "LEAD", leadId, principal.id(),
            "阶段变更 | 原阶段=" + current.getDbValue() + " | 新阶段=" + target.getDbValue());
        return lead;
    }
}
