package com.dealtrace.customer;

import com.dealtrace.common.BusinessException;
import com.dealtrace.common.ErrorCode;
import com.dealtrace.common.MultiTransactionalIntegrationTest;
import com.dealtrace.customer.dto.CreateCustomerRequest;
import com.dealtrace.customer.repository.CustomerMapper;
import com.dealtrace.customer.service.CustomerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec R4 场景 2：两线程同时 service.create 同 USCI；
 * 期望恰有一个成功、一个 DUPLICATE_CUSTOMER；DB 仅一行；不外泄 INTERNAL_ERROR / SQL 异常。
 *
 * <p>多事务基类：service.create 内部的 @Transactional 在两个独立线程上各开自己的事务，
 * 实现 read-then-insert 竞态的可重现。
 */
@SpringBootTest
class CustomerConcurrentInsertTest extends MultiTransactionalIntegrationTest {

    private static final String VALID_USCI = "91110000123456789Q";

    @Autowired private CustomerService customerService;
    @Autowired private CustomerMapper customerMapper;

    @Override
    protected Set<String> tablesToTruncate() {
        return Set.of("customer");
    }

    @Test
    void sameUsci_concurrentInsert_oneWinsOneFails() throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        ExecutorService exec = Executors.newFixedThreadPool(2);
        Runnable task = () -> {
            try {
                startGate.await(); // 两线程在同一起跑线
                customerService.create(new CreateCustomerRequest("并发公司", VALID_USCI));
                successCount.incrementAndGet();
            } catch (BusinessException ex) {
                if (ex.getErrorCode() == ErrorCode.DUPLICATE_CUSTOMER) {
                    duplicateCount.incrementAndGet();
                } else {
                    unexpected.compareAndSet(null, ex);
                }
            } catch (Throwable t) {
                unexpected.compareAndSet(null, t);
            } finally {
                doneGate.countDown();
            }
        };
        exec.submit(task);
        exec.submit(task);
        startGate.countDown();
        boolean finished = doneGate.await(10, TimeUnit.SECONDS);
        exec.shutdownNow();

        assertThat(finished).as("两线程应在 10s 内完成").isTrue();
        assertThat(unexpected.get())
            .as("不允许出现 BusinessException(DUPLICATE_CUSTOMER) 以外的异常（防 INTERNAL_ERROR / SQL 异常外泄）")
            .isNull();
        assertThat(successCount.get()).as("恰有一个成功").isEqualTo(1);
        assertThat(duplicateCount.get()).as("另一个收到 DUPLICATE_CUSTOMER").isEqualTo(1);

        Long rowCount = customerMapper.selectCount(null);
        assertThat(rowCount).as("DB 仅有一行 customer").isEqualTo(1L);
    }
}
