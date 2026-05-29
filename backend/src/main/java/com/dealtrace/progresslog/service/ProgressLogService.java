package com.dealtrace.progresslog.service;

import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.common.BusinessException;
import com.dealtrace.common.ErrorCode;
import com.dealtrace.lead.entity.Lead;
import com.dealtrace.lead.repository.LeadMapper;
import com.dealtrace.progresslog.dto.ProgressLogView;
import com.dealtrace.progresslog.entity.ProgressLog;
import com.dealtrace.progresslog.entity.TrackMethod;
import com.dealtrace.progresslog.repository.ProgressLogMapper;
import com.dealtrace.security.AccountPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 进度跟踪编排（progress-log spec ADDED）。
 *
 * <p>设计 D10：自包含实现，不依赖既有 lead 服务私有原语。新增进度事务骨架与其它写动作同构：
 * 先 {@link LeadMapper#selectByIdForUpdate} 行锁 → 角色/归属校验 → 终态只读校验 → 入参校验 →
 * 单次服务端时钟 now 复用：插进度（track_time=now）+ 同步 lead.last_tracked_at=now（同值同源，D4）。
 *
 * <p>读写权限不对称（D1/D3）：写仅 SALES 自己名下（ADMIN/他人/公海 → 404）；读 ADMIN 任意 /
 * SALES 自己名下（他人/公海 → 404）。错误优先级阶梯（D7）：不存在 NOT_FOUND → 写权限 NOT_FOUND →
 * 已结束 LEAD_ENDED_READONLY → 入参 VALIDATION_ERROR。新增进度不写系统日志（D6）。
 */
@Service
public class ProgressLogService {

    private final LeadMapper leadMapper;
    private final ProgressLogMapper progressLogMapper;
    private final AccountMapper accountMapper;

    public ProgressLogService(LeadMapper leadMapper, ProgressLogMapper progressLogMapper,
                              AccountMapper accountMapper) {
        this.leadMapper = leadMapper;
        this.progressLogMapper = progressLogMapper;
        this.accountMapper = accountMapper;
    }

    /** POST /api/leads/{id}/progress：新增进度 + 原子同步 last_tracked_at。失败整体回滚（D5）。 */
    @Transactional
    public ProgressLog add(Long leadId, String methodRaw, String contentRaw, AccountPrincipal principal) {
        // 关 1+2：行锁读 + 写权限（仅 SALES 自己名下，否则 404 不泄漏；ADMIN 也走此分支被拒）
        Lead lead = leadMapper.selectByIdForUpdate(leadId);
        if (lead == null
            || principal.role() != Role.SALES
            || !Objects.equals(lead.getOwnerSalesId(), principal.id())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "线索不存在");
        }
        // 关 3：终态只读（先于入参校验）
        if (lead.getStage() != null && !lead.getStage().isActive()) {
            throw new BusinessException(ErrorCode.LEAD_ENDED_READONLY, "线索已结束，不可新增进度");
        }
        // 关 4：入参校验
        TrackMethod method = TrackMethod.fromDbValue(methodRaw == null ? null : methodRaw.strip());
        if (method == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "跟踪方式非法或缺失");
        }
        String content = contentRaw == null ? null : contentRaw.strip();
        if (content == null || content.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "跟踪内容必填");
        }

        LocalDateTime now = LocalDateTime.now();
        ProgressLog entry = new ProgressLog();
        entry.setLeadId(leadId);
        entry.setMethod(method);
        entry.setContent(content);
        entry.setTrackerId(principal.id());
        entry.setTrackTime(now);
        progressLogMapper.insert(entry);

        // 同值同源：last_tracked_at = 本次进度 track_time（同一个 now，D4）
        leadMapper.updateLastTrackedAt(leadId, now);
        return entry;
    }

    /** GET /api/leads/{id}/progress：按 track_time 倒序读取（ADMIN 任意 / SALES 自己名下，否则 404）。 */
    @Transactional(readOnly = true)
    public List<ProgressLogView> list(Long leadId, AccountPrincipal principal) {
        Lead lead = leadMapper.selectById(leadId);
        if (lead == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "线索不存在");
        }
        if (principal.role() == Role.SALES
            && !Objects.equals(lead.getOwnerSalesId(), principal.id())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "线索不存在");
        }
        List<ProgressLog> rows = progressLogMapper.selectByLeadIdOrderByTrackTimeDesc(leadId);
        Map<Long, Account> trackers = loadTrackers(rows);
        return rows.stream()
            .map(p -> ProgressLogView.of(p, trackers.get(p.getTrackerId())))
            .toList();
    }

    private Map<Long, Account> loadTrackers(List<ProgressLog> rows) {
        List<Long> ids = rows.stream()
            .map(ProgressLog::getTrackerId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return accountMapper.selectBatchIds(ids).stream()
            .collect(Collectors.toMap(Account::getId, Function.identity()));
    }
}
