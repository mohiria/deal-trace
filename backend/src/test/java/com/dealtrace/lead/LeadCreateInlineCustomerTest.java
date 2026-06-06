package com.dealtrace.lead;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.auth.JwtService;
import com.dealtrace.common.IntegrationTest;
import com.dealtrace.customer.entity.Customer;
import com.dealtrace.customer.repository.CustomerMapper;
import com.dealtrace.lead.entity.BusinessType;
import com.dealtrace.lead.entity.Lead;
import com.dealtrace.lead.entity.LeadStage;
import com.dealtrace.lead.repository.LeadMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * find-or-create 合并端点（spec：内联建客户）。
 *
 * <p>{@code POST /leads} 接受 {@code customerId | newCustomer{name,usci}}（恰择其一）。
 * newCustomer 走事务内 USCI 仲裁：未命中→建客户+线索；命中同名→复用；命中异名→DUPLICATE_CUSTOMER；
 * 字段校验失败（USCI 非法 / 电话非法 / 两者同缺同提供）→ VALIDATION_ERROR 且无孤儿客户。
 */
@AutoConfigureMockMvc
class LeadCreateInlineCustomerTest extends IntegrationTest {

    private static final String EXISTING_USCI = "91110000123456789Q";

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private LeadMapper leadMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private Account sales;
    private Customer existingCustomer;

    @BeforeEach
    void seed() {
        accountMapper.delete(null);
        customerMapper.delete(null);

        sales = insertAccount("inline-sales@dealtrace.test", "Sales", Role.SALES, AccountStatus.ENABLED);

        Customer c = new Customer();
        c.setName("既有客户甲");
        c.setUsci(EXISTING_USCI);
        c.setCreatedAt(LocalDateTime.now());
        customerMapper.insert(c);
        existingCustomer = c;
    }

