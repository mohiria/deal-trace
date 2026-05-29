package com.dealtrace.lead.service;

import com.dealtrace.common.ErrorCode;
import com.dealtrace.common.IntegrationTest;
import com.dealtrace.customer.entity.Customer;
import com.dealtrace.customer.repository.CustomerMapper;
import com.dealtrace.lead.dto.DuplicateCheckResponse;
import com.dealtrace.lead.entity.BusinessType;
import com.dealtrace.lead.entity.Lead;
import com.dealtrace.lead.entity.LeadStage;
import com.dealtrace.lead.repository.LeadMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec R5 三态决策表 + 非重复路径（IntegrationTest 单事务自动回滚）。
 */
class LeadDuplicateServiceTest extends IntegrationTest {

    private static final String VALID_USCI = "91110000123456789Q";

    @Autowired private LeadDuplicateService duplicateService;
    @Autowired private LeadMapper leadMapper;
    @Autowired private CustomerMapper customerMapper;

    private Long customerId;

    @BeforeEach
    void seedCustomer() {
        // 清除既有 customer 行（含旧 session 留下的 smoke 数据），避免 USCI UNIQUE 冲突；
        // 单事务 + @Rollback 保证清理只在本测试方法事务内生效。
        customerMapper.delete(null);
        Customer c = new Customer();
        c.setName("Dup Test Customer");
        c.setUsci(VALID_USCI);
        c.setCreatedAt(LocalDateTime.now());
        customerMapper.insert(c);
        customerId = c.getId();
    }

    private void insertLead(int year, BusinessType type, LeadStage stage) {
        Lead l = new Lead();
        l.setCustomerId(customerId);
        l.setBusinessYear((short) year);
        l.setBusinessType(type);
        l.setContactName("X");
        l.setContactPhone("13800000000");
        l.setStage(stage);
        l.setCreatedAt(LocalDateTime.now());
        if (stage == LeadStage.LOST) {
            l.setLostAt(LocalDateTime.now());
            l.setLoseReason("价格过高");
        }
        leadMapper.insert(l);
    }

    @Test
    void check_emptyTriplet_allows() {
        DuplicateCheckResponse r = duplicateService.check(2026, customerId, BusinessType.BIM_CONSULTING);
        assertThat(r.canCreate()).isTrue();
        assertThat(r.blockingReason()).isNull();
        assertThat(r.historicalLost()).isEmpty();
    }

    @Test
    void check_activeExists_blocksWithActiveCode() {
        insertLead(2026, BusinessType.BIM_CONSULTING, LeadStage.QUOTED);
        DuplicateCheckResponse r = duplicateService.check(2026, customerId, BusinessType.BIM_CONSULTING);
        assertThat(r.canCreate()).isFalse();
        assertThat(r.blockingReason()).isEqualTo(ErrorCode.DUPLICATE_ACTIVE_LEAD.name());
    }

    @Test
    void check_wonExists_blocksWithWonCode() {
        insertLead(2026, BusinessType.BIM_CONSULTING, LeadStage.WON);
        DuplicateCheckResponse r = duplicateService.check(2026, customerId, BusinessType.BIM_CONSULTING);
        assertThat(r.canCreate()).isFalse();
        assertThat(r.blockingReason()).isEqualTo(ErrorCode.DUPLICATE_WON_LEAD.name());
    }

    @Test
    void check_onlyLost_allowsAndExposesHistory() {
        insertLead(2026, BusinessType.BIM_CONSULTING, LeadStage.LOST);
        insertLead(2026, BusinessType.BIM_CONSULTING, LeadStage.LOST);
        DuplicateCheckResponse r = duplicateService.check(2026, customerId, BusinessType.BIM_CONSULTING);
        assertThat(r.canCreate()).isTrue();
        assertThat(r.blockingReason()).isNull();
        assertThat(r.historicalLost()).hasSize(2);
        assertThat(r.historicalLost().get(0).loseReason()).isEqualTo("价格过高");
    }

    @Test
    void check_activeAndWonBoth_blocksAtLeastOne() {
        insertLead(2026, BusinessType.BIM_CONSULTING, LeadStage.QUOTED);
        insertLead(2026, BusinessType.BIM_CONSULTING, LeadStage.WON);
        DuplicateCheckResponse r = duplicateService.check(2026, customerId, BusinessType.BIM_CONSULTING);
        assertThat(r.canCreate()).isFalse();
        assertThat(r.blockingReason())
            .isIn(ErrorCode.DUPLICATE_ACTIVE_LEAD.name(), ErrorCode.DUPLICATE_WON_LEAD.name());
    }

    @Test
    void check_differentYear_notDuplicate() {
        insertLead(2025, BusinessType.BIM_CONSULTING, LeadStage.QUOTED);
        DuplicateCheckResponse r = duplicateService.check(2026, customerId, BusinessType.BIM_CONSULTING);
        assertThat(r.canCreate()).isTrue();
    }

    @Test
    void check_differentBusinessType_notDuplicate() {
        insertLead(2026, BusinessType.BIM_CONSULTING, LeadStage.QUOTED);
        DuplicateCheckResponse r = duplicateService.check(2026, customerId, BusinessType.BIM_TRAINING);
        assertThat(r.canCreate()).isTrue();
    }
}
