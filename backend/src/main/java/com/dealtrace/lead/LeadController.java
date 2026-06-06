package com.dealtrace.lead;

import com.dealtrace.common.ApiResponse;
import com.dealtrace.common.BusinessException;
import com.dealtrace.common.ErrorCode;
import com.dealtrace.common.PageQuery;
import com.dealtrace.common.PageView;
import com.dealtrace.customer.entity.Customer;
import com.dealtrace.customer.repository.CustomerMapper;
import com.dealtrace.lead.dto.AssignLeadRequest;
import com.dealtrace.lead.dto.CreateLeadRequest;
import com.dealtrace.lead.dto.DuplicateCheckResponse;
import com.dealtrace.lead.dto.LeadOtherLeadView;
import com.dealtrace.lead.dto.LeadView;
import com.dealtrace.lead.dto.PoolLeadView;
import com.dealtrace.lead.dto.LoseLeadRequest;
import com.dealtrace.lead.dto.ReleaseLeadRequest;
import com.dealtrace.lead.dto.TransferLeadRequest;
import com.dealtrace.lead.dto.UpdateStageRequest;
import com.dealtrace.lead.dto.WinLeadRequest;
import com.dealtrace.lead.entity.BusinessType;
import com.dealtrace.lead.entity.Lead;
import com.dealtrace.lead.service.LeadDuplicateService;
import com.dealtrace.lead.service.LeadClosureService;
import com.dealtrace.lead.service.LeadOtherLeadsService;
import com.dealtrace.lead.service.LeadOwnershipService;
import com.dealtrace.lead.service.LeadService;
import com.dealtrace.lead.service.LeadStageService;
import com.dealtrace.progresslog.dto.AddProgressRequest;
import com.dealtrace.progresslog.dto.ProgressLogView;
import com.dealtrace.progresslog.service.ProgressLogService;
import com.dealtrace.security.AccountPrincipal;
import com.dealtrace.systemlog.dto.SystemLogView;
import com.dealtrace.systemlog.service.SystemLogReadService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 业务线索端点（design D4 / D9）。
 *
 * <p>lead-core 提供创建 / 详情 / 列表 / 查重；lead-ownership 追加公海列表 + 认领 / 退回 /
 * 分配 / 回收 / 转移 6 个端点；lead-stage 追加非结束阶段变更。赢单 / 流失由后续 change 扩展。
 */
@RestController
@RequestMapping("/leads")
public class LeadController {

    private final LeadService leadService;
    private final LeadOwnershipService ownershipService;
    private final LeadStageService stageService;
    private final LeadClosureService closureService;
    private final LeadDuplicateService duplicateService;
    private final LeadOtherLeadsService otherLeadsService;
    private final ProgressLogService progressLogService;
    private final SystemLogReadService systemLogReadService;
    private final CustomerMapper customerMapper;

    public LeadController(LeadService leadService,
                          LeadOwnershipService ownershipService,
                          LeadStageService stageService,
                          LeadClosureService closureService,
                          LeadDuplicateService duplicateService,
                          LeadOtherLeadsService otherLeadsService,
                          ProgressLogService progressLogService,
                          SystemLogReadService systemLogReadService,
                          CustomerMapper customerMapper) {
        this.leadService = leadService;
        this.ownershipService = ownershipService;
        this.stageService = stageService;
        this.closureService = closureService;
        this.duplicateService = duplicateService;
        this.otherLeadsService = otherLeadsService;
        this.progressLogService = progressLogService;
        this.systemLogReadService = systemLogReadService;
        this.customerMapper = customerMapper;
    }

    @PostMapping
    public ApiResponse<LeadView> create(
            @AuthenticationPrincipal AccountPrincipal principal,
            @RequestBody CreateLeadRequest request) {
        Lead lead = leadService.create(request, principal);
        Customer customer = customerMapper.selectById(lead.getCustomerId());
        return ApiResponse.ok(LeadView.of(lead, customer, leadService.ownerName(lead.getOwnerSalesId())));
    }

