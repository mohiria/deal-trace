package com.dealtrace.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.common.MultiTransactionalIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec R4（当前用户修改自身密码）+ R8 / scenario 2（改密后哈希存储）。
 *
 * <p>多事务：改密 commit 后用旧/新密码再次登录验证（每次 login 是独立请求）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChangePasswordControllerTest extends MultiTransactionalIntegrationTest {

    private static final String OLD_PWD = "old-p@ssw0rd";
    private static final String NEW_PWD = "new-p@ssw0rd";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Override
    protected Set<String> tablesToTruncate() {
        return Set.of("account");
    }

    private Account insertEnabledAccount(String email) {
        Account a = new Account();
        a.setEmail(email);
        a.setName("ChangePwd User");
        a.setRole(Role.SALES);
        a.setStatus(AccountStatus.ENABLED);
        a.setPasswordHash(passwordEncoder.encode(OLD_PWD));
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);
        return a;
    }

    private String reload(String email, String field) {
        Account fresh = accountMapper.selectOne(new QueryWrapper<Account>().eq("email", email));
        return fresh.getPasswordHash();
    }

    @Test
    void successfulChangeInvalidatesOldPasswordAndAcceptsNew() throws Exception {
        Account a = insertEnabledAccount("change-ok@dealtrace.test");
        String oldHash = a.getPasswordHash();
        String token = jwtService.generateToken(a);

        mockMvc.perform(post("/auth/change-password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"oldPassword\":\"" + OLD_PWD + "\",\"newPassword\":\"" + NEW_PWD + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"));

        String newHash = reload("change-ok@dealtrace.test", "password_hash");
        assertThat(newHash)
            .as("改密后哈希必须变化")
            .isNotEqualTo(oldHash);
        assertThat(passwordEncoder.matches(NEW_PWD, newHash))
            .as("新哈希必须与新密码匹配")
            .isTrue();

        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"change-ok@dealtrace.test\",\"password\":\"" + OLD_PWD + "\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"change-ok@dealtrace.test\",\"password\":\"" + NEW_PWD + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void wrongOldPasswordRejectedAndHashUnchanged() throws Exception {
        Account a = insertEnabledAccount("change-bad-old@dealtrace.test");
        String oldHash = a.getPasswordHash();
        String token = jwtService.generateToken(a);

        mockMvc.perform(post("/auth/change-password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"oldPassword\":\"WRONG\",\"newPassword\":\"" + NEW_PWD + "\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").value("原密码错误"));

        String hashAfter = reload("change-bad-old@dealtrace.test", "password_hash");
        assertThat(hashAfter)
            .as("旧密码校验失败时哈希不得改变")
            .isEqualTo(oldHash);
    }

    @Test
    void blankNewPasswordRejectedAndHashUnchanged() throws Exception {
        Account a = insertEnabledAccount("change-blank-new@dealtrace.test");
        String oldHash = a.getPasswordHash();
        String token = jwtService.generateToken(a);

        mockMvc.perform(post("/auth/change-password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"oldPassword\":\"" + OLD_PWD + "\",\"newPassword\":\"   \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        String hashAfter = reload("change-blank-new@dealtrace.test", "password_hash");
        assertThat(hashAfter)
            .as("新密码空白时哈希不得改变")
            .isEqualTo(oldHash);
    }
}
