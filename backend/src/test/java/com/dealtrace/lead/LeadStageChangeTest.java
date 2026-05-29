package com.dealtrace.lead;

import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;
import com.dealtrace.auth.JwtService;
import com.dealtrace.common.IntegrationTest;
import com.dealtrace.customer.entity.Customer;
import com.dealtrace.customer.repository.CustomerMapper;
import com.dealtrace.account.repository.AccountMapper;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec ADDED 线索阶段变更（13 Scenario）：
 * SALES 改自己名下成功 / 任意方向跳转 / ADMIN 改他人+公海 / SALES 改他人+公海 404 /
 * 已结束只读（且优先于目标非法）/ 目标结束阶段|非法|no-op 400 / LEAD_STAGE_CHANGE 日志含原+新 /
 * 不触 lastTrackedAt。
 */
@AutoConfigureMockMvc
class LeadStageChangeTest extends IntegrationTest {

    private static final String VALID_USCI = "91110000123456789Q";

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private LeadMapper leadMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Account salesA;
    private Account salesB;
    private Account admin;
    private Long customerId;

    @BeforeEach
    void seed() {
        leadMapper.delete(null);
        customerMapper.delete(null);
        accountMapper.delete(null);

        salesA = insertAccount("stage-a@dealtrace.test", Role.SALES);
        salesB = insertAccount("stage-b@dealtrace.test", Role.SALES);
        admin = insertAccount("stage-admin@dealtrace.test", Role.ADMIN);

        Customer c = new Customer();
        c.setName("Stage Customer");
        c.setUsci(VALID_USCI);
        c.setCreatedAt(LocalDateTime.now());
        customerMapper.insert(c);
        customerId = c.getId();
    }

