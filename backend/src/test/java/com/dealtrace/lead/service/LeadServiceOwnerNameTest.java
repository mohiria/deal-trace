package com.dealtrace.lead.service;

import com.dealtrace.account.entity.Account;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.customer.repository.CustomerMapper;
import com.dealtrace.lead.entity.Lead;
import com.dealtrace.lead.repository.LeadMapper;
import com.dealtrace.systemlog.SystemLogPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 归属销售姓名解析单元测试（design D1）。
 *
 * <p>纯单元（Mockito，无 DB）：覆盖 ownerSalesName 内联的解析逻辑——有归属取姓名、
 * 公海/无归属为 null、账号缺失（停用/删号）为 null、批量映射仅含非空归属。
 * 端到端视图断言由 {@code LeadControllerDetailListTest} 集成覆盖（当前因共享远程库
 * contract 污染数据阻断，详见 QA 报告）。
 */
class LeadServiceOwnerNameTest {

    private final LeadMapper leadMapper = mock(LeadMapper.class);
    private final CustomerMapper customerMapper = mock(CustomerMapper.class);
    private final AccountMapper accountMapper = mock(AccountMapper.class);
    private final LeadDuplicateService duplicateService = mock(LeadDuplicateService.class);
    private final SystemLogPort systemLogPort = mock(SystemLogPort.class);

    private final LeadService service =
        new LeadService(leadMapper, customerMapper, accountMapper, duplicateService, systemLogPort);

    private static Account account(long id, String name) {
        Account a = new Account();
        a.setId(id);
        a.setName(name);
        return a;
    }

    private static Lead leadOwnedBy(Long ownerId) {
        Lead l = new Lead();
        l.setOwnerSalesId(ownerId);
        return l;
    }

    @Test
    void ownerName_returnsAccountName_whenOwnerExists() {
        when(accountMapper.selectById(7L)).thenReturn(account(7L, "赵磊"));
        assertThat(service.ownerName(7L)).isEqualTo("赵磊");
    }

    @Test
    void ownerName_returnsNull_whenOwnerIdNull() {
        assertThat(service.ownerName(null)).isNull();
    }

    @Test
    void ownerName_returnsNull_whenAccountMissing() {
        when(accountMapper.selectById(8L)).thenReturn(null);
        assertThat(service.ownerName(8L)).isNull();
    }

    @Test
    void loadOwnerNames_mapsOnlyNonNullOwners() {
        List<Lead> leads = List.of(leadOwnedBy(7L), leadOwnedBy(null), leadOwnedBy(8L));
        when(accountMapper.selectBatchIds(anyCollection()))
            .thenReturn(List.of(account(7L, "赵磊"), account(8L, "林雨")));

        Map<Long, String> names = service.loadOwnerNames(leads);

        assertThat(names).containsEntry(7L, "赵磊").containsEntry(8L, "林雨");
        assertThat(names.get(null)).isNull();
    }

    @Test
    void loadOwnerNames_empty_whenAllPool() {
        Map<Long, String> names = service.loadOwnerNames(List.of(leadOwnedBy(null), leadOwnedBy(null)));
        assertThat(names).isEmpty();
    }
}
