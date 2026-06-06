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

    /**
     * 内联建客户的 find-or-create（spec：内联建客户）。按 USCI 仲裁：
     * <ul>
     *   <li>USCI 未命中 → 建客户并返回（并发同 USCI 由唯一约束兜底：catch 后重查复用）。</li>
     *   <li>USCI 命中且名称一致（trim 后全等）→ 复用既有客户，不新增行。</li>
     *   <li>USCI 命中但名称不一致 → {@code DUPLICATE_CUSTOMER}（不创建、不改名）。</li>
     * </ul>
     * 归一化 / 校验口径与 {@link #create} 完全一致（trim name；normalize+isValid USCI）。
     * 调用方（LeadService.create）在同一 {@code @Transactional} 内调用，确保后续线索创建失败时
     * 新建客户随事务回滚、无孤儿。
     */
    @Transactional
    public Customer findOrCreate(String rawName, String rawUsci) {
        String name = rawName == null ? "" : rawName.strip();
        if (name.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "客户名称不可为空");
        }
        String usci = UsciValidator.normalize(rawUsci);
        if (usci == null || usci.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "USCI 不可为空");
        }
        if (!UsciValidator.isValid(usci)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                "USCI 不符合 GB 32100-2015 标准格式或校验位错误");
        }

        Customer existing = customerMapper.selectOne(
            new QueryWrapper<Customer>().eq("usci", usci));
        if (existing != null) {
            return arbitrate(existing, name);
        }

        Customer c = new Customer();
        c.setName(name);
        c.setUsci(usci);
        c.setCreatedAt(LocalDateTime.now());
        try {
            customerMapper.insert(c);
        } catch (DuplicateKeyException ex) {
            // 并发同 USCI：唯一约束兜底，重查既有后按同样仲裁复用 / 拒绝
            Customer raced = customerMapper.selectOne(
                new QueryWrapper<Customer>().eq("usci", usci));
            if (raced != null) {
                return arbitrate(raced, name);
            }
            throw translateDuplicateKey(ex); // 名称撞既有（不同 USCI）
        }
        return c;
    }

    /** USCI 命中后按名称仲裁：同名复用、异名 DUPLICATE_CUSTOMER。 */
    private Customer arbitrate(Customer existing, String name) {
        if (existing.getName().equals(name)) {
            return existing;
        }
        throw new BusinessException(ErrorCode.DUPLICATE_CUSTOMER,
            "该统一社会信用代码已存在且对应的客户名称不一致");
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