    @GetMapping("/duplicate-check")
    public ApiResponse<DuplicateCheckResponse> duplicateCheck(
            @AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam Long customerId,
            @RequestParam String businessType) {
        BusinessType type = BusinessType.fromDbValue(businessType);
        if (type == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "业务类型非法");
        }
        int year = LocalDate.now().getYear();
        DuplicateCheckResponse resp = duplicateService.check(year, customerId, type);
        return ApiResponse.ok(resp);
    }

    /**
     * 客户其他业务线索提示（spec customer-other-leads-hint，PRD §7.6.3/§7.6.5/§7.6.6）。
     * authenticated；按角色裁剪在 service：ADMIN 全量、SALES 仅本人名下（design D4）。
     * {@code excludeBusinessType} 选填——详情页传当前线索业务类型以排除自身业务线。
     */
    @GetMapping("/customer-other-leads")
    public ApiResponse<List<LeadOtherLeadView>> customerOtherLeads(
            @AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam Long customerId,
            @RequestParam(required = false) String excludeBusinessType) {
        BusinessType exclude = excludeBusinessType == null ? null
            : BusinessType.fromDbValue(excludeBusinessType);
        return ApiResponse.ok(otherLeadsService.otherLeadsFor(customerId, exclude, principal));
    }

    @GetMapping("/mine")
    public ApiResponse<PageView<LeadView>> mine(
            @AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String keyword) {
        PageView<Lead> result = leadService.myLeads(principal, PageQuery.of(page, size, keyword));
        return ApiResponse.ok(toPageView(result));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PageView<LeadView>> listAll(
            @AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String keyword) {
        PageView<Lead> result = leadService.allLeads(PageQuery.of(page, size, keyword));
        return ApiResponse.ok(toPageView(result));
    }

    /**
     * 我的长期未跟踪线索（spec ADDED：我的长期未跟踪线索查询）。供工作台今日提醒下推；
     * 调用者名下、未结束、超阈值（或从未跟踪）的线索，升序取前 N，无持久化副作用。
     */
    @GetMapping("/mine/stale")
    public ApiResponse<List<LeadView>> mineStale(
            @AuthenticationPrincipal AccountPrincipal principal) {
        return ApiResponse.ok(toViews(leadService.staleOwned(principal)));
    }

    @GetMapping("/{id}")
    public ApiResponse<LeadView> detail(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable Long id) {
        Lead lead = leadService.detailFor(id, principal);
        Customer customer = customerMapper.selectById(lead.getCustomerId());
        return ApiResponse.ok(LeadView.of(lead, customer, leadService.ownerName(lead.getOwnerSalesId())));
    }

    // ---- lead-ownership：公海列表 + 5 个归属写动作 ----

    @GetMapping("/pool")
    public ApiResponse<PageView<PoolLeadView>> pool(
            @AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(ownershipService.listPool(principal, PageQuery.of(page, size, keyword)));
    }

    @PostMapping("/{id}/claim")
    @PreAuthorize("hasRole('SALES')")
    public ApiResponse<LeadView> claim(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable Long id) {
        return ApiResponse.ok(toView(ownershipService.claim(id, principal)));
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("hasRole('SALES')")
    public ApiResponse<LeadView> release(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable Long id,
            @RequestBody(required = false) ReleaseLeadRequest request) {
        String note = request == null ? null : request.releaseNote();
        return ApiResponse.ok(toView(ownershipService.release(id, note, principal)));
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<LeadView> assign(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable Long id,
            @RequestBody(required = false) AssignLeadRequest request) {
        Long salesId = request == null ? null : request.salesId();
        return ApiResponse.ok(toView(ownershipService.assign(id, salesId, principal)));
    }

    @PostMapping("/{id}/recall")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<LeadView> recall(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable Long id) {
        return ApiResponse.ok(toView(ownershipService.recall(id, principal)));
    }

    @PostMapping("/{id}/transfer")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<LeadView> transfer(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable Long id,
            @RequestBody(required = false) TransferLeadRequest request) {
        Long salesId = request == null ? null : request.salesId();
        return ApiResponse.ok(toView(ownershipService.transfer(id, salesId, principal)));
    }

    // ---- lead-stage：非结束阶段变更（ADMIN 任意线索 / SALES 自己名下）----

    @PatchMapping("/{id}/stage")
    public ApiResponse<LeadView> changeStage(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable Long id,
            @RequestBody(required = false) UpdateStageRequest request) {
        String stage = request == null ? null : request.stage();
        return ApiResponse.ok(toView(stageService.changeStage(id, stage, principal)));
    }

    // ---- lead-closure：赢单 / 流失（ADMIN 任意线索 / SALES 自己名下）----

    @PostMapping("/{id}/win")
    public ApiResponse<LeadView> win(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable Long id,
            @RequestBody(required = false) WinLeadRequest request) {
        var amount = request == null ? null : request.contractAmount();
        var signedDate = request == null ? null : request.signedDate();
        return ApiResponse.ok(toView(closureService.win(id, amount, signedDate, principal)));
    }

    @PostMapping("/{id}/lose")
    public ApiResponse<LeadView> lose(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable Long id,
            @RequestBody(required = false) LoseLeadRequest request) {
        String reason = request == null ? null : request.loseReason();
        String note = request == null ? null : request.loseNote();
        return ApiResponse.ok(toView(closureService.lose(id, reason, note, principal)));
    }

    // ---- progress-log：新增进度（仅 SALES 自己名下）/ 读取进度（ADMIN 任意 / SALES 自己名下）----

    @PostMapping("/{id}/progress")
    public ApiResponse<ProgressLogView> addProgress(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable Long id,
            @RequestBody(required = false) AddProgressRequest request) {
        String method = request == null ? null : request.method();
        String content = request == null ? null : request.content();
        var entry = progressLogService.add(id, method, content, principal);
        return ApiResponse.ok(ProgressLogView.of(entry, null));
    }

    @GetMapping("/{id}/progress")
    public ApiResponse<List<ProgressLogView>> listProgress(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable Long id) {
        return ApiResponse.ok(progressLogService.list(id, principal));
    }

    // ---- system-log：读取线索系统日志（ADMIN 任意 / SALES 自己名下，否则 404 不泄漏；view-system-log）----

    @GetMapping("/{id}/logs")
    public ApiResponse<List<SystemLogView>> listLogs(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable Long id) {
        return ApiResponse.ok(systemLogReadService.listByLead(id, principal));
    }

    private LeadView toView(Lead lead) {
        Customer customer = customerMapper.selectById(lead.getCustomerId());
        return LeadView.of(lead, customer, leadService.ownerName(lead.getOwnerSalesId()));
    }

    private List<LeadView> toViews(List<Lead> rows) {
        Map<Long, Customer> customers = leadService.loadCustomers(rows);
        Map<Long, String> ownerNames = leadService.loadOwnerNames(rows);
        // 公海线索 ownerSalesId 为 null；不可变空映射（Map.of()）对 null 键 get 会抛 NPE，
        // 故显式跳过 null 键查找（整页皆公海时 ownerNames 为空映射）。
        return rows.stream()
            .map(l -> LeadView.of(
                l,
                l.getCustomerId() == null ? null : customers.get(l.getCustomerId()),
                l.getOwnerSalesId() == null ? null : ownerNames.get(l.getOwnerSalesId())))
            .toList();
    }

    private PageView<LeadView> toPageView(PageView<Lead> page) {
        return PageView.of(toViews(page.items()), page.total(), page.page(), page.size());
    }
}
