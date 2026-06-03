package com.dealtrace.contract;

import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.common.IntegrationTest;
import com.dealtrace.contract.dto.ContractRow;
import com.dealtrace.contract.repository.ContractMapper;
import com.dealtrace.customer.entity.Customer;
import com.dealtrace.customer.repository.CustomerMapper;
import com.dealtrace.lead.entity.BusinessType;
import com.dealtrace.lead.entity.Lead;
import com.dealtrace.lead.entity.LeadStage;
import com.dealtrace.lead.repository.LeadMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * contract-view spec R1/R3 的 Mapper 层：JOIN customer/lead 取客户名与业务类型、LEFT JOIN account 取成交销售
 * 当前姓名，created_at 倒序，签订日期闭区间、成交销售、关键词筛选，count 与 selectPage 一致。
 *
 * <p>隔离：不 DELETE/TRUNCATE（{@code @Rollback} 回滚）；以<b>唯一客户名</b>作 keyword 在共享库上确定性断言。
 * 金额以 String 读出（{@code ResultSet.getString} 保留 DECIMAL 标度）。
 */
class ContractRecordsViewMapperTest extends IntegrationTest {

    @Autowired private ContractMapper contractMapper;
    @Autowired private AccountMapper accountMapper;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private LeadMapper leadMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String customerName;
    private String like;
    private Account s7;
    private Account s8;
    private Long leadBim;     // C1: deal_sales s7, signed 2026-05-10, created 05-10
    private Long leadTrain;   // C2: deal_sales s8, signed 2026-04-30, created 05-11
    private Long leadCustom;  // C3: 公海赢单 deal_sales NULL, signed 2026-06-01, created 05-12

    @BeforeEach
    void seed() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        customerName = "CONTRACTVIEW-" + u;
        like = "%" + customerName + "%";

        s7 = insertAccount("cv-s7-" + u + "@dealtrace.test", "销售七-" + u);
        s8 = insertAccount("cv-s8-" + u + "@dealtrace.test", "销售八-" + u);

        Long customerId = insertCustomer(customerName, usci(u));
        leadBim = insertLead(customerId, BusinessType.BIM_CONSULTING, s7.getId());
        leadTrain = insertLead(customerId, BusinessType.BIM_TRAINING, s8.getId());
        leadCustom = insertLead(customerId, BusinessType.CUSTOM_DEVELOPMENT, null);

        insertContract(leadBim, "120000.50", LocalDate.of(2026, 5, 10), s7.getId(), LocalDateTime.of(2026, 5, 10, 10, 0));
        insertContract(leadTrain, "50000.00", LocalDate.of(2026, 4, 30), s8.getId(), LocalDateTime.of(2026, 5, 11, 10, 0));
        insertContract(leadCustom, "80000.00", LocalDate.of(2026, 6, 1), null, LocalDateTime.of(2026, 5, 12, 10, 0));
    }

    @Test
    void selectPage_byKeyword_descWithJoinedFields() {
        List<ContractRow> rows = contractMapper.selectPage(null, null, null, like, 20, 0);

        assertThat(rows).hasSize(3);
        // created_at 倒序：C3(05-12) → C2(05-11) → C1(05-10)
        assertThat(rows.get(0).getLeadId()).isEqualTo(leadCustom);
        assertThat(rows.get(1).getLeadId()).isEqualTo(leadTrain);
        assertThat(rows.get(2).getLeadId()).isEqualTo(leadBim);

        ContractRow custom = rows.get(0);
        assertThat(custom.getCustomerName()).isEqualTo(customerName);
        assertThat(custom.getBusinessType()).isEqualTo("定制开发");
        assertThat(custom.getDealSalesId()).isNull();
        assertThat(custom.getDealSalesName()).isNull();       // 公海赢单：service 再组装为"公海赢单"

        ContractRow bim = rows.get(2);
        assertThat(bim.getBusinessType()).isEqualTo("BIM咨询");
        assertThat(bim.getContractAmount()).isEqualTo("120000.50");  // DECIMAL 标度保留
        assertThat(bim.getDealSalesId()).isEqualTo(s7.getId());
        assertThat(bim.getDealSalesName()).isEqualTo(s7.getName());  // 当前姓名
    }

    @Test
    void selectPage_filterByDealSales() {
        List<ContractRow> rows = contractMapper.selectPage(s7.getId(), null, null, like, 20, 0);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getLeadId()).isEqualTo(leadBim);
    }

    @Test
    void selectPage_filterBySignedDateClosedInterval() {
        // [2026-05-01, 2026-05-31]：仅 C1(05-10)；C2(04-30) 与 C3(06-01) 在区间外
        List<ContractRow> rows = contractMapper.selectPage(
            null, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), like, 20, 0);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getLeadId()).isEqualTo(leadBim);
    }

    @Test
    void countPage_matchesFilters() {
        assertThat(contractMapper.countPage(null, null, null, like)).isEqualTo(3);
        assertThat(contractMapper.countPage(s7.getId(), null, null, like)).isEqualTo(1);
        assertThat(contractMapper.countPage(
            null, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), like)).isEqualTo(1);
    }

    // ---- 造数辅助 ----

    private Account insertAccount(String email, String name) {
        Account a = new Account();
        a.setEmail(email);
        a.setName(name);
        a.setRole(Role.SALES);
        a.setStatus(AccountStatus.ENABLED);
        a.setPasswordHash(passwordEncoder.encode("p@ssw0rd"));
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);
        return a;
    }

    private Long insertCustomer(String name, String usci) {
        Customer c = new Customer();
        c.setName(name);
        c.setUsci(usci);
        c.setCreatedAt(LocalDateTime.now());
        customerMapper.insert(c);
        return c.getId();
    }

    private Long insertLead(Long customerId, BusinessType type, Long ownerId) {
        Lead l = new Lead();
        l.setCustomerId(customerId);
        l.setBusinessYear((short) 2026);
        l.setBusinessType(type);
        l.setContactName("联系人");
        l.setContactPhone("13800000000");
        l.setOwnerSalesId(ownerId);
        l.setStage(LeadStage.WON);
        l.setCreatedAt(LocalDateTime.now());
        leadMapper.insert(l);
        return l.getId();
    }

    private void insertContract(Long leadId, String amount, LocalDate signedDate, Long dealSalesId, LocalDateTime createdAt) {
        jdbcTemplate.update(
            "INSERT INTO contract (lead_id, contract_amount, signed_date, deal_sales_id, created_at) VALUES (?, ?, ?, ?, ?)",
            leadId, new BigDecimal(amount), signedDate, dealSalesId, createdAt);
    }

    /** 造一个 18 位、首字符随唯一片确定的 USCI（仅用于满足列约束，不参与本测试断言）。 */
    private static String usci(String u) {
        String base = ("91110000" + u + "000000000000").toUpperCase();
        return base.substring(0, 18);
    }
}
