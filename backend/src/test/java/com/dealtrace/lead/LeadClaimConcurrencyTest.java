package com.dealtrace.lead;

import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.common.BusinessException;
import com.dealtrace.common.ErrorCode;
import com.dealtrace.common.MultiTransactionalIntegrationTest;
import com.dealtrace.customer.entity.Customer;
import com.dealtrace.customer.repository.CustomerMapper;
import com.dealtrace.lead.entity.BusinessType;
import com.dealtrace.lead.entity.Lead;
import com.dealtrace.lead.entity.LeadStage;
import com.dealtrace.lead.repository.LeadMapper;
import com.dealtrace.lead.service.LeadOwnershipService;
import com.dealtrace.security.AccountPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec ADDED 认领 + design D1：两线程并发认领同一公海线索，行锁兜底仅一人成功，
 * 终态 owner 等于成功者。用真实多事务（{@link MultiTransactionalIntegrationTest}），
 * 两个独立连接 + SELECT ... FOR UPDATE 串行化。
 */
class LeadClaimConcurrencyTest extends MultiTransactionalIntegrationTest {

    private static final String VALID_USCI = "91110000123456789Q";

    @Autowired private LeadOwnershipService ownershipService;
    @Autowired private AccountMapper accountMapper;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private LeadMapper leadMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    private AccountPrincipal salesA;
    private AccountPrincipal salesB;
    private Long poolLeadId;

    @Override
    protected Set<String> tablesToTruncate() {
        // `lead` 是 MySQL 保留字，基类 TRUNCATE 直接拼表名，必须带反引号
        return Set.of("system_log", "`lead`", "customer", "account");
    }

    @BeforeEach
    void seed() {
        // 多事务测试无 @Rollback；先清掉任何历史残留（含上一次失败 run 的提交），再种数据
        truncateAll();

        Account a = insertAccount("concurrent-a@dealtrace.test");
        Account b = insertAccount("concurrent-b@dealtrace.test");
        salesA = new AccountPrincipal(a.getId(), a.getEmail(), Role.SALES);
        salesB = new AccountPrincipal(b.getId(), b.getEmail(), Role.SALES);

        Customer c = new Customer();
        c.setName("Concurrency Customer");
        c.setUsci(VALID_USCI);
        c.setCreatedAt(LocalDateTime.now());
        customerMapper.insert(c);

        Lead l = new Lead();
        l.setCustomerId(c.getId());
        l.setBusinessYear((short) LocalDate.now().getYear());
        l.setBusinessType(BusinessType.BIM_CONSULTING);
        l.setContactName("X");
        l.setContactPhone("13800000000");
        l.setOwnerSalesId(null);
        l.setStage(LeadStage.UNTOUCHED);
        l.setCreatedAt(LocalDateTime.now());
        leadMapper.insert(l);
        poolLeadId = l.getId();
    }

    private void truncateAll() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            jdbcTemplate.execute("TRUNCATE TABLE system_log");
            jdbcTemplate.execute("TRUNCATE TABLE `lead`");
            jdbcTemplate.execute("TRUNCATE TABLE customer");
            jdbcTemplate.execute("TRUNCATE TABLE account");
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    private Account insertAccount(String email) {
        Account a = new Account();
        a.setEmail(email);
        a.setName(email);
        a.setRole(Role.SALES);
        a.setStatus(AccountStatus.ENABLED);
        a.setPasswordHash(passwordEncoder.encode("p@ssw0rd"));
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);
        return a;
    }

    @Test
    void twoSalesClaimingSameLead_onlyOneSucceeds() throws Exception {
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger alreadyClaimed = new AtomicInteger();
        AtomicReference<Long> winnerId = new AtomicReference<>();

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable claimAsA = claimTask(salesA, startGate, doneGate, successes, alreadyClaimed, winnerId);
        Runnable claimAsB = claimTask(salesB, startGate, doneGate, successes, alreadyClaimed, winnerId);

        pool.submit(claimAsA);
        pool.submit(claimAsB);
        startGate.countDown();
        assertThat(doneGate.await(15, TimeUnit.SECONDS)).as("两线程应在超时前完成").isTrue();
        pool.shutdownNow();

        assertThat(successes.get()).as("恰一人认领成功").isEqualTo(1);
        assertThat(alreadyClaimed.get()).as("另一人收到 LEAD_ALREADY_CLAIMED").isEqualTo(1);

        Lead finalLead = leadMapper.selectById(poolLeadId);
        assertThat(finalLead.getOwnerSalesId())
            .as("终态归属等于成功者")
            .isEqualTo(winnerId.get());
    }

    private Runnable claimTask(AccountPrincipal principal,
                               CountDownLatch startGate,
                               CountDownLatch doneGate,
                               AtomicInteger successes,
                               AtomicInteger alreadyClaimed,
                               AtomicReference<Long> winnerId) {
        return () -> {
            try {
                startGate.await();
                ownershipService.claim(poolLeadId, principal);
                successes.incrementAndGet();
                winnerId.set(principal.id());
            } catch (BusinessException ex) {
                if (ex.getErrorCode() == ErrorCode.LEAD_ALREADY_CLAIMED) {
                    alreadyClaimed.incrementAndGet();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                doneGate.countDown();
            }
        };
    }
}
