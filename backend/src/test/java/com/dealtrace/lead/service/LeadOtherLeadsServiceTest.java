package com.dealtrace.lead.service;

import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.lead.dto.LeadOtherLeadView;
import com.dealtrace.lead.entity.BusinessType;
import com.dealtrace.lead.entity.Lead;
import com.dealtrace.lead.entity.LeadStage;
import com.dealtrace.lead.repository.LeadMapper;
import com.dealtrace.security.AccountPrincipal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 客户其他业务线索提示——角色裁剪 / 映射单元测试（spec customer-other-leads-hint，PRD §7.6.3/§7.6.5/§7.6.6）。
 *
 * <p>纯单元（Mockito，无 DB）：mock 出「已按范围查询的其他线索」，断言 ADMIN 全量映射（类型/归属/阶段），
 * SALES 仅保留本人名下、不泄漏他人/公海线索。SQL 范围谓词（排除已流失/同类/跨年度）由集成测试覆盖。
 */
class LeadOtherLeadsServiceTest {

    private final LeadMapper leadMapper = mock(LeadMapper.class);
    private final AccountMapper accountMapper = mock(AccountMapper.class);

    private final LeadOtherLeadsService service =
        new LeadOtherLeadsService(leadMapper, accountMapper);

    private static Account account(long id, String name) {
        Account a = new Account();
        a.setId(id);
        a.setName(name);
        return a;
    }

    private static Lead lead(Long ownerId, BusinessType type, LeadStage stage) {
        Lead l = new Lead();
        l.setCustomerId(100L);
        l.setOwnerSalesId(ownerId);
        l.setBusinessType(type);
        l.setStage(stage);
        return l;
    }

    @Test
    void admin_seesOtherTypesWithOwnerAndStage() {
        when(leadMapper.selectList(any())).thenReturn(List.of(
            lead(10L, BusinessType.CUSTOM_DEVELOPMENT, LeadStage.QUOTED),
            lead(null, BusinessType.BIM_TRAINING, LeadStage.UNTOUCHED)
        ));
        when(accountMapper.selectById(10L)).thenReturn(account(10L, "张三"));

        AccountPrincipal admin = new AccountPrincipal(1L, "admin@dealtrace.test", Role.ADMIN);
        List<LeadOtherLeadView> result =
            service.otherLeadsFor(100L, BusinessType.BIM_CONSULTING, admin);

        assertThat(result).containsExactlyInAnyOrder(
            new LeadOtherLeadView("定制开发", "张三", "方案报价"),
            new LeadOtherLeadView("BIM培训", "公海", "未触达"));
    }

    @Test
    void sales_seesOnlyOwnOtherLeads() {
        when(leadMapper.selectList(any())).thenReturn(List.of(
            lead(7L, BusinessType.BIM_TRAINING, LeadStage.CONTACTED),
            lead(9L, BusinessType.CUSTOM_DEVELOPMENT, LeadStage.QUOTED),
            lead(null, BusinessType.BIM_CONSULTING, LeadStage.UNTOUCHED)
        ));
        when(accountMapper.selectById(7L)).thenReturn(account(7L, "本人销售"));

        AccountPrincipal sales = new AccountPrincipal(7L, "me@dealtrace.test", Role.SALES);
        List<LeadOtherLeadView> result =
            service.otherLeadsFor(100L, null, sales);

        // 仅本人名下「BIM培训/初步沟通」，不含他人(定制开发)与公海(BIM咨询)
        assertThat(result).containsExactly(
            new LeadOtherLeadView("BIM培训", "本人销售", "初步沟通"));
        assertThat(result).noneMatch(v -> "定制开发".equals(v.businessType()));
        assertThat(result).noneMatch(v -> "公海".equals(v.ownerSalesName()));
    }
}