    private Account insertAccount(String email, Role role) {
        Account a = new Account();
        a.setEmail(email);
        a.setName(email);
        a.setRole(role);
        a.setStatus(AccountStatus.ENABLED);
        a.setPasswordHash(passwordEncoder.encode("p@ssw0rd"));
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);
        return a;
    }

    private Long insertLead(Long ownerId, LeadStage stage) {
        return insertLead(ownerId, stage, null);
    }

    private Long insertLead(Long ownerId, LeadStage stage, LocalDateTime lastTrackedAt) {
        Lead l = new Lead();
        l.setCustomerId(customerId);
        l.setBusinessYear((short) LocalDate.now().getYear());
        l.setBusinessType(BusinessType.BIM_CONSULTING);
        l.setContactName("X");
        l.setContactPhone("13800000000");
        l.setOwnerSalesId(ownerId);
        l.setStage(stage);
        l.setLastTrackedAt(lastTrackedAt);
        l.setCreatedAt(LocalDateTime.now());
        leadMapper.insert(l);
        return l.getId();
    }

    private void patchStage(Long id, String stageValue, String token, ResultMatcher... matchers) throws Exception {
        var rb = patch("/leads/" + id + "/stage")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"stage\":\"" + stageValue + "\"}");
        var actions = mockMvc.perform(rb);
        for (ResultMatcher m : matchers) actions = actions.andExpect(m);
    }

    private long systemLogCount(Long leadId) {
        Long n = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM system_log WHERE lead_id = ?", Long.class, leadId);
        return n == null ? 0 : n;
    }

    // ---- 3.1 / S1：SALES 改自己名下到另一非结束阶段 ----
    @Test
    void sales_changesOwnLead_toAnotherActiveStage_succeeds() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.CONTACTED);
        patchStage(id, "方案报价", jwtService.generateToken(salesA),
            status().isOk(),
            jsonPath("$.code").value("SUCCESS"),
            jsonPath("$.data.stage").value("方案报价"));
        assertThat(leadMapper.selectById(id).getStage()).isEqualTo(LeadStage.QUOTED);
    }

    // ---- 3.2 / S2：任意方向跳转（回退） ----
    @Test
    void sales_canJumpBackwardsBetweenActiveStages() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.NEGOTIATING);
        patchStage(id, "未触达", jwtService.generateToken(salesA),
            status().isOk(),
            jsonPath("$.data.stage").value("未触达"));
        assertThat(leadMapper.selectById(id).getStage()).isEqualTo(LeadStage.UNTOUCHED);
    }

    // ---- 3.3 / S3：ADMIN 改他人名下 ----
    @Test
    void admin_changesOtherSalesLead_succeeds() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.QUOTED);
        patchStage(id, "商务谈判", jwtService.generateToken(admin),
            status().isOk(),
            jsonPath("$.data.stage").value("商务谈判"));
        assertThat(leadMapper.selectById(id).getStage()).isEqualTo(LeadStage.NEGOTIATING);
    }

    // ---- 3.3 / S4：ADMIN 改公海线索 ----
    @Test
    void admin_changesPoolLead_succeeds() throws Exception {
        Long id = insertLead(null, LeadStage.UNTOUCHED);
        patchStage(id, "初步沟通", jwtService.generateToken(admin),
            status().isOk(),
            jsonPath("$.data.stage").value("初步沟通"));
        assertThat(leadMapper.selectById(id).getStage()).isEqualTo(LeadStage.CONTACTED);
    }

    // ---- 3.4 / S5：SALES 改他人名下 → 404 ----
    @Test
    void sales_changesOtherSalesLead_returns404() throws Exception {
        Long id = insertLead(salesB.getId(), LeadStage.QUOTED);
        patchStage(id, "方案报价", jwtService.generateToken(salesA),
            status().isNotFound(),
            jsonPath("$.code").value("NOT_FOUND"));
        assertThat(leadMapper.selectById(id).getStage()).isEqualTo(LeadStage.QUOTED);
    }

    // ---- 3.4 / S6：SALES 改公海 → 404 ----
    @Test
    void sales_changesPoolLead_returns404() throws Exception {
        Long id = insertLead(null, LeadStage.UNTOUCHED);
        patchStage(id, "初步沟通", jwtService.generateToken(salesA),
            status().isNotFound(),
            jsonPath("$.code").value("NOT_FOUND"));
        assertThat(leadMapper.selectById(id).getStage()).isEqualTo(LeadStage.UNTOUCHED);
    }

    // ---- 3.5 / S7：已结束线索只读 ----
    @Test
    void endedLead_isReadOnly() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.WON);
        patchStage(id, "方案报价", jwtService.generateToken(salesA),
            status().isBadRequest(),
            jsonPath("$.code").value("LEAD_ENDED_READONLY"));
        assertThat(leadMapper.selectById(id).getStage()).isEqualTo(LeadStage.WON);
    }

    // ---- 3.5 / S8：只读优先于目标非法 ----
    @Test
    void endedLead_readOnlyTakesPrecedenceOverInvalidTarget() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.LOST);
        patchStage(id, "不存在的阶段", jwtService.generateToken(salesA),
            status().isBadRequest(),
            jsonPath("$.code").value("LEAD_ENDED_READONLY"));
        assertThat(leadMapper.selectById(id).getStage()).isEqualTo(LeadStage.LOST);
    }

    // ---- 3.6 / S9：目标为结束阶段被拒 ----
    @Test
    void targetEndingStage_returnsValidationError() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.NEGOTIATING);
        patchStage(id, "已赢单", jwtService.generateToken(salesA),
            status().isBadRequest(),
            jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(leadMapper.selectById(id).getStage()).isEqualTo(LeadStage.NEGOTIATING);
    }

    // ---- 3.6 / S10：目标为非法枚举被拒 ----
    @Test
    void targetInvalidEnum_returnsValidationError() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.CONTACTED);
        patchStage(id, "foo", jwtService.generateToken(salesA),
            status().isBadRequest(),
            jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(leadMapper.selectById(id).getStage()).isEqualTo(LeadStage.CONTACTED);
    }

    // ---- 3.6 / S11：no-op 被拒且不写日志 ----
    @Test
    void targetEqualsCurrent_noOp_returnsValidationError_andWritesNoLog() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.QUOTED);
        long before = systemLogCount(id);
        patchStage(id, "方案报价", jwtService.generateToken(salesA),
            status().isBadRequest(),
            jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(leadMapper.selectById(id).getStage()).isEqualTo(LeadStage.QUOTED);
        assertThat(systemLogCount(id)).isEqualTo(before);
    }

    // ---- 3.7 / S12：成功生成 LEAD_STAGE_CHANGE 日志含原+新阶段 ----
    @Test
    void success_writesStageChangeSystemLog_withOldAndNewStage() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.CONTACTED);
        long before = systemLogCount(id);
        patchStage(id, "方案报价", jwtService.generateToken(salesA), status().isOk());

        List<Map<String, Object>> logs = jdbcTemplate.queryForList(
            "SELECT action, target_type, target_id, operator_id, lead_id, summary "
                + "FROM system_log WHERE lead_id = ?", id);
        assertThat(systemLogCount(id)).isEqualTo(before + 1);
        Map<String, Object> log = logs.get(logs.size() - 1);
        assertThat(log.get("action")).isEqualTo("LEAD_STAGE_CHANGE");
        assertThat(log.get("target_type")).isEqualTo("LEAD");
        assertThat(((Number) log.get("target_id")).longValue()).isEqualTo(id);
        assertThat(((Number) log.get("operator_id")).longValue()).isEqualTo(salesA.getId());
        assertThat(((Number) log.get("lead_id")).longValue()).isEqualTo(id);
        assertThat((String) log.get("summary")).contains("初步沟通").contains("方案报价");
    }

    // ---- 3.8 / S13：阶段变更不更新 lastTrackedAt ----
    @Test
    void stageChange_doesNotTouchLastTrackedAt() throws Exception {
        LocalDateTime t0 = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        Long id = insertLead(salesA.getId(), LeadStage.UNTOUCHED, t0);
        patchStage(id, "初步沟通", jwtService.generateToken(salesA), status().isOk());
        assertThat(leadMapper.selectById(id).getLastTrackedAt()).isEqualTo(t0);
    }
}
