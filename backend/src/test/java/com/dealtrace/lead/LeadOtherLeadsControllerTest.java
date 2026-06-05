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

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 客户其他业务线索提示端点（spec customer-other-leads-hint）。
 *
 * <p>真 MySQL 集成；按唯一客户隔离（不全局清表，规避 contract 外键 / 残留污染，见
 * [[account-wipe-tests-vs-contract-fk]]）。验证 ADMIN 详情、SALES 收窄本人且不泄漏、匿名 401，
 * 以及范围谓词（排除已流失/给定类型、保留已赢单+跨年度）。
 */
@AutoConfigureMockMvc
class LeadOtherLeadsControllerTest extends IntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private LeadMapper leadMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private Account admin;
    private Account salesA;
    private Account salesB;
    private Long customerId;

    private static final String SALES_A_NAME = "销售甲-钱";
    private static final String SALES_B_NAME = "销售乙-赵";

    @BeforeEach
    void seed() {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        admin = insertAccount("oh-admin-" + sfx + "@dealtrace.test", "管理员", Role.ADMIN);
        salesA = insertAccount("oh-a-" + sfx + "@dealtrace.test", SALES_A_NAME, Role.SALES);
        salesB = insertAccount("oh-b-" + sfx + "@dealtrace.test", SALES_B_NAME, Role.SALES);

        Customer c = new Customer();
        c.setName("OtherLeads 客户 " + sfx);
        c.setUsci(uniqueUsci(sfx));
        c.setCreatedAt(LocalDateTime.now());
        customerMapper.insert(c);
        customerId = c.getId();

        short thisYear = (short) java.time.LocalDate.now().getYear();
        short lastYear = (short) (thisYear - 1);

        // 排除项：当前业务类型自身（excludeBusinessType=BIM咨询）
        insertLead(salesA.getId(), BusinessType.BIM_CONSULTING, LeadStage.UNTOUCHED, thisYear);
        // ADMIN 可见 / SALES甲 不可见：他人名下
        insertLead(salesB.getId(), BusinessType.CUSTOM_DEVELOPMENT, LeadStage.QUOTED, thisYear);
        // ADMIN 可见（公海）/ SALES甲 不可见
        insertLead(null, BusinessType.BIM_TRAINING, LeadStage.UNTOUCHED, thisYear);
        // ADMIN 与 SALES甲 都可见：本人名下进行中
        insertLead(salesA.getId(), BusinessType.BIM_TRAINING, LeadStage.CONTACTED, thisYear);
        // 排除项：已流失
        insertLead(salesA.getId(), BusinessType.CUSTOM_DEVELOPMENT, LeadStage.LOST, thisYear);
        // ADMIN 与 SALES甲 都可见：本人名下、已赢单、跨年度
        insertLead(salesA.getId(), BusinessType.CUSTOM_DEVELOPMENT, LeadStage.WON, lastYear);
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

    private void insertLead(Long ownerId, BusinessType type, LeadStage stage, short year) {
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
    }

    private static String uniqueUsci(String sfx) {
        // 18 位、字母大写；仅需满足唯一约束（mapper 直插不校验校验位）。
        String base = ("91" + sfx + "OTHERLEADS").toUpperCase();
        return (base + "000000000000000000").substring(0, 18);
    }

    @Test
    void admin_returnsOtherTypesWithOwnerAndStage_excludesLost_keepsWonAndCrossYear() throws Exception {
        String token = jwtService.generateToken(admin);
        MvcResult res = mockMvc.perform(get("/leads/customer-other-leads")
                .param("customerId", String.valueOf(customerId))
                .param("excludeBusinessType", "BIM咨询")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.length()").value(4))
            .andReturn();
        String body = res.getResponse().getContentAsString();
        // 含他人/公海/本人 的非流失其他业务线索详情
        assertThat(body).contains("定制开发").contains("BIM培训");
        assertThat(body).contains(SALES_B_NAME).contains(SALES_A_NAME).contains("公海");
        assertThat(body).contains("方案报价").contains("未触达").contains("初步沟通").contains("已赢单");
        // 排除：当前业务类型自身 + 已流失
        assertThat(body).doesNotContain("BIM咨询");
        assertThat(body).doesNotContain("已流失");
    }

    @Test
    void sales_narrowedToOwn_noLeakOfOthers() throws Exception {
        String token = jwtService.generateToken(salesA);
        MvcResult res = mockMvc.perform(get("/leads/customer-other-leads")
                .param("customerId", String.valueOf(customerId))
                .param("excludeBusinessType", "BIM咨询")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andReturn();
        String body = res.getResponse().getContentAsString();
        // 仅本人名下：BIM培训/初步沟通 + 定制开发/已赢单
        assertThat(body).contains(SALES_A_NAME).contains("BIM培训").contains("定制开发")
            .contains("初步沟通").contains("已赢单");
        // 不泄漏他人归属、公海项、已流失
        assertThat(body).doesNotContain(SALES_B_NAME);
        assertThat(body).doesNotContain("公海");
        assertThat(body).doesNotContain("已流失");
        assertThat(body).doesNotContain("方案报价"); // 他人(salesB)的阶段
    }

    @Test
    void anonymous_unauthorized() throws Exception {
        mockMvc.perform(get("/leads/customer-other-leads")
                .param("customerId", String.valueOf(customerId)))
            .andExpect(status().isUnauthorized());
    }
}
