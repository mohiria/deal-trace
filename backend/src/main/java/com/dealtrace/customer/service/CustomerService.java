package com.dealtrace.customer.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dealtrace.common.BusinessException;
import com.dealtrace.common.ErrorCode;
import com.dealtrace.common.PageQuery;
import com.dealtrace.common.PageView;
import com.dealtrace.customer.dto.CreateCustomerRequest;
import com.dealtrace.customer.entity.Customer;
import com.dealtrace.customer.repository.CustomerMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Customer 业务流（spec R1-R5 + design D2 / D4 / D5 / D6）。
 *
 * <p>create 流程严格按 design D5 六步：
 * <ol>
 *   <li>name 归一化（trim）</li>
 *   <li>usci 归一化（trim + upper）</li>
 *   <li>UsciValidator.isValid 校验</li>
 *   <li>USCI 存在性 check</li>
 *   <li>name 存在性 check</li>
 *   <li>INSERT；catch DuplicateKeyException 翻译为 DUPLICATE_CUSTOMER（并发竞态兜底）</li>
 * </ol>
 *
 * <p>search 改服务端分页（spec「客户搜索 / 列表统一端点」MODIFIED）：keyword 对全表
 * name OR usci 子串匹配后按 created_at 倒序切页，返回 {@code { items, total, page, size }}。
 */
@Service
public class CustomerService {

    private final CustomerMapper customerMapper;

    public CustomerService(CustomerMapper customerMapper) {
        this.customerMapper = customerMapper;
    }

    @Transactional
    public Customer create(CreateCustomerRequest req) {
        if (req == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请求体不可为空");
        }
        String trimmedName = req.name() == null ? "" : req.name().strip();
        if (trimmedName.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "客户名称不可为空");
        }
        String normalizedUsci = UsciValidator.normalize(req.usci());
        if (normalizedUsci == null || normalizedUsci.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "USCI 不可为空");
        }
        if (!UsciValidator.isValid(normalizedUsci)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                "USCI 不符合 GB 32100-2015 标准格式或校验位错误");
        }

        Long usciCount = customerMapper.selectCount(
            new QueryWrapper<Customer>().eq("usci", normalizedUsci));
        if (usciCount != null && usciCount > 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_CUSTOMER, "USCI 已存在");
        }
        Long nameCount = customerMapper.selectCount(
            new QueryWrapper<Customer>().eq("name", trimmedName));
        if (nameCount != null && nameCount > 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_CUSTOMER, "客户名称已存在");
        }

        Customer c = new Customer();
        c.setName(trimmedName);
        c.setUsci(normalizedUsci);
        c.setCreatedAt(LocalDateTime.now());
        try {
            customerMapper.insert(c);
        } catch (DuplicateKeyException ex) {
            throw translateDuplicateKey(ex);
        }
        return c;
    }

    /** 服务端分页搜索：keyword 非空 → name OR usci 全表子串匹配；按 created_at 倒序切页 + count。 */
    @Transactional(readOnly = true)
    public PageView<Customer> search(PageQuery query) {
        QueryWrapper<Customer> qw = new QueryWrapper<>();
        if (query.hasKeyword()) {
            String k = query.keyword();
            qw.and(w -> w.like("name", k).or().like("usci", k));
        }
        Long total = customerMapper.selectCount(qw);

        qw.orderByDesc("created_at").orderByDesc("id");
        qw.last("LIMIT " + query.size() + " OFFSET " + query.offset());
        List<Customer> rows = customerMapper.selectList(qw);

        return PageView.of(rows, total == null ? 0 : total, query.page(), query.size());
    }

    private BusinessException translateDuplicateKey(DuplicateKeyException ex) {
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        if (msg.contains("uk_customer_usci")) {
            return new BusinessException(ErrorCode.DUPLICATE_CUSTOMER, "USCI 已存在");
        }
        if (msg.contains("uk_customer_name")) {
            return new BusinessException(ErrorCode.DUPLICATE_CUSTOMER, "客户名称已存在");
        }
        return new BusinessException(ErrorCode.DUPLICATE_CUSTOMER, "客户已存在");
    }
}
