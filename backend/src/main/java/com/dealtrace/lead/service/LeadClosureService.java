package com.dealtrace.lead.service;

import com.dealtrace.account.entity.Role;
import com.dealtrace.common.BusinessException;
import com.dealtrace.common.ErrorCode;
import com.dealtrace.contract.entity.Contract;
import com.dealtrace.contract.repository.ContractMapper;
import com.dealtrace.lead.entity.Lead;
import com.dealtrace.lead.entity.LeadStage;
import com.dealtrace.lead.entity.LoseReason;
import com.dealtrace.lead.repository.LeadMapper;
import com.dealtrace.security.AccountPrincipal;
import com.dealtrace.systemlog.SystemLogPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * 线索闭环编排（spec ADDED 标记赢单 / 标记流失）。
 *
 * <p>设计 D10：自包含实现，不依赖 LeadOwnershipService / LeadStageService 私有原语，保证既有服务零侵入。
 * 事务骨架与其它写动作同构：先 {@link LeadMapper#selectByIdForUpdate} 行锁 → 角色/归属校验 →
 * 终态只读校验 → 入参校验 → 终态写入（赢单另插 1 条合同）→ 系统日志。
 *
 * <p>错误优先级阶梯（design D3）：不存在 NOT_FOUND → SALES 非自己名下 NOT_FOUND → 已结束 LEAD_ENDED_READONLY
 * → 入参 VALIDATION_ERROR。无前置阶段要求：任意非终态阶段均可闭单。
 */
@Service
public class LeadClosureService {

    private final LeadMapper leadMapper;
    private final ContractMapper contractMapper;
    private final SystemLogPort systemLogPort;

    public LeadClosureService(LeadMapper leadMapper, ContractMapper contractMapper, SystemLogPort systemLogPort) {
        this.leadMapper = leadMapper;
        this.contractMapper = contractMapper;
        this.systemLogPort = systemLogPort;
    }

    /** POST /api/leads/{id}/win：标记赢单 + 原子生成合同记录。 */
    @Transactional
    public Lead win(Long leadId, BigDecimal contractAmount, String signedDateRaw, AccountPrincipal principal) {
        Lead lead = lockAndAuthorize(leadId, principal);
        BigDecimal amount = validateAmount(contractAmount);
        LocalDate signedDate = parseSignedDate(signedDateRaw);

        LocalDateTime now = LocalDateTime.now();
        leadMapper.updateWon(leadId, LeadStage.WON.getDbValue(), now);

        Contract contract = new Contract();
        contract.setLeadId(leadId);
        contract.setContractAmount(amount);
        contract.setSignedDate(signedDate);
        contract.setDealSalesId(lead.getOwnerSalesId()); // 赢单时刻归属；公海单为 NULL
        contract.setCreatedAt(now);
        contractMapper.insert(contract);

        lead.setStage(LeadStage.WON);
        lead.setWonAt(now);
        systemLogPort.record("LEAD_WIN", "LEAD", leadId, principal.id(),
            "标记赢单 | 合同金额=" + amount.toPlainString() + " | 签订日期=" + signedDate);
        return lead;
    }

    /** POST /api/leads/{id}/lose：标记流失。 */
    @Transactional
    public Lead lose(Long leadId, String loseReasonRaw, String loseNoteRaw, AccountPrincipal principal) {
        Lead lead = lockAndAuthorize(leadId, principal);

        LoseReason reason = LoseReason.fromDbValue(loseReasonRaw == null ? null : loseReasonRaw.strip());
        if (reason == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "流失原因非法或缺失");
        }
        String note = loseNoteRaw == null ? null : loseNoteRaw.strip();
        if (reason == LoseReason.OTHER && (note == null || note.isEmpty())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "流失原因为其他时必须填写流失说明");
        }

        LocalDateTime now = LocalDateTime.now();
        leadMapper.updateLost(leadId, LeadStage.LOST.getDbValue(), now, reason.getDbValue(), note);

        lead.setStage(LeadStage.LOST);
        lead.setLostAt(now);
        lead.setLoseReason(reason.getDbValue());
        lead.setLoseNote(note);
        systemLogPort.record("LEAD_LOSE", "LEAD", leadId, principal.id(),
            "标记流失 | 流失原因=" + reason.getDbValue() + " | 流失说明=" + (note == null ? "" : note));
        return lead;
    }

    // ---- 共用原语（自包含，不复用其它 service 私有方法）----

    /** 行锁读 + 存在性 + SALES 归属(404 不泄漏) + 终态只读。 */
    private Lead lockAndAuthorize(Long leadId, AccountPrincipal principal) {
        Lead lead = leadMapper.selectByIdForUpdate(leadId);
        if (lead == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "线索不存在");
        }
        if (principal.role() == Role.SALES
            && !Objects.equals(lead.getOwnerSalesId(), principal.id())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "线索不存在");
        }
        if (lead.getStage() != null && !lead.getStage().isActive()) {
            throw new BusinessException(ErrorCode.LEAD_ENDED_READONLY, "线索已结束，不可标记赢单或流失");
        }
        return lead;
    }

    /** 合同金额：必填、>0、最多 2 位小数（精确类型，tech-arch §9.2）。 */
    private BigDecimal validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "合同金额必填且必须大于 0");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "合同金额最多两位小数");
        }
        return amount;
    }

    /** 签订日期：必填、合法日期（不限范围）；非法 → VALIDATION_ERROR（不致 500）。 */
    private LocalDate parseSignedDate(String raw) {
        if (raw == null || raw.strip().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "签订日期必填");
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "签订日期格式非法");
        }
    }
}
