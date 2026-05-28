package com.dealtrace.account;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.auth.JwtService;
import com.dealtrace.common.MultiTransactionalIntegrationTest;
import com.dealtrace.systemlog.SystemLogPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec R7（Admin 启用与停用账号）。多事务以便每次 PATCH commit 后能直接读最新 DB 状态。
 *
 * <p>SystemLogPort 用 @MockitoSpyBean 监听 record() 调用次数与参数，但**不**替换业务逻辑。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminAccountStatusControllerTest extends MultiTransactionalIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @MockitoSpyBean
    private SystemLogPort systemLogPort;

    private Account admin;
    private Account sales;

    @Override
    protected Set<String> tablesToTruncate() {
        return Set.of("account");
    }

    @BeforeEach
    void seed() {
        admin = insert("status-admin@dealtrace.test", "Admin", Role.ADMIN, AccountStatus.ENABLED);
        sales = insert("status-sales@dealtrace.test", "Sales", Role.SALES, AccountStatus.ENABLED);
        Mockito.reset(systemLogPort);
    }

    private Account insert(String email, String name, Role role, AccountStatus status) {
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

    private Account reload(Long id) {
        return accountMapper.selectById(id);
    }

    @Test
    void disableEnabledSalesUpdatesStateAndLogs() throws Exception {
        String adminToken = jwtService.generateToken(admin);

        mockMvc.perform(patch("/admin/accounts/" + sales.getId() + "/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DISABLED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"));

        Account after = reload(sales.getId());
        assertThat(after.getStatus()).isEqualTo(AccountStatus.DISABLED);
        assertThat(after.getDisabledAt()).isNotNull();

        Mockito.verify(systemLogPort, Mockito.times(1))
            .record("ACCOUNT_DISABLE", "ACCOUNT", sales.getId(), admin.getId());
    }

    @Test
    void enableDisabledSalesUpdatesStateAndLogs() throws Exception {
        sales.setStatus(AccountStatus.DISABLED);
        sales.setDisabledAt(LocalDateTime.now());
        accountMapper.updateById(sales);

        String adminToken = jwtService.generateToken(admin);

        mockMvc.perform(patch("/admin/accounts/" + sales.getId() + "/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ENABLED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"));

        Account after = reload(sales.getId());
        assertThat(after.getStatus()).isEqualTo(AccountStatus.ENABLED);
        assertThat(after.getDisabledAt()).isNull();

        Mockito.verify(systemLogPort, Mockito.times(1))
            .record("ACCOUNT_ENABLE", "ACCOUNT", sales.getId(), admin.getId());
    }

    @Test
    void disablingAlreadyDisabledIsIdempotent() throws Exception {
        sales.setStatus(AccountStatus.DISABLED);
        sales.setDisabledAt(LocalDateTime.now());
        accountMapper.updateById(sales);

        String adminToken = jwtService.generateToken(admin);

        mockMvc.perform(patch("/admin/accounts/" + sales.getId() + "/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DISABLED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"));

        Account after = reload(sales.getId());
        assertThat(after.getStatus()).isEqualTo(AccountStatus.DISABLED);
    }

    @Test
    void adminCannotDisableSelf() throws Exception {
        String adminToken = jwtService.generateToken(admin);

        mockMvc.perform(patch("/admin/accounts/" + admin.getId() + "/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DISABLED\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("不可停用自己"));

        Account after = reload(admin.getId());
        assertThat(after.getStatus()).isEqualTo(AccountStatus.ENABLED);
    }

    @Test
    void salesCannotChangeStatus() throws Exception {
        String salesToken = jwtService.generateToken(sales);

        mockMvc.perform(patch("/admin/accounts/" + admin.getId() + "/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + salesToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DISABLED\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        Account after = reload(admin.getId());
        assertThat(after.getStatus()).isEqualTo(AccountStatus.ENABLED);
    }

    @Test
    void nonExistentTargetReturnsNotFound() throws Exception {
        String adminToken = jwtService.generateToken(admin);

        mockMvc.perform(patch("/admin/accounts/999999/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DISABLED\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
