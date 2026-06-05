package com.dealtrace.lead.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.lead.dto.LeadOtherLeadView;
import com.dealtrace.lead.entity.BusinessType;
import com.dealtrace.lead.entity.Lead;
import com.dealtrace.lead.entity.LeadStage;
import com.dealtrace.lead.repository.LeadMapper;
import com.dealtrace.security.AccountPrincipal;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 客户其他业务线索提示（spec ADDED customer-other-leads-hint）。
 *
 * <p>返回「同一客户、与给定业务类型不同、非已流失」的其他业务线索摘要，按调用者角色裁剪：
 * <ul>
 *   <li>ADMIN：该客户全部其他业务线索（design D2）</li>
 *   <li>SALES：仅 {@code owner_sales_id == 调用者} 的其他业务线索（§7.6.5/§7.6.6）</li>
 * </ul>
 *
 * <p>范围限定（design D3）：{@code customer_id = ? AND business_type <> ? AND stage <> 已流失}，
 * 跨自然年度，保留进行中四阶段 + 已赢单。不阻断任何写操作。
 */
@Service
public class LeadOtherLeadsService {

    private final LeadMapper leadMapper;
    private final AccountMapper accountMapper;

    public LeadOtherLeadsService(LeadMapper leadMapper, AccountMapper accountMapper) {
        this.leadMapper = leadMapper;
        this.accountMapper = accountMapper;
    }

    /**
     * 查同客户其他业务类型（非已流失）线索，按角色裁剪。
     *
     * @param customerId          目标客户
     * @param excludeBusinessType 排除的业务类型（通常为当前线索/新建线索的类型）；可为 null（不排除）
     * @param principal           调用者
     */
    private static final String POOL_LABEL = "公海";

    public List<LeadOtherLeadView> otherLeadsFor(Long customerId, BusinessType excludeBusinessType,
                                                 AccountPrincipal principal) {
        if (customerId == null || principal == null) {
            return List.of();
        }
        // 范围限定（design D3）：同客户 + 非已流失；可选排除给定业务类型。跨年度、保留进行中+已赢单。
        QueryWrapper<Lead> qw = new QueryWrapper<Lead>()
            .eq("customer_id", customerId)
            .ne("stage", LeadStage.LOST.getDbValue());
        if (excludeBusinessType != null) {
            qw.ne("business_type", excludeBusinessType.getDbValue());
        }
        List<Lead> rows = leadMapper.selectList(qw);

        // 角色裁剪（design D2）：SALES 仅保留本人名下（§7.6.5/§7.6.6）；ADMIN 全量。
        boolean sales = principal.role() == Role.SALES;
        return rows.stream()
            .filter(l -> !sales || Objects.equals(l.getOwnerSalesId(), principal.id()))
            .map(this::toView)
            .toList();
    }

    private LeadOtherLeadView toView(Lead l) {
        BusinessType type = l.getBusinessType();
        LeadStage stage = l.getStage();
        return new LeadOtherLeadView(
            type == null ? null : type.getDbValue(),
            ownerLabel(l.getOwnerSalesId()),
            stage == null ? null : stage.getDbValue());
    }

    /** 归属销售姓名；公海（owner 为空）显示「公海」，账号缺失（停用/删号）回退「公海」。 */
    private String ownerLabel(Long ownerSalesId) {
        if (ownerSalesId == null) {
            return POOL_LABEL;
        }
        Account a = accountMapper.selectById(ownerSalesId);
        return a == null ? POOL_LABEL : a.getName();
    }
}
