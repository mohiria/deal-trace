package com.dealtrace.customer;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec R7：创建客户不触发 SystemLogPort.record 调用、system_log 表无新增行。
 *
 * <p>多事务基类让 controller 的 INSERT 真实 commit，便于 SELECT COUNT 在 commit 后立即可读。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerSystemLogQuietTest extends MultiTransactionalIntegrationTest {

    private static final String VALID_USCI = "91110000123456789Q";

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private JwtService jwtService;

    @MockitoSpyBean
    private SystemLogPort systemLogPort;

    private Account sales;

    @Override
    protected Set<String> tablesToTruncate() {
        return Set.of("customer", "account", "system_log");
    }

    @BeforeEach
    void seed() {
        sales = new Account();
        sales.setEmail("syslog-quiet-sales@dealtrace.test");
        sales.setName("Sales");
        sales.setRole(Role.SALES);
        sales.setStatus(AccountStatus.ENABLED);
        sales.setPasswordHash("placeholder-hash-do-not-use-for-login-please-32+");
        LocalDateTime now = LocalDateTime.now();
        sales.setCreatedAt(now);
        sales.setUpdatedAt(now);
        accountMapper.insert(sales);
        Mockito.reset(systemLogPort);
    }

    @Test
    void create_doesNotEmitSystemLog() throws Exception {
        Long before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM system_log", Long.class);

        String token = jwtService.generateToken(sales);
        String body = "{\"name\":\"安静公司\",\"usci\":\"" + VALID_USCI + "\"}";
        mockMvc.perform(post("/customers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());

        Long after = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM system_log", Long.class);
        assertThat(after).as("创建客户后 system_log 行数应保持不变").isEqualTo(before);

        // spy 验证：业务路径未调用 record(...)
        Mockito.verify(systemLogPort, Mockito.never())
            .record(anyString(), anyString(), any(), any());
    }
}