    private Account insertAccount(String email, String name, Role role, AccountStatus status) {
        Account a = new Account();
        a.setEmail(email);
        a.setName(name);
        a.setRole(role);
        a.setStatus(status);
        a.setPasswordHash(passwordEncoder.encode("p@ssw0rd"));
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);
        return a;
    }

    /** 一个未被占用的合法 18 位 USCI（与既有客户不同，校验位经 GB32100 算法计算）。 */
    private String freshValidUsci() {
        return "91310000MA1234567N";
    }

    private void perform(String body, String token, org.springframework.test.web.servlet.ResultMatcher... matchers) throws Exception {
        var rb = post("/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
        if (token != null) {
            rb = rb.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        var actions = mockMvc.perform(rb);
        for (var m : matchers) actions = actions.andExpect(m);
    }

    private String leadWithNewCustomer(String name, String usci, String contactPhone) {
        return "{"
            + "\"newCustomer\":{\"name\":\"" + name + "\",\"usci\":\"" + usci + "\"},"
            + "\"businessType\":\"BIM咨询\","
            + "\"contactName\":\"王工\","
            + "\"contactPhone\":\"" + contactPhone + "\""
            + "}";
    }

    @Test
    void newCustomer_usciNotFound_createsCustomerAndLead() throws Exception {
        String fresh = freshValidUsci();
        long customersBefore = customerMapper.selectCount(null);
        String token = jwtService.generateToken(sales);

        perform(leadWithNewCustomer("全新客户乙", fresh, "13812345678"), token,
            status().isOk(),
            jsonPath("$.code").value("SUCCESS"),
            jsonPath("$.data.customerName").value("全新客户乙"));

        assertThat(customerMapper.selectCount(null)).isEqualTo(customersBefore + 1);
        Customer created = customerMapper.selectOne(new QueryWrapper<Customer>().eq("usci", fresh));
        assertThat(created).isNotNull();
        Lead lead = leadMapper.selectOne(new QueryWrapper<Lead>().eq("customer_id", created.getId()));
        assertThat(lead).isNotNull();
        assertThat(lead.getOwnerSalesId()).isEqualTo(sales.getId());
    }

    @Test
    void newCustomer_usciFoundSameName_reusesCustomer() throws Exception {
        long customersBefore = customerMapper.selectCount(null);
        String token = jwtService.generateToken(sales);

        perform(leadWithNewCustomer("既有客户甲", EXISTING_USCI, "13812345678"), token,
            status().isOk(),
            jsonPath("$.code").value("SUCCESS"));

        assertThat(customerMapper.selectCount(null)).isEqualTo(customersBefore);
        Lead lead = leadMapper.selectOne(new QueryWrapper<Lead>().eq("customer_id", existingCustomer.getId()));
        assertThat(lead).isNotNull();
    }

    @Test
    void newCustomer_usciFoundDifferentName_rejectedDuplicate() throws Exception {
        long customersBefore = customerMapper.selectCount(null);
        String token = jwtService.generateToken(sales);

        perform(leadWithNewCustomer("名称不一致的客户", EXISTING_USCI, "13812345678"), token,
            status().isBadRequest(),
            jsonPath("$.code").value("DUPLICATE_CUSTOMER"));

        assertThat(customerMapper.selectCount(null)).isEqualTo(customersBefore);
        assertThat(leadMapper.selectCount(null)).isZero();
    }

    @Test
    void newCustomer_invalidUsci_rejected() throws Exception {
        long customersBefore = customerMapper.selectCount(null);
        String token = jwtService.generateToken(sales);

        perform(leadWithNewCustomer("非法码客户", "NOT-A-VALID-USCI", "13812345678"), token,
            status().isBadRequest(),
            jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(customerMapper.selectCount(null)).isEqualTo(customersBefore);
        assertThat(leadMapper.selectCount(null)).isZero();
    }

    @Test
    void invalidPhone_withNewCustomer_rejected_noOrphanCustomer() throws Exception {
        String fresh = freshValidUsci();
        long customersBefore = customerMapper.selectCount(null);
        String token = jwtService.generateToken(sales);

        perform(leadWithNewCustomer("不应被创建的客户", fresh, "abc"), token,
            status().isBadRequest(),
            jsonPath("$.code").value("VALIDATION_ERROR"));

        // 电话校验在 find-or-create 之前，孤儿客户不应产生
        assertThat(customerMapper.selectCount(null)).isEqualTo(customersBefore);
        assertThat(customerMapper.selectOne(new QueryWrapper<Customer>().eq("usci", fresh))).isNull();
        assertThat(leadMapper.selectCount(null)).isZero();
    }

    @Test
    void bothCustomerIdAndNewCustomer_rejected() throws Exception {
        String fresh = freshValidUsci();
        String token = jwtService.generateToken(sales);
        String body = "{"
            + "\"customerId\":" + existingCustomer.getId() + ","
            + "\"newCustomer\":{\"name\":\"冲突客户\",\"usci\":\"" + fresh + "\"},"
            + "\"businessType\":\"BIM咨询\","
            + "\"contactName\":\"王工\","
            + "\"contactPhone\":\"13812345678\""
            + "}";

        perform(body, token,
            status().isBadRequest(),
            jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(leadMapper.selectCount(null)).isZero();
    }

    @Test
    void neitherCustomerIdNorNewCustomer_rejected() throws Exception {
        String token = jwtService.generateToken(sales);
        String body = "{"
            + "\"businessType\":\"BIM咨询\","
            + "\"contactName\":\"王工\","
            + "\"contactPhone\":\"13812345678\""
            + "}";

        perform(body, token,
            status().isBadRequest(),
            jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void newCustomer_reuse_thenDuplicateActiveLead_rejected_noNewCustomer() throws Exception {
        // 既有客户甲已有本年度 BIM咨询 进行中线索；用同名同 USCI 内联建会复用客户并被查重拦截。
        Lead existing = new Lead();
        existing.setCustomerId(existingCustomer.getId());
        existing.setBusinessYear((short) LocalDate.now().getYear());
        existing.setBusinessType(BusinessType.BIM_CONSULTING);
        existing.setContactName("X");
        existing.setContactPhone("13800000000");
        existing.setStage(LeadStage.QUOTED);
        existing.setCreatedAt(LocalDateTime.now());
        leadMapper.insert(existing);

        long customersBefore = customerMapper.selectCount(null);
        String token = jwtService.generateToken(sales);

        perform(leadWithNewCustomer("既有客户甲", EXISTING_USCI, "13812345678"), token,
            status().isBadRequest(),
            jsonPath("$.code").value("DUPLICATE_ACTIVE_LEAD"));

        assertThat(customerMapper.selectCount(null)).isEqualTo(customersBefore);
    }
}
