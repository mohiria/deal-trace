package com.dealtrace.lead.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.common.BusinessException;
import com.dealtrace.common.ErrorCode;
import com.dealtrace.common.PageQuery;
import com.dealtrace.common.PageView;
import com.dealtrace.customer.entity.Customer;
import com.dealtrace.customer.repository.CustomerMapper;
import com.dealtrace.customer.service.CustomerService;
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

    private final LeadMapper leadMapper;
    private final CustomerMapper customerMapper;
    private final CustomerService customerService;
    private final AccountMapper accountMapper;
    private final LeadDuplicateService duplicateService;
    private final SystemLogPort systemLogPort;

    public LeadService(LeadMapper leadMapper,
                       CustomerMapper customerMapper,
                       CustomerService customerService,
                       AccountMapper accountMapper,
                       LeadDuplicateService duplicateService,
                       SystemLogPort systemLogPort) {
        this.leadMapper = leadMapper;
        this.customerMapper = customerMapper;
        this.customerService = customerService;
        this.accountMapper = accountMapper;
        this.duplicateService = duplicateService;
        this.systemLogPort = systemLogPort;
    }

    @Transactional
    public Lead create(CreateLeadRequest req, AccountPrincipal principal) {
        if (req == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请求体不可为空");
        }
        // 1. 字段校验（businessType / contactName / contactPhone）——先于建客户，
        //    保证电话非法等输入错误不产生孤儿客户。
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

        // 2. 解析关联客户：customerId（选既有）或 newCustomer（内联 find-or-create），恰择其一
        Customer customer = resolveCustomer(req);
        Long customerId = customer.getId();

        // 3. 归属规则
        Long ownerSalesId = resolveOwner(req, principal);

        // 4. business_year 服务端生成
        short businessYear = (short) LocalDate.now().getYear();

        // 5. 查重三元组三态
        DuplicateCheckResponse dup = duplicateService.check(businessYear, customerId, type);
        if (!dup.canCreate()) {
            ErrorCode code = ErrorCode.valueOf(dup.blockingReason());
            String msg = code == ErrorCode.DUPLICATE_ACTIVE_LEAD
                ? "该客户在本年度该业务类型已存在进行中线索"
                : "该客户在本年度该业务类型已有已赢单线索";
            throw new BusinessException(code, msg);
        }

        // 6. INSERT
        Lead lead = new Lead();
        lead.setCustomerId(customerId);
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
        systemLogPort.record("LEAD_CREATE", "LEAD", lead.getId(), principal.id(), summary,
            com.dealtrace.systemlog.SystemLogDetails.leadCreate(ownerSalesId, customer.getName(), type.getDbValue()));

        return lead;
    }

    /**
     * 解析线索关联客户：{@code customerId} 与 {@code newCustomer} 恰择其一。
     * 两者同缺或同提供 → VALIDATION_ERROR；newCustomer 走 {@link CustomerService#findOrCreate}
     * （同一事务内，失败随线索回滚无孤儿）。
     */
    private Customer resolveCustomer(CreateLeadRequest req) {
        boolean hasCustomerId = req.customerId() != null;
        boolean hasNewCustomer = req.newCustomer() != null;
        if (hasCustomerId == hasNewCustomer) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                "请选择既有客户或录入新客户（二者择一）");
        }
        if (hasNewCustomer) {
            return customerService.findOrCreate(req.newCustomer().name(), req.newCustomer().usci());
        }
        Customer customer = customerMapper.selectById(req.customerId());
        if (customer == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "关联客户不存在");
        }
        return customer;
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

    /** GET /api/leads/mine：当前用户名下；服务端分页 + keyword 全表（join customer 名/USCI、联系人）。 */
    @Transactional(readOnly = true)
    public PageView<Lead> myLeads(AccountPrincipal principal, PageQuery query) {
        return pagedLeads(new QueryWrapper<Lead>().eq("owner_sales_id", principal.id()), query);
    }

    /** GET /api/leads：Admin 全局；服务端分页 + keyword 全表。 */
    @Transactional(readOnly = true)
    public PageView<Lead> allLeads(PageQuery query) {
        return pagedLeads(new QueryWrapper<>(), query);
    }

    /** 「我的长期未跟踪线索」阈值天数（后端集中定义，前端不重算）。 */
    public static final int STALE_TRACK_DAYS = 7;
    /** 提醒展示用的数量上限（非全量浏览）。 */
    public static final int STALE_LIMIT = 5;

    /**
     * GET /api/leads/mine/stale：调用者名下、未结束、且 lastTrackedAt 早于阈值（或从未跟踪 NULL）的线索，
     * 按 lastTrackedAt 升序（NULL 视为最久，MySQL ASC 下天然排前）取前 {@link #STALE_LIMIT} 条；无副作用。
     */
    @Transactional(readOnly = true)
    public List<Lead> staleOwned(AccountPrincipal principal) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(STALE_TRACK_DAYS);
        QueryWrapper<Lead> qw = new QueryWrapper<Lead>()
            .eq("owner_sales_id", principal.id())
            .notIn("stage", LeadStage.WON.getDbValue(), LeadStage.LOST.getDbValue())
            .and(w -> w.isNull("last_tracked_at").or().lt("last_tracked_at", cutoff))
            .orderByAsc("last_tracked_at")
            .last("LIMIT " + STALE_LIMIT);
        return leadMapper.selectList(qw);
    }

    /** 在给定基础条件上叠加 keyword 过滤 + count + 倒序切页。 */
    private PageView<Lead> pagedLeads(QueryWrapper<Lead> base, PageQuery query) {
        if (query.hasKeyword()) {
            applyKeyword(base, query.keyword());
        }
        Long total = leadMapper.selectCount(base);

        base.orderByDesc("created_at").orderByDesc("id");
        base.last("LIMIT " + query.size() + " OFFSET " + query.offset());
        List<Lead> rows = leadMapper.selectList(base);

        return PageView.of(rows, total == null ? 0 : total, query.page(), query.size());
    }

    /**
     * keyword 跨表匹配：关联客户名/USCI 或本表联系人（**不**含 contact_phone，避免脱敏口径泄漏）。
     * 参数化两步（先查匹配 customerId 再 IN），避免 inSql 拼接用户输入的注入风险。
     */
    private void applyKeyword(QueryWrapper<Lead> qw, String k) {
        List<Long> customerIds = customerMapper.selectList(
                new QueryWrapper<Customer>().select("id")
                    .and(w -> w.like("name", k).or().like("usci", k)))
            .stream().map(Customer::getId).toList();
        qw.and(w -> {
            w.like("contact_name", k);
            if (!customerIds.isEmpty()) {
                w.or().in("customer_id", customerIds);
            }
        });
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

    /**
     * 解析单条线索归属销售姓名（design D1）：ownerSalesId 为空（公海/无归属）或账号缺失（停用/删号）返回 null。
     */
    public String ownerName(Long ownerSalesId) {
        if (ownerSalesId == null) {
            return null;
        }
        Account account = accountMapper.selectById(ownerSalesId);
        return account == null ? null : account.getName();
    }

    /**
     * 批量解析归属销售姓名映射（避免列表 N+1）：仅查非空 ownerSalesId，一次性 IN。
     */
    public Map<Long, String> loadOwnerNames(List<Lead> leads) {
        Set<Long> ownerIds = leads.stream()
            .map(Lead::getOwnerSalesId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (ownerIds.isEmpty()) {
            return Map.of();
        }
        return accountMapper.selectBatchIds(ownerIds).stream()
            .collect(Collectors.toMap(Account::getId, Account::getName));
    }
}
