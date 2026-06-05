package com.dealtrace.lead;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公海列表「该客户有其他业务线索」布尔（spec customer-other-leads-hint，PRD §7.6.4）。
 *
 * <p>真 MySQL；按唯一客户隔离、按线索 id 定位本测试自造的公海行（公海返回全局前 50，
 * 新造行 created_at 最新故必在内）。验证 true（异类非流失）/ false（仅同类或仅已流失）/ 不泄漏其他线索详情。
 */
@AutoConfigureMockMvc
class LeadPoolOtherLeadsHintTest extends IntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private LeadMapper leadMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private Account salesA;
    private String otherOwnerName; // 拥有「其他业务线索」的销售，其姓名绝不应出现在公海响应里
    private Long poolWithOtherId;
    private Long poolNoOtherId;

    @BeforeEach
    void seed() {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        salesA = insertAccount("hint-a-" + sfx + "@dealtrace.test", "公海浏览者", Role.SALES);
        otherOwnerName = "异业务持有者-" + sfx;
        Account other = insertAccount("hint-o-" + sfx + "@dealtrace.test", otherOwnerName, Role.SALES);

        short year = (short) LocalDate.now().getYear();

        // 客户1：公海 BIM咨询 + 他人名下 定制开发（进行中）→ 应 true
        Long c1 = insertCustomer("HintCust1-" + sfx, sfx + "A");
        poolWithOtherId = insertLead(c1, null, BusinessType.BIM_CONSULTING, LeadStage.UNTOUCHED, year);
        insertLead(c1, other.getId(), BusinessType.CUSTOM_DEVELOPMENT, LeadStage.QUOTED, year);

        // 客户2：公海 BIM咨询 + 同类 BIM咨询(进行中) + 异类 定制开发(已流失) → 应 false
        Long c2 = insertCustomer("HintCust2-" + sfx, sfx + "B");
        poolNoOtherId = insertLead(c2, null, BusinessType.BIM_CONSULTING, LeadStage.UNTOUCHED, year);
        insertLead(c2, other.getId(), BusinessType.BIM_CONSULTING, LeadStage.QUOTED, year);
        insertLead(c2, other.getId(), BusinessType.CUSTOM_DEVELOPMENT, LeadStage.LOST, year);
    }

    private Account insertAccount(String email, String name, Role role) {
        Account a = new Account();
        a.setEmail(email);
        a.setName(name);
        a.setRole(role);
        a.setStatus(AccountStatus.ENABLED);
        a.setPasswordHash(passwordEncoder.encode("p@ssw0rd"));
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);
        return a;
    }

    private Long insertCustomer(String name, String usciSeed) {
        Customer c = new Customer();
        c.setName(name);
        c.setUsci(("91" + usciSeed + "HINT000000000000").substring(0, 18).toUpperCase());
        c.setCreatedAt(LocalDateTime.now());
        customerMapper.insert(c);
        return c.getId();
    }

    private Long insertLead(Long customerId, Long ownerId, BusinessType type, LeadStage stage, short year) {
        Lead l = new Lead();
        l.setCustomerId(customerId);
        l.setBusinessYear(year);
        l.setBusinessType(type);
        l.setContactName("联系人");
        l.setContactPhone("13812345678");
        l.setOwnerSalesId(ownerId);
        l.setStage(stage);
        l.setCreatedAt(LocalDateTime.now());
        leadMapper.insert(l);
        return l.getId();
    }

    @Test
    void poolRow_trueWhenCustomerHasOtherActiveType() throws Exception {
        String token = jwtService.generateToken(salesA);
        mockMvc.perform(get("/leads/pool").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.id == " + poolWithOtherId + ")].customerHasOtherLeads")
                .value(contains(true)));
    }

    @Test
    void poolRow_falseWhenOnlySameTypeOrLost() throws Exception {
        String token = jwtService.generateToken(salesA);
        mockMvc.perform(get("/leads/pool").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.id == " + poolNoOtherId + ")].customerHasOtherLeads")
                .value(contains(false)));
    }

    @Test
    void poolRow_noOtherLeadDetailLeak() throws Exception {
        String token = jwtService.generateToken(salesA);
        MvcResult res = mockMvc.perform(get("/leads/pool")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
        String body = res.getResponse().getContentAsString();
        // 仅布尔信号；其他业务线索的归属销售姓名绝不出现（§7.6.4/§7.6.5）
        assertThat(body).doesNotContain(otherOwnerName);
    }
}
