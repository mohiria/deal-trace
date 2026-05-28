package com.dealtrace.customer.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dealtrace.common.BusinessException;
import com.dealtrace.common.ErrorCode;
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
 * <p>search 按 design D2：keyword 空 → 最近 50 行；keyword 非空 → name OR usci 子串。
 */
@Service
public class CustomerService {

    private static final int SEARCH_LIMIT = 50;

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

    public List<Customer> search(String keyword) {
        String k = keyword == null ? "" : keyword.strip();
        QueryWrapper<Customer> q = new QueryWrapper<Customer>().orderByDesc("created_at").last("LIMIT " + SEARCH_LIMIT);
        if (k.isEmpty()) {
            return customerMapper.selectList(q);
        }
        // name LIKE %k% OR usci LIKE %k%
        return customerMapper.selectList(
            q.and(w -> w.like("name", k).or().like("usci", k))
        );
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
