package com.dealtrace.auth;

import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.common.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec R1（邮箱与密码登录）+ 部分 R8（密码哈希存储）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>登录成功签发 token + 返回脱敏字段 + 响应不含 passwordHash</li>
 *   <li>邮箱不存在 vs 密码错 → message 文本完全相同（防账号枚举）</li>
 *   <li>停用账号登录 → message="账号已停用"</li>
 * </ul>
 */
@AutoConfigureMockMvc
class LoginControllerTest extends IntegrationTest {

    private static final String PLAIN_PASSWORD = "p@ssw0rd";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountMapper accountMapper;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void cleanAccounts() {
        accountMapper.delete(null);
    }

    private Account insertAccount(String email, String name, Role role, AccountStatus status) {
        Account a = new Account();
        a.setEmail(email);
        a.setName(name);
        a.setRole(role);
        a.setStatus(status);
        a.setPasswordHash(encoder.encode(PLAIN_PASSWORD));
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);
        return a;
    }

    @Test
    void enabledUserWithCorrectPasswordReceivesToken() throws Exception {
        Account a = insertAccount("alice@dealtrace.test", "Alice", Role.SALES, AccountStatus.ENABLED);

        MvcResult result = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"alice@dealtrace.test\",\"password\":\"" + PLAIN_PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andExpect(jsonPath("$.data.email").value("alice@dealtrace.test"))
            .andExpect(jsonPath("$.data.name").value("Alice"))
            .andExpect(jsonPath("$.data.role").value("SALES"))
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
            .as("登录响应不得包含 passwordHash / password 等敏感字段")
            .doesNotContain("passwordHash")
            .doesNotContain(a.getPasswordHash())
            .doesNotContain(PLAIN_PASSWORD);
    }

    @Test
    void nonExistentEmailAndWrongPasswordShareSameMessage() throws Exception {
        insertAccount("bob@dealtrace.test", "Bob", Role.SALES, AccountStatus.ENABLED);

        MvcResult nonExistent = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ghost@dealtrace.test\",\"password\":\"" + PLAIN_PASSWORD + "\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andReturn();

        MvcResult wrongPwd = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"bob@dealtrace.test\",\"password\":\"wrong-password\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andReturn();

        String nonExistentBody = nonExistent.getResponse().getContentAsString();
        String wrongPwdBody = wrongPwd.getResponse().getContentAsString();

        assertThat(nonExistentBody)
            .as("邮箱不存在场景的 message 不得暴露账号是否存在的语义")
            .doesNotContain("不存在")
            .doesNotContain("not found")
            .doesNotContainIgnoringCase("no such");

        // 两场景 message 必须完全相同，否则可被攻击者用于账号枚举。
        assertThat(wrongPwdBody).isEqualTo(nonExistentBody);
    }

    @Test
    void disabledAccountLoginRejectedWithStatusMessage() throws Exception {
        insertAccount("carol@dealtrace.test", "Carol", Role.SALES, AccountStatus.DISABLED);

        MvcResult result = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"carol@dealtrace.test\",\"password\":\"" + PLAIN_PASSWORD + "\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").value("账号已停用"))
            .andExpect(jsonPath("$.data").doesNotExist())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
            .as("停用账号的 401 响应不得包含 token 字段")
            .doesNotContain("\"token\"");
    }
}
