package com.dealtrace.lead.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.common.BusinessException;
import com.dealtrace.common.ErrorCode;
import com.dealtrace.customer.entity.Customer;
import com.dealtrace.customer.repository.CustomerMapper;
import com.dealtrace.lead.dto.PoolLeadView;
import com.dealtrace.lead.entity.Lead;
import com.dealtrace.lead.entity.LeadStage;
import com.dealtrace.lead.repository.LeadMapper;
import com.dealtrace.security.AccountPrincipal;
import com.dealtrace.systemlog.SystemLogPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 线索归属流转编排（spec ADDED：公海列表 / 认领 / 退回 / 分配 / 回收 / 转移）。
 *
 * <p>5 个写动作共用事务骨架（design D1）：在 {@code @Transactional} 内先
 * {@link LeadMapper#selectByIdForUpdate} 加行锁读，再依动作特定顺序校验，最后
 * {@link LeadMapper#updateOwner} + {@link SystemLogPort#record}。行锁保证并发认领仅一人成功。
 *
 * <p>系统日志 summary 用自然语言承载原 / 新归属（design D6）；退回备注仅入 summary，不落 lead 表（D5）。
 */
@Service
public class LeadOwnershipService {

    private static final int POOL_LIMIT = 50;
    private static final String POOL_LABEL = "公海";

    private final LeadMapper leadMapper;
    private final AccountMapper accountMapper;
    private final CustomerMapper customerMapper;
    private final SystemLogPort systemLogPort;

    public LeadOwnershipService(LeadMapper leadMapper,
                                AccountMapper accountMapper,
                                CustomerMapper customerMapper,
                                SystemLogPort systemLogPort) {
        this.leadMapper = leadMapper;
        this.accountMapper = accountMapper;
        this.customerMapper = customerMapper;
        this.systemLogPort = systemLogPort;
    }

    /** GET /api/leads/pool：公海未结束线索；SALES 电话脱敏、ADMIN 明文（design D7）。 */
    public List<PoolLeadView> listPool(AccountPrincipal principal) {
        List<Lead> rows = leadMapper.selectList(new QueryWrapper<Lead>()
            .isNull("owner_sales_id")
            .notIn("stage", LeadStage.WON.getDbValue(), LeadStage.LOST.getDbValue())
            .orderByDesc("created_at")
            .last("LIMIT " + POOL_LIMIT));
        Map<Long, Customer> customers = loadCustomers(rows);
        boolean admin = principal.role() == Role.ADMIN;
        return rows.stream()
            .map(l -> {
                String phone = admin ? l.getContactPhone() : PhoneMasker.mask(l.getContactPhone());
                return PoolLeadView.of(l, customers.get(l.getCustomerId()), phone);
            })
            .toList();
    }

    /** Sales 认领公海线索；并发下行锁保证仅一人成功（spec ADDED 认领 / design D1-D2）。 */
    @Transactional
    public Lead claim(Long leadId, AccountPrincipal principal) {
        Lead lead = lockOrThrow(leadId);
        ensureActive(lead);
        Account caller = accountMapper.selectById(principal.id());
        if (caller == null || caller.getStatus() != AccountStatus.ENABLED) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已停用，不可认领");
        }
        if (lead.getOwnerSalesId() != null) {
            throw new BusinessException(ErrorCode.LEAD_ALREADY_CLAIMED, "该线索已被认领");
        }
        leadMapper.updateOwner(leadId, principal.id());
        lead.setOwnerSalesId(principal.id());
        record("LEAD_CLAIM", leadId, principal.id(),
            "认领 | 原归属=" + POOL_LABEL + " | 新归属=" + ownerLabel(principal.id()));
        return lead;
    }

    /** Sales 退回自己名下未结束线索；releaseNote 必填，仅进 summary（spec ADDED 退回 / design D5）。 */
    @Transactional
    public Lead release(Long leadId, String releaseNote, AccountPrincipal principal) {
        Lead lead = lockOrThrow(leadId);
        if (!Objects.equals(lead.getOwnerSalesId(), principal.id())) {
            // 非自己名下（含公海与他人名下）统一 NOT_FOUND，不泄漏存在性
            throw new BusinessException(ErrorCode.NOT_FOUND, "线索不存在");
        }
        ensureActive(lead);
        String note = releaseNote == null ? "" : releaseNote.strip();
        if (note.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "退回公海必须填写退回备注");
        }
        Long original = lead.getOwnerSalesId();
        leadMapper.updateOwner(leadId, null);
        lead.setOwnerSalesId(null);
        record("LEAD_RELEASE", leadId, principal.id(),
            "退回公海 | 原归属=" + ownerLabel(original) + " | 新归属=" + POOL_LABEL + " | 备注=" + note);
        return lead;
    }

    /** Admin 把公海未结束线索分配给 ENABLED SALES（spec ADDED 分配）。 */
    @Transactional
    public Lead assign(Long leadId, Long salesId, AccountPrincipal principal) {
        Lead lead = lockOrThrow(leadId);
        ensureActive(lead);
        if (lead.getOwnerSalesId() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "线索已有归属，请使用转移");
        }
        Account target = resolveEnabledSales(salesId);
        leadMapper.updateOwner(leadId, target.getId());
        lead.setOwnerSalesId(target.getId());
        record("LEAD_ASSIGN", leadId, principal.id(),
            "分配 | 原归属=" + POOL_LABEL + " | 新归属=" + target.getEmail());
        return lead;
    }

    /** Admin 把私海未结束线索回收到公海（spec ADDED 回收）。 */
    @Transactional
    public Lead recall(Long leadId, AccountPrincipal principal) {
        Lead lead = lockOrThrow(leadId);
        ensureActive(lead);
        if (lead.getOwnerSalesId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "线索已在公海，无需回收");
        }
        Long original = lead.getOwnerSalesId();
        leadMapper.updateOwner(leadId, null);
        lead.setOwnerSalesId(null);
        record("LEAD_RECALL", leadId, principal.id(),
            "回收 | 原归属=" + ownerLabel(original) + " | 新归属=" + POOL_LABEL);
        return lead;
    }

    /** Admin 把私海未结束线索转移给另一 ENABLED SALES（spec ADDED 转移）。 */
    @Transactional
    public Lead transfer(Long leadId, Long salesId, AccountPrincipal principal) {
        Lead lead = lockOrThrow(leadId);
        ensureActive(lead);
        if (lead.getOwnerSalesId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "线索在公海，请使用分配");
        }
        Account target = resolveEnabledSales(salesId);
        if (Objects.equals(target.getId(), lead.getOwnerSalesId())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "目标销售与当前归属相同");
        }
        Long original = lead.getOwnerSalesId();
        leadMapper.updateOwner(leadId, target.getId());
        lead.setOwnerSalesId(target.getId());
        record("LEAD_TRANSFER", leadId, principal.id(),
            "转移 | 原归属=" + ownerLabel(original) + " | 新归属=" + target.getEmail());
        return lead;
    }

    // ---- 共用原语 ----

    private Lead lockOrThrow(Long leadId) {
        Lead lead = leadMapper.selectByIdForUpdate(leadId);
        if (lead == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "线索不存在");
        }
        return lead;
    }

    private void ensureActive(Lead lead) {
        if (lead.getStage() != null && !lead.getStage().isActive()) {
            throw new BusinessException(ErrorCode.LEAD_ENDED_READONLY, "线索已结束，不可变更归属");
        }
    }

    private Account resolveEnabledSales(Long salesId) {
        if (salesId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请指定目标销售");
        }
        Account a = accountMapper.selectById(salesId);
        if (a == null || a.getRole() != Role.SALES || a.getStatus() != AccountStatus.ENABLED) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "目标销售不存在、非销售角色或已停用");
        }
        return a;
    }

    private String ownerLabel(Long ownerId) {
        if (ownerId == null) {
            return POOL_LABEL;
        }
        Account a = accountMapper.selectById(ownerId);
        return a == null ? ("账号#" + ownerId) : a.getEmail();
    }

    private void record(String action, Long leadId, Long operatorId, String summary) {
        systemLogPort.record(action, "LEAD", leadId, operatorId, summary);
    }

    private Map<Long, Customer> loadCustomers(List<Lead> leads) {
        if (leads.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = leads.stream().map(Lead::getCustomerId).collect(Collectors.toSet());
        return customerMapper.selectBatchIds(ids).stream()
            .collect(Collectors.toMap(Customer::getId, c -> c));
    }
}
