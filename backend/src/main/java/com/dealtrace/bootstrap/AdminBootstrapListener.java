package com.dealtrace.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Spec R9（初始 Admin 由部署配置注入）。
 *
 * <p>幂等约束（含 design D4 漂移防护）：
 * 若 {@code account} 表中已存在**任何** {@code role=ADMIN} 行，跳过注入；
 * 否则按配置邮箱 + 密码 BCrypt 哈希后插入一条 ADMIN/ENABLED 行。
 */
@Component
public class AdminBootstrapListener {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapListener.class);

    private final AccountMapper accountMapper;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    public AdminBootstrapListener(AccountMapper accountMapper,
                                  PasswordEncoder passwordEncoder,
                                  @Value("${dealtrace.bootstrap.admin-email}") String email,
                                  @Value("${dealtrace.bootstrap.admin-password}") String password) {
        this.accountMapper = accountMapper;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        runIfNeeded();
    }

    /**
     * 暴露给测试与 listener 自身的幂等执行入口。
     */
    public void runIfNeeded() {
        Long adminCount = accountMapper.selectCount(
            new QueryWrapper<Account>().eq("role", Role.ADMIN.name())
        );
        if (adminCount != null && adminCount > 0) {
            log.info("[bootstrap] 已存在 ADMIN 账号 {} 条，跳过初始注入", adminCount);
            return;
        }
        Account admin = new Account();
        admin.setEmail(email);
        admin.setName("系统管理员");
        admin.setRole(Role.ADMIN);
        admin.setStatus(AccountStatus.ENABLED);
        admin.setPasswordHash(passwordEncoder.encode(password));
        LocalDateTime now = LocalDateTime.now();
        admin.setCreatedAt(now);
        admin.setUpdatedAt(now);
        accountMapper.insert(admin);
        log.info("[bootstrap] 初始 ADMIN 账号已注入: {}", email);
    }
}
