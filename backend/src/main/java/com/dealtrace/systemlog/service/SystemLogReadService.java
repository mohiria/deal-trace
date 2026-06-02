package com.dealtrace.systemlog.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.common.BusinessException;
import com.dealtrace.common.ErrorCode;
import com.dealtrace.lead.entity.Lead;
import com.dealtrace.lead.repository.LeadMapper;
import com.dealtrace.security.AccountPrincipal;
import com.dealtrace.systemlog.SystemLogActionLabels;
import com.dealtrace.systemlog.dto.SystemLogPageView;
import com.dealtrace.systemlog.dto.SystemLogView;
import com.dealtrace.systemlog.entity.SystemLog;
import com.dealtrace.systemlog.repository.SystemLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 系统日志读出编排（view-system-log spec R1–R4）。
 *
 * <p>线索维度读权限镜像 progress-log（design D3）：ADMIN 任意线索 / SALES 仅自己名下，否则统一
 * {@link ErrorCode#NOT_FOUND} 不泄漏存在性。全局浏览（{@code /admin/**} 路径级 ADMIN 守卫）按 created_at
 * 倒序分页、可选 action/target_type 过滤。
 *
 * <p>展示组装（design D4）：按<b>当前</b>账号解析 operator / 归属姓名（MVP 无硬删除，id 必可解析；
 * operator 为 NULL → "系统"，空归属 → "公海"），action → 中文标签；detail 非空走结构化富化、NULL 走
 * freetext fallback（spec R4）。金额保留精确字符串，千分位交前端渲染。
 */
@Service
public class SystemLogReadService {

    /** 归属相关 id 键 → 富化后附加的当前姓名键。 */
    private static final Map<String, String> OWNER_ID_KEYS = Map.of(
        "fromOwnerId", "fromOwnerName",
        "toOwnerId", "toOwnerName",
        "ownerSalesId", "ownerName"
    );

    private static final String POOL_LABEL = "公海";
    private static final String SYSTEM_LABEL = "系统";
    private static final int MAX_PAGE_SIZE = 100;

    private final SystemLogMapper systemLogMapper;
    private final LeadMapper leadMapper;
    private final AccountMapper accountMapper;
    private final ObjectMapper objectMapper;

    public SystemLogReadService(SystemLogMapper systemLogMapper, LeadMapper leadMapper,
                                AccountMapper accountMapper, ObjectMapper objectMapper) {
        this.systemLogMapper = systemLogMapper;
        this.leadMapper = leadMapper;
        this.accountMapper = accountMapper;
        this.objectMapper = objectMapper;
    }

    /** GET /leads/{id}/logs：线索维度倒序读取（ADMIN 任意 / SALES 自己名下，否则 404）。 */
    @Transactional(readOnly = true)
    public List<SystemLogView> listByLead(Long leadId, AccountPrincipal principal) {
        Lead lead = leadMapper.selectById(leadId);
        if (lead == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "线索不存在");
        }
        if (principal.role() == Role.SALES
            && !Objects.equals(lead.getOwnerSalesId(), principal.id())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "线索不存在");
        }
        return toViews(systemLogMapper.selectByLeadIdOrderByCreatedAtDesc(leadId));
    }

    /** GET /admin/system-logs：全局倒序分页（路径级 ADMIN 守卫）。可选 action/target_type 过滤。 */
    @Transactional(readOnly = true)
    public SystemLogPageView listGlobal(String action, String targetType, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 1);
        long offset = (long) (safePage - 1) * safeSize;

        QueryWrapper<SystemLog> qw = new QueryWrapper<>();
        if (action != null && !action.isBlank()) {
            qw.eq("action", action.strip());
        }
        if (targetType != null && !targetType.isBlank()) {
            qw.eq("target_type", targetType.strip());
        }
        Long total = systemLogMapper.selectCount(qw);

        qw.orderByDesc("created_at").orderByDesc("id");
        qw.last("LIMIT " + safeSize + " OFFSET " + offset);
        List<SystemLog> rows = systemLogMapper.selectList(qw);

        return new SystemLogPageView(toViews(rows), total == null ? 0 : total, safePage, safeSize);
    }

    /** 批量解析账号姓名 + 解析/富化 detail，组装展示视图。 */
    private List<SystemLogView> toViews(List<SystemLog> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = resolveNames(rows);
        List<SystemLogView> views = new ArrayList<>(rows.size());
        for (SystemLog r : rows) {
            Map<String, Object> detail = parseAndEnrichDetail(r.getDetail(), names);
            String operatorName = r.getOperatorId() == null
                ? SYSTEM_LABEL
                : names.getOrDefault(r.getOperatorId(), "账号#" + r.getOperatorId());
            views.add(new SystemLogView(
                r.getId(),
                r.getAction(),
                SystemLogActionLabels.labelOf(r.getAction()),
                operatorName,
                r.getCreatedAt(),
                r.getTargetType(),
                r.getTargetId(),
                r.getLeadId(),
                detail,
                r.getSummary()
            ));
        }
        return views;
    }

    /** 收集所有 operator + 归属 id，一次批量查账号当前姓名。 */
    private Map<Long, String> resolveNames(List<SystemLog> rows) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (SystemLog r : rows) {
            if (r.getOperatorId() != null) {
                ids.add(r.getOperatorId());
            }
            Map<String, Object> d = parseDetailRaw(r.getDetail());
            for (String idKey : OWNER_ID_KEYS.keySet()) {
                Long ownerId = asLong(d.get(idKey));
                if (ownerId != null) {
                    ids.add(ownerId);
                }
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return accountMapper.selectBatchIds(ids).stream()
            .collect(Collectors.toMap(Account::getId, Account::getName, (a, b) -> a));
    }

    /** 解析 detail JSON 并附加归属当前姓名（公海为"公海"）；非 JSON / null 返回空 map。 */
    private Map<String, Object> parseAndEnrichDetail(String detailJson, Map<Long, String> names) {
        Map<String, Object> raw = parseDetailRaw(detailJson);
        if (raw.isEmpty()) {
            return null;
        }
        Map<String, Object> enriched = new LinkedHashMap<>(raw);
        OWNER_ID_KEYS.forEach((idKey, nameKey) -> {
            if (enriched.containsKey(idKey)) {
                Long ownerId = asLong(enriched.get(idKey));
                enriched.put(nameKey, ownerId == null ? POOL_LABEL
                    : names.getOrDefault(ownerId, "账号#" + ownerId));
            }
        });
        return enriched;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseDetailRaw(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(detailJson, Map.class);
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    private static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.valueOf(v.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
