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
import com.dealtrace.lead.entity.Lead;
import com.dealtrace.lead.entity.LeadStage;
import com.dealtrace.lead.repository.LeadMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec R2-R5 + R8（创建路径 + LEAD_CREATE 日志）。
 */
@AutoConfigureMockMvc
class LeadControllerCreateTest extends IntegrationTest {

    private static final String VALID_USCI = "91110000123456789Q";

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private LeadMapper leadMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Account admin;
    private Account sales;
    private Account otherSales;
    private Account disabledSales;
    private Long customerId;

    @BeforeEach
    void seed() {
        accountMapper.delete(null);
        customerMapper.delete(null);

        admin = insertAccount("lead-create-admin@dealtrace.test", "Admin", Role.ADMIN, AccountStatus.ENABLED);
        sales = insertAccount("lead-create-sales@dealtrace.test", "Sales", Role.SALES, AccountStatus.ENABLED);
        otherSales = insertAccount("lead-create-other@dealtrace.test", "Other", Role.SALES, AccountStatus.ENABLED);
        disabledSales = insertAccount("lead-create-disabled@dealtrace.test", "Disabled", Role.SALES, AccountStatus.DISABLED);

        Customer c = new Customer();
        c.setName("Lead Create Test Customer");
        c.setUsci(VALID_USCI);
        c.setCreatedAt(LocalDateTime.now());
        customerMapper.insert(c);
        customerId = c.getId();
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

    private String body(Object... kvPairs) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kvPairs.length; i += 2) {
            if (i > 0) sb.append(",");
            String key = (String) kvPairs[i];
            Object val = kvPairs[i + 1];
            sb.append("\"").append(key).append("\":");
            if (val == null) sb.append("null");
            else if (val instanceof String) sb.append("\"").append(val).append("\"");
            else sb.append(val.toString());
        }
        return sb.append("}").toString();
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

    // ---- spec R5: Sales 默认归自己 ----
    @Test
    void sales_createsLead_defaultsToSelf_andStageUntouched() throws Exception {
        String token = jwtService.generateToken(sales);
        perform(body(
                "customerId", customerId,
                "businessType", "BIM咨询",
                "contactName", "Tom",
                "contactPhone", "13812345678"
            ), token,
            status().isOk(),
            jsonPath("$.code").value("SUCCESS"),
            jsonPath("$.data.ownerSalesId").value(sales.getId()),
            jsonPath("$.data.stage").value("未触达"),
            jsonPath("$.data.businessYear").value(LocalDate.now().getYear()));
    }

    // ---- spec R4: Sales 显式入池 ----
    @Test
    void sales_explicitlyAssignsToPool() throws Exception {
        String token = jwtService.generateToken(sales);
        perform(body(
                "customerId", customerId,
                "businessType", "BIM咨询",
                "contactName", "Tom",
                "contactPhone", "13812345678",
                "assignToPool", true
            ), token,
            status().isOk(),
            jsonPath("$.code").value("SUCCESS"),
            jsonPath("$.data.ownerSalesId").doesNotExist()
        );

        Lead l = leadMapper.selectOne(new QueryWrapper<Lead>().eq("customer_id", customerId));
        assertThat(l.getOwnerSalesId()).isNull();
    }

    // ---- spec R4: Sales 试图指定他人 → 被忽略，归自己 ----
    @Test
    void sales_tryingToAssignToOther_isIgnored_ownerBecomesSelf() throws Exception {
        String token = jwtService.generateToken(sales);
        perform(body(
                "customerId", customerId,
                "businessType", "BIM咨询",
                "contactName", "Tom",
                "contactPhone", "13812345678",
                "ownerSalesId", otherSales.getId()
            ), token,
            status().isOk(),
            jsonPath("$.data.ownerSalesId").value(sales.getId()) // 归 self，不是 other
        );
    }

