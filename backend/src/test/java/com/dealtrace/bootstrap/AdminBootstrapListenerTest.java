package com.dealtrace.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.common.MultiTransactionalIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec R9（初始 Admin 由部署配置注入）。
 *
 * <p>通过 truncate account 表 → 直接调 {@link AdminBootstrapListener#runIfNeeded()}
 * 模拟"重发 ApplicationReadyEvent"语义，避免依赖 SpringApplication 实例构造 event。
 */
@SpringBootTest
class AdminBootstrapListenerTest extends MultiTransactionalIntegrationTest {

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AdminBootstrapListener listener;

    @Value("${dealtrace.bootstrap.admin-email}")
    private String adminEmail;

    @Value("${dealtrace.bootstrap.admin-password}")
    private String adminPassword;

    @Override
    protected Set<String> tablesToTruncate() {
        return Set.of("account");
    }

    @BeforeEach
    void clearAccountTable() {
        // 启动时 AdminBootstrapListener 已经注入了一条 ADMIN；@AfterEach 只在测试方法结束后清，
        // 首个测试方法跑前 table 仍有 startup 残留行，必须先清。
        // lead-core change 起 lead.owner_sales_id FK 引用 account，TRUNCATE 需先关 FK_CHECKS。
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            jdbcTemplate.execute("TRUNCATE TABLE account");
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    @Test
    void firstStartInjectsConfiguredAdmin() {
        listener.runIfNeeded();

        Account injected = accountMapper.selectOne(
            new QueryWrapper<Account>().eq("email", adminEmail));
        assertThat(injected)
            .as("空 account 表 + runIfNeeded 后应新增配置的 admin")
            .isNotNull();
        assertThat(injected.getRole()).isEqualTo(Role.ADMIN);
        assertThat(injected.getStatus()).isEqualTo(AccountStatus.ENABLED);
        assertThat(passwordEncoder.matches(adminPassword, injected.getPasswordHash()))
            .as("注入的密码哈希应能用配置的明文密码 BCrypt.matches 通过")
            .isTrue();
    }

    @Test
    void existingAdminUntouchedOnReinjection() {
        Account existing = new Account();
        existing.setEmail(adminEmail);
        existing.setName("Pre-existing Admin");
        existing.setRole(Role.ADMIN);
        existing.setStatus(AccountStatus.ENABLED);
        existing.setPasswordHash(passwordEncoder.encode("totally-different-secret"));
        LocalDateTime now = LocalDateTime.now();
        existing.setCreatedAt(now);
        existing.setUpdatedAt(now);
        accountMapper.insert(existing);

        listener.runIfNeeded();

        Account after = accountMapper.selectById(existing.getId());
        assertThat(after.getName()).isEqualTo("Pre-existing Admin");
        assertThat(after.getPasswordHash()).isEqualTo(existing.getPasswordHash());

        Long count = accountMapper.selectCount(new QueryWrapper<Account>().eq("email", adminEmail));
        assertThat(count).isEqualTo(1);
    }

    @Test
    void otherAdminPreventsInjection() {
        Account otherAdmin = new Account();
        otherAdmin.setEmail("other-admin@dealtrace.test");
        otherAdmin.setName("Other Admin");
        otherAdmin.setRole(Role.ADMIN);
        otherAdmin.setStatus(AccountStatus.ENABLED);
        otherAdmin.setPasswordHash(passwordEncoder.encode("secret"));
        LocalDateTime now = LocalDateTime.now();
        otherAdmin.setCreatedAt(now);
        otherAdmin.setUpdatedAt(now);
        accountMapper.insert(otherAdmin);

        listener.runIfNeeded();

        Account injected = accountMapper.selectOne(
            new QueryWrapper<Account>().eq("email", adminEmail));
        assertThat(injected)
            .as("已存在其他邮箱的 ADMIN 时，配置邮箱不应被注入（防多 Admin 漂移，design D4）")
            .isNull();

        Long adminCount = accountMapper.selectCount(
            new QueryWrapper<Account>().eq("role", "ADMIN"));
        assertThat(adminCount).isEqualTo(1);
    }
}
