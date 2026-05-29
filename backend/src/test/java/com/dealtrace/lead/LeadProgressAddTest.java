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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * progress-log spec ADDED：新增进度（字段+时间服务端生成、method/content 校验、仅 SALES 自己名下、
 * ADMIN 写被拒、结束只读先于入参、last_tracked_at 同值同源、失败无残留、不写系统日志）。
 */
@AutoConfigureMockMvc
class LeadProgressAddTest extends IntegrationTest {

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
        jdbcTemplate.update("DELETE FROM progress_log");
        leadMapper.delete(null);
        customerMapper.delete(null);
        accountMapper.delete(null);

        salesA = insertAccount("prog-a@dealtrace.test", Role.SALES);
        salesB = insertAccount("prog-b@dealtrace.test", Role.SALES);
        admin = insertAccount("prog-admin@dealtrace.test", Role.ADMIN);

        Customer c = new Customer();
        c.setName("Progress Customer");
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
        Lead l = new Lead();
        l.setCustomerId(customerId);
        l.setBusinessYear((short) LocalDate.now().getYear());
        l.setBusinessType(BusinessType.BIM_CONSULTING);
        l.setContactName("X");
        l.setContactPhone("13800000000");
        l.setOwnerSalesId(ownerId);
        l.setStage(stage);
        l.setCreatedAt(LocalDateTime.now());
        leadMapper.insert(l);
        return l.getId();
    }

    private void setLastTrackedAt(Long leadId, LocalDateTime t) {
        jdbcTemplate.update("UPDATE `lead` SET last_tracked_at = ? WHERE id = ?", t, leadId);
    }

    private void addProgress(Long id, String body, String token, ResultMatcher... matchers) throws Exception {
        var rb = post("/leads/" + id + "/progress")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
        var actions = mockMvc.perform(rb);
        for (ResultMatcher m : matchers) actions = actions.andExpect(m);
    }

    private long progressCount(Long leadId) {
        Long n = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM progress_log WHERE lead_id = ?", Long.class, leadId);
        return n == null ? 0 : n;
    }

    private LocalDateTime lastTrackedAt(Long leadId) {
        Lead l = leadMapper.selectById(leadId);
        return l.getLastTrackedAt();
    }

    /** 读取该线索唯一一条进度的 track_time（驱动返回 LocalDateTime，避免 Timestamp 强转）。 */
    private LocalDateTime trackTimeOf(Long leadId) {
        return jdbcTemplate.queryForObject(
            "SELECT track_time FROM progress_log WHERE lead_id = ?", LocalDateTime.class, leadId);
    }

    // ---- 3.1：SALES 新增自己名下成功，字段正确、track_time 服务端生成 ----
    @Test
    void sales_addsOwnLead_succeeds_fieldsCorrect() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.CONTACTED);
        LocalDateTime before = LocalDateTime.now().minusSeconds(2);

        addProgress(id, "{\"method\":\"电话\",\"content\":\"已电话沟通需求，客户计划下周内部评审\"}",
            jwtService.generateToken(salesA),
            status().isOk(), jsonPath("$.code").value("SUCCESS"));

        assertThat(progressCount(id)).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT method, content, tracker_id FROM progress_log WHERE lead_id = ?", id);
        assertThat(row.get("method")).isEqualTo("电话");
        assertThat(row.get("content")).isEqualTo("已电话沟通需求，客户计划下周内部评审");
        assertThat(((Number) row.get("tracker_id")).longValue()).isEqualTo(salesA.getId());
        assertThat(trackTimeOf(id)).isAfter(before);
    }

    // ---- 3.2：track_time / tracker_id 不受 body 注入影响 ----
    @Test
    void addProgress_ignoresClientSuppliedTrackTimeAndTracker() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.CONTACTED);

        addProgress(id,
            "{\"method\":\"微信\",\"content\":\"微信跟进\",\"trackTime\":\"2000-01-01T00:00:00\",\"trackerId\":99999}",
            jwtService.generateToken(salesA), status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT tracker_id FROM progress_log WHERE lead_id = ?", id);
        assertThat(((Number) row.get("tracker_id")).longValue()).isEqualTo(salesA.getId());
        assertThat(trackTimeOf(id)).isAfter(LocalDateTime.of(2020, 1, 1, 0, 0));
    }

    // ---- 3.3：last_tracked_at 被更新为新进度 track_time 且严格相等（T0 与 NULL 两起点）----
    @Test
    void addProgress_syncsLastTrackedAt_equalToTrackTime_fromT0() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.CONTACTED);
        setLastTrackedAt(id, LocalDateTime.of(2020, 1, 1, 0, 0));

        addProgress(id, "{\"method\":\"拜访\",\"content\":\"现场拜访\"}",
            jwtService.generateToken(salesA), status().isOk());

        assertThat(lastTrackedAt(id)).isEqualTo(trackTimeOf(id));
    }

    @Test
    void addProgress_syncsLastTrackedAt_equalToTrackTime_fromNull() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.CONTACTED);
        assertThat(lastTrackedAt(id)).isNull();

        addProgress(id, "{\"method\":\"其他\",\"content\":\"邮件往来\"}",
            jwtService.generateToken(salesA), status().isOk());

        assertThat(lastTrackedAt(id)).isEqualTo(trackTimeOf(id));
    }

    // ---- 3.4：SALES 对他人 → 404，无残留，last_tracked_at 不变 ----
    @Test
    void sales_addsOtherSalesLead_returns404_noResidue() throws Exception {
        Long id = insertLead(salesB.getId(), LeadStage.CONTACTED);
        LocalDateTime t0 = LocalDateTime.of(2021, 6, 1, 12, 0);
        setLastTrackedAt(id, t0);

        addProgress(id, "{\"method\":\"电话\",\"content\":\"x\"}",
            jwtService.generateToken(salesA),
            status().isNotFound(), jsonPath("$.code").value("NOT_FOUND"));

        assertThat(progressCount(id)).isEqualTo(0);
        assertThat(lastTrackedAt(id)).isEqualTo(t0);
    }

    // ---- 3.4：SALES 对公海 → 404 ----
    @Test
    void sales_addsPoolLead_returns404_noResidue() throws Exception {
        Long id = insertLead(null, LeadStage.UNTOUCHED);
        addProgress(id, "{\"method\":\"电话\",\"content\":\"x\"}",
            jwtService.generateToken(salesA),
            status().isNotFound(), jsonPath("$.code").value("NOT_FOUND"));
        assertThat(progressCount(id)).isEqualTo(0);
    }

    // ---- 3.4：ADMIN 新增 → 404（读写不对称）----
    @Test
    void admin_addsProgress_returns404_noResidue() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.CONTACTED);
        addProgress(id, "{\"method\":\"电话\",\"content\":\"x\"}",
            jwtService.generateToken(admin),
            status().isNotFound(), jsonPath("$.code").value("NOT_FOUND"));
        assertThat(progressCount(id)).isEqualTo(0);
    }

    // ---- 3.5：已结束线索新增 → LEAD_ENDED_READONLY，无残留，last_tracked_at 不变 ----
    @Test
    void addProgress_toEndedLead_returnsReadonly_noResidue() throws Exception {
        for (LeadStage ended : new LeadStage[]{LeadStage.WON, LeadStage.LOST}) {
            Long id = insertLead(salesA.getId(), ended);
            LocalDateTime t0 = LocalDateTime.of(2021, 6, 1, 12, 0);
            setLastTrackedAt(id, t0);

            addProgress(id, "{\"method\":\"电话\",\"content\":\"x\"}",
                jwtService.generateToken(salesA),
                status().isBadRequest(), jsonPath("$.code").value("LEAD_ENDED_READONLY"));

            assertThat(progressCount(id)).isEqualTo(0);
            assertThat(lastTrackedAt(id)).isEqualTo(t0);
        }
    }

    // ---- 3.5：结束只读先于入参校验（非法 body 仍报 READONLY）----
    @Test
    void addProgress_endedLead_readonlyPrecedesValidation() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.LOST);
        addProgress(id, "{\"method\":\"邮件\",\"content\":\"   \"}",
            jwtService.generateToken(salesA),
            status().isBadRequest(), jsonPath("$.code").value("LEAD_ENDED_READONLY"));
    }

    // ---- 3.6：content 空白 → VALIDATION_ERROR，无残留，last_tracked_at 不变 ----
    @Test
    void addProgress_blankContent_returnsValidationError_noResidue() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.CONTACTED);
        LocalDateTime t0 = LocalDateTime.of(2021, 6, 1, 12, 0);
        setLastTrackedAt(id, t0);

        addProgress(id, "{\"method\":\"电话\",\"content\":\"   \"}",
            jwtService.generateToken(salesA),
            status().isBadRequest(), jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(progressCount(id)).isEqualTo(0);
        assertThat(lastTrackedAt(id)).isEqualTo(t0);
    }

    // ---- 3.6：method 缺失 / 非枚举 → VALIDATION_ERROR ----
    @Test
    void addProgress_missingOrInvalidMethod_returnsValidationError() throws Exception {
        Long id1 = insertLead(salesA.getId(), LeadStage.CONTACTED);
        addProgress(id1, "{\"content\":\"合法内容\"}", jwtService.generateToken(salesA),
            status().isBadRequest(), jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(progressCount(id1)).isEqualTo(0);

        Long id2 = insertLead(salesA.getId(), LeadStage.CONTACTED);
        addProgress(id2, "{\"method\":\"邮件\",\"content\":\"合法内容\"}", jwtService.generateToken(salesA),
            status().isBadRequest(), jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(progressCount(id2)).isEqualTo(0);
    }

    // ---- 3.7：新增成功后 system_log 行数不变 ----
    @Test
    void addProgress_doesNotWriteSystemLog() throws Exception {
        Long id = insertLead(salesA.getId(), LeadStage.CONTACTED);
        Long before = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM system_log WHERE lead_id = ?", Long.class, id);

        addProgress(id, "{\"method\":\"电话\",\"content\":\"沟通记录\"}",
            jwtService.generateToken(salesA), status().isOk());

        Long after = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM system_log WHERE lead_id = ?", Long.class, id);
        assertThat(after).isEqualTo(before);
    }
}