    // ---- spec R4: Admin 指定 ENABLED Sales ----
    @Test
    void admin_assignsToEnabledSales() throws Exception {
        String token = jwtService.generateToken(admin);
        perform(body(
                "customerId", customerId,
                "businessType", "BIM咨询",
                "contactName", "Tom",
                "contactPhone", "13812345678",
                "ownerSalesId", sales.getId()
            ), token,
            status().isOk(),
            jsonPath("$.data.ownerSalesId").value(sales.getId())
        );
    }

    // ---- spec R4: Admin 指定 DISABLED Sales → 拒 ----
    @Test
    void admin_assignsToDisabledSales_rejected() throws Exception {
        String token = jwtService.generateToken(admin);
        perform(body(
                "customerId", customerId,
                "businessType", "BIM咨询",
                "contactName", "Tom",
                "contactPhone", "13812345678",
                "ownerSalesId", disabledSales.getId()
            ), token,
            status().isBadRequest(),
            jsonPath("$.code").value("VALIDATION_ERROR")
        );
        assertThat(leadMapper.selectCount(null)).isZero();
    }

    // ---- spec R4: Admin 不指定 → 公海 ----
    @Test
    void admin_noOwner_goesToPool() throws Exception {
        String token = jwtService.generateToken(admin);
        perform(body(
                "customerId", customerId,
                "businessType", "BIM咨询",
                "contactName", "Tom",
                "contactPhone", "13812345678"
            ), token,
            status().isOk(),
            jsonPath("$.data.ownerSalesId").doesNotExist()
        );
    }

    // ---- spec R3: 缺 customerId ----
    @Test
    void missingCustomerId_rejected() throws Exception {
        String token = jwtService.generateToken(sales);
        perform(body(
                "businessType", "BIM咨询",
                "contactName", "Tom",
                "contactPhone", "13812345678"
            ), token,
            status().isBadRequest(),
            jsonPath("$.code").value("VALIDATION_ERROR")
        );
    }

    // ---- spec R3: customerId 不存在 ----
    @Test
    void nonExistentCustomerId_rejected() throws Exception {
        String token = jwtService.generateToken(sales);
        perform(body(
                "customerId", 999999L,
                "businessType", "BIM咨询",
                "contactName", "Tom",
                "contactPhone", "13812345678"
            ), token,
            status().isBadRequest(),
            jsonPath("$.code").value("VALIDATION_ERROR")
        );
    }

    // ---- spec R3: 非法 businessType ----
    @Test
    void invalidBusinessType_rejected() throws Exception {
        String token = jwtService.generateToken(sales);
        perform(body(
                "customerId", customerId,
                "businessType", "WRONG_TYPE",
                "contactName", "Tom",
                "contactPhone", "13812345678"
            ), token,
            status().isBadRequest(),
            jsonPath("$.code").value("VALIDATION_ERROR")
        );
    }

    // ---- spec R3: 空 contactName ----
    @Test
    void blankContactName_rejected() throws Exception {
        String token = jwtService.generateToken(sales);
        perform(body(
                "customerId", customerId,
                "businessType", "BIM咨询",
                "contactName", "   ",
                "contactPhone", "13812345678"
            ), token,
            status().isBadRequest(),
            jsonPath("$.code").value("VALIDATION_ERROR")
        );
    }

    // ---- spec R3: 电话格式错 ----
    @Test
    void invalidPhone_rejected() throws Exception {
        String token = jwtService.generateToken(sales);
        perform(body(
                "customerId", customerId,
                "businessType", "BIM咨询",
                "contactName", "Tom",
                "contactPhone", "abc"
            ), token,
            status().isBadRequest(),
            jsonPath("$.code").value("VALIDATION_ERROR")
        );
    }

