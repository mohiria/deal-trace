package com.dealtrace.lead.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.common.BusinessException;
import com.dealtrace.common.ErrorCode;
import com.dealtrace.customer.entity.Customer;
import com.dealtrace.customer.repository.CustomerMapper;
import com.dealtrace.lead.dto.CreateLeadRequest;
import com.dealtrace.lead.dto.DuplicateCheckResponse;
import com.dealtrace.lead.entity.BusinessType;
import com.dealtrace.lead.entity.Lead;
import com.dealtrace.lead.entity.LeadStage;
import com.dealtrace.lead.repository.LeadMapper;
import com.dealtrace.security.AccountPrincipal;
import com.dealtrace.systemlog.SystemLogPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 线索创建 + 查询编排（spec R2-R8 + design D5-D10 / D13）。
 *
 * <p>create 流程：
 * <ol>
 *   <li>必填校验（customerId / businessType / contactName / contactPhone）</li>
 *   <li>contactPhone 格式校验（PhoneValidator）</li>
 *   <li>customerId 存在性校验</li>
 *   <li>归属规则（Admin 可指定 ENABLED Sales；Sales 默认归自己或入池）</li>
 *   <li>business_year = LocalDate.now().getYear()</li>
 *   <li>查重三元组三态判断（LeadDuplicateService）</li>
 *   <li>stage = UNTOUCHED，INSERT</li>
 *   <li>LEAD_CREATE 系统日志（summary 自然语言）</li>
 * </ol>
 */
@Service
public class LeadService {

    private static final int SEARCH_LIMIT = 50;

    private final LeadMapper leadMapper;
    private final CustomerMapper customerMapper;
    private final AccountMapper accountMapper;
    private final LeadDuplicateService duplicateService;
    private final SystemLogPort systemLogPort;

    public LeadService(LeadMapper leadMapper,
                       CustomerMapper customerMapper,
                       AccountMapper accountMapper,
                       LeadDuplicateService duplicateService,
                       SystemLogPort systemLogPort) {
        this.leadMapper = leadMapper;
        this.customerMapper = customerMapper;
        this.accountMapper = accountMapper;
        this.duplicateService = duplicateService;
        this.systemLogPort = systemLogPort;
    }

    @Transactional
    public Lead create(CreateLeadRequest req, AccountPrincipal principal) {
        if (req == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请求体不可为空");
        }
        // 1. 必填字段（customerId / businessType / contactName / contactPhone）
        if (req.customerId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择关联客户");
        }
        BusinessType type = BusinessType.fromDbValue(req.businessType());
        if (type == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "业务类型非法，仅支持 BIM咨询 / BIM培训 / 定制开发");
        }
        String contactName = req.contactName() == null ? "" : req.contactName().strip();
        if (contactName.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "联系人不可为空");
        }
        if (!PhoneValidator.isValid(req.contactPhone())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "联系电话格式非法（仅支持中国大陆 11 位手机号或常见座机）");
        }
        String contactPhone = req.contactPhone().strip();

        // 2. customerId 存在性
        Customer customer = customerMapper.selectById(req.customerId());
        if (customer == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "关联客户不存在");
        }

        // 3. 归属规则
        Long ownerSalesId = resolveOwner(req, principal);

        // 4. business_year 服务端生成
        short businessYear = (short) LocalDate.now().getYear();

        // 5. 查重三元组三态
        DuplicateCheckResponse dup = duplicateService.check(businessYear, req.customerId(), type);
        if (!dup.canCreate()) {
            ErrorCode code = ErrorCode.valueOf(dup.blockingReason());
            String msg = code == ErrorCode.DUPLICATE_ACTIVE_LEAD
                ? "该客户在本年度该业务类型已存在进行中线索"
                : "该客户在本年度该业务类型已有已赢单线索";
            throw new BusinessException(code, msg);
        }

        // 6. INSERT
        Lead lead = new Lead();
        lead.setCustomerId(req.customerId());
        lead.setBusinessYear(businessYear);
        lead.setBusinessType(type);
        lead.setContactName(contactName);
        lead.setContactPhone(contactPhone);
        lead.setLeadSource(req.leadSource() == null || req.leadSource().isBlank() ? null : req.leadSource().strip());
        lead.setOwnerSalesId(ownerSalesId);
        lead.setStage(LeadStage.UNTOUCHED);
        lead.setCreatedAt(LocalDateTime.now());
        leadMapper.insert(lead);

        // 7. LEAD_CREATE 系统日志
        String ownerLabel = ownerSalesId == null
            ? "公海"
            : accountMapper.selectById(ownerSalesId).getEmail();
        String summary = String.format("客户=%s | 类型=%s | 归属=%s",
            customer.getName(), type.getDbValue(), ownerLabel);
        systemLogPort.record("LEAD_CREATE", "LEAD", lead.getId(), principal.id(), summary);

        return lead;
    }

    private Long resolveOwner(CreateLeadRequest req, AccountPrincipal principal) {
        if (principal.role() == Role.ADMIN) {
            if (req.ownerSalesId() == null) {
                return null; // Admin 不指定 → 公海
            }
            Account target = accountMapper.selectById(req.ownerSalesId());
            if (target == null || target.getRole() != Role.SALES
                || target.getStatus() != AccountStatus.ENABLED) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "归属销售已停用或不可用");
            }
            return target.getId();
        }
        // SALES：忽略 req.ownerSalesId（design D5 选「忽略」分支）；按 assignToPool 决定
        return Boolean.TRUE.equals(req.assignToPool()) ? null : principal.id();
    }

    /**
     * 详情：Admin 任意 / Sales 仅自己；其他统一抛 NOT_FOUND（design D9，message 不泄漏存在性）。
     */
    public Lead detailFor(Long id, AccountPrincipal principal) {
        Lead lead = leadMapper.selectById(id);
        if (lead == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "线索不存在");
        }
        if (principal.role() == Role.SALES
            && !Objects.equals(lead.getOwnerSalesId(), principal.id())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "线索不存在");
        }
        return lead;
    }

    /** GET /api/leads/mine：当前用户名下；按 created_at desc + LIMIT 50。 */
    public List<Lead> myLeads(AccountPrincipal principal) {
        return leadMapper.selectList(new QueryWrapper<Lead>()
            .eq("owner_sales_id", principal.id())
            .orderByDesc("created_at")
            .last("LIMIT " + SEARCH_LIMIT));
    }

    /** GET /api/leads：Admin 全局；按 created_at desc + LIMIT 50。 */
    public List<Lead> allLeads() {
        return leadMapper.selectList(new QueryWrapper<Lead>()
            .orderByDesc("created_at")
            .last("LIMIT " + SEARCH_LIMIT));
    }

    /** 配合详情 / 列表的 customerName / USCI 内联。 */
    public Map<Long, Customer> loadCustomers(List<Lead> leads) {
        if (leads.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = leads.stream().map(Lead::getCustomerId).collect(Collectors.toSet());
        return customerMapper.selectBatchIds(ids).stream()
            .collect(Collectors.toMap(Customer::getId, c -> c));
    }
}