    // ---- spec R5: 重复进行中 ----
    @Test
    void duplicateActiveTriplet_rejectedWithActiveCode() throws Exception {
        // 先 INSERT 一条进行中线索
        Lead existing = new Lead();
        existing.setCustomerId(customerId);
        existing.setBusinessYear((short) LocalDate.now().getYear());
        existing.setBusinessType(com.dealtrace.lead.entity.BusinessType.BIM_CONSULTING);
        existing.setContactName("X");
        existing.setContactPhone("13800000000");
        existing.setStage(LeadStage.QUOTED);
        existing.setCreatedAt(LocalDateTime.now());
        leadMapper.insert(existing);

        String token = jwtService.generateToken(sales);
        perform(body(
                "customerId", customerId,
                "businessType", "BIM咨询",
                "contactName", "Tom",
                "contactPhone", "13812345678"
            ), token,
            status().isBadRequest(),
            jsonPath("$.code").value("DUPLICATE_ACTIVE_LEAD")
        );
    }

    // ---- spec R5: 重复已赢单 ----
    @Test
    void duplicateWonTriplet_rejectedWithWonCode() throws Exception {
        Lead existing = new Lead();
        existing.setCustomerId(customerId);
        existing.setBusinessYear((short) LocalDate.now().getYear());
        existing.setBusinessType(com.dealtrace.lead.entity.BusinessType.BIM_CONSULTING);
        existing.setContactName("X");
        existing.setContactPhone("13800000000");
        existing.setStage(LeadStage.WON);
        existing.setCreatedAt(LocalDateTime.now());
        leadMapper.insert(existing);

        String token = jwtService.generateToken(sales);
        perform(body(
                "customerId", customerId,
                "businessType", "BIM咨询",
                "contactName", "Tom",
                "contactPhone", "13812345678"
            ), token,
            status().isBadRequest(),
            jsonPath("$.code").value("DUPLICATE_WON_LEAD")
        );
    }

    // ---- spec R5: 仅已流失允许 ----
    @Test
    void onlyLostTriplet_allowsNewWithStageUntouched() throws Exception {
        Lead existing = new Lead();
        existing.setCustomerId(customerId);
        existing.setBusinessYear((short) LocalDate.now().getYear());
        existing.setBusinessType(com.dealtrace.lead.entity.BusinessType.BIM_CONSULTING);
        existing.setContactName("X");
        existing.setContactPhone("13800000000");
        existing.setStage(LeadStage.LOST);
        existing.setLostAt(LocalDateTime.now());
        existing.setCreatedAt(LocalDateTime.now());
        leadMapper.insert(existing);

        String token = jwtService.generateToken(sales);
        perform(body(
                "customerId", customerId,
                "businessType", "BIM咨询",
                "contactName", "Tom",
                "contactPhone", "13812345678"
            ), token,
            status().isOk(),
            jsonPath("$.code").value("SUCCESS"),
            jsonPath("$.data.stage").value("未触达")
        );
    }

    // ---- spec R8: LEAD_CREATE 系统日志 + summary ----
    @Test
    void create_writesLeadCreateSystemLogWithSummary() throws Exception {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            jdbcTemplate.execute("TRUNCATE TABLE system_log");
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }

        String token = jwtService.generateToken(sales);
        perform(body(
                "customerId", customerId,
                "businessType", "BIM咨询",
                "contactName", "Tom",
                "contactPhone", "13812345678"
            ), token,
            status().isOk()
        );

        List<Map<String, Object>> logs = jdbcTemplate.queryForList(
            "SELECT action, target_type, target_id, operator_id, lead_id, summary FROM system_log");
        assertThat(logs).hasSize(1);
        Map<String, Object> log = logs.get(0);
        assertThat(log.get("action")).isEqualTo("LEAD_CREATE");
        assertThat(log.get("target_type")).isEqualTo("LEAD");
        assertThat(((Number) log.get("target_id")).longValue()).isPositive();
        assertThat(((Number) log.get("operator_id")).longValue()).isEqualTo(sales.getId());
        assertThat(((Number) log.get("lead_id")).longValue()).isEqualTo(((Number) log.get("target_id")).longValue());
        String summary = (String) log.get("summary");
        assertThat(summary)
            .contains("Lead Create Test Customer")
            .contains("BIM咨询")
            .contains(sales.getEmail());
    }
}
