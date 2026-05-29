## 1. QA 测试设计（TDD 前置）

- [x] 1.1 用 `.claude/skills/vibe-coding-qa/templates/` 在 `openspec/changes/progress-log/qa/lightweight-test-design.md` 写测试设计，逐条映射 progress-log spec 的全部 Scenario（字段/时间服务端生成、method/content 校验、仅 SALES 自己名下、ADMIN 写被拒、结束只读先于入参、last_tracked_at 同值同源、失败无残留、不可变、不写系统日志、读取可见性与倒序）到 API/集成用例
- [x] 1.2 在 `qa/regression-impact-analysis.md` 记录回归范围：共享 `lead` 表（新增 `updateLastTrackedAt` 写路径 vs lead-stage「阶段变更不碰 last_tracked_at」反向契约）、共享 `system_log`（断言不增）、新增 V7 progress_log 表迁移风险、与 lead-stage/ownership/closure 的行锁邻接

## 2. 数据层（迁移 + 实体 + DTO + 枚举）

- [x] 2.1 新增 V7 Flyway 迁移建 `progress_log` 表：`id` / `lead_id`(FK→`lead`) / `method VARCHAR(16)` / `content VARCHAR(1024)` / `tracker_id`(FK→`account`) / `track_time DATETIME(3)`；`lead` 保留字反引号；加 `KEY idx_progress_lead_time (lead_id, track_time)` 支撑按线索倒序；charset/collation 沿用库默认 utf8mb4_unicode_ci
- [x] 2.2 新增 `ProgressLog` 实体（`@TableName("progress_log")`）+ `ProgressLogMapper`（BaseMapper，按需加 `selectByLeadIdOrderByTrackTimeDesc`、`countByLeadId`）
- [x] 2.3 新增 `TrackMethod` 枚举（中文 dbValue：电话/微信/拜访/其他，仿 `BusinessType` 的 `@EnumValue`）+ `fromDbValue`
- [x] 2.4 新增 `AddProgressRequest`(method: String, content: String) DTO；不加 `@NotBlank`，校验在 service
- [x] 2.5 在 `LeadMapper` 加 `updateLastTrackedAt`(id, trackTime) 定向更新，反引号保留字

## 3. 新增进度服务（Red → Green）

- [x] 3.1 写失败测试：SALES 对自己名下线索新增成功（新增 1 条进度、method/content/tracker_id 正确、track_time 服务端生成）
- [x] 3.2 写失败测试：track_time/tracker_id 不受 body 注入影响（即使 body 携带也以服务端值为准）
- [x] 3.3 写失败测试：last_tracked_at 被更新为新进度 track_time 且严格相等（前值 T0 或 NULL 两种起点）
- [x] 3.4 写失败测试：SALES 对他人/公海线索 → 404 不泄漏存在性；ADMIN 新增 → 404；均不残留进度、last_tracked_at 不变
- [x] 3.5 写失败测试：已结束线索（已赢单/已流失）新增 → LEAD_ENDED_READONLY；结束只读先于入参校验（非法 body 仍报 LEAD_ENDED_READONLY 而非 VALIDATION_ERROR）
- [x] 3.6 写失败测试：content 空白 → VALIDATION_ERROR；method 缺失/非枚举 → VALIDATION_ERROR；均不残留、last_tracked_at 不变
- [x] 3.7 写失败测试：新增成功后 `system_log` 行数不变（不写系统日志）
- [x] 3.8 实现新增进度服务方法：单一 @Transactional 内 `selectByIdForUpdate` 锁行 → 角色/归属校验(非 SALES 或 owner≠caller → 404) → 终态只读校验(LEAD_ENDED_READONLY) → 入参校验(content trim 非空、method 枚举) → `now = LocalDateTime.now()` 取一次 → `progressLogMapper.insert(track_time=now, tracker_id=caller, ...)` → `leadMapper.updateLastTrackedAt(leadId, now)`，使 3.1–3.7 全绿

## 4. 进度读取服务（Red → Green）

- [x] 4.1 写失败测试：ADMIN 读任意线索（含他人名下/公海）进度成功、按 track_time 倒序
- [x] 4.2 写失败测试：SALES 读自己名下线索进度成功（含已结束线索仍可读）
- [x] 4.3 写失败测试：SALES 读他人/公海线索 → 404 不泄漏存在性
- [x] 4.4 写失败测试：读取端点无持久化副作用、不写 system_log
- [x] 4.5 实现进度读取服务方法：`selectByIdForUpdate` 或普通读取目标线索 → 可见性校验(ADMIN 放行 / SALES owner==caller 否则 404) → `selectByLeadIdOrderByTrackTimeDesc`，使 4.1–4.4 全绿

## 5. 控制器层

- [x] 5.1 在 `LeadController` 新增 `@PostMapping("/{id}/progress")` 与 `@GetMapping("/{id}/progress")`（无 `@PreAuthorize`，权限在 service 判定），绑定 DTO 与 `AccountPrincipal`，委派进度服务
- [x] 5.2 补 API 层测试（MockMvc + 真 MySQL 8.4）覆盖 HTTP 状态码与 `code`，与 spec Scenario 的 HTTP 断言一致（注意读写权限不对称：ADMIN 写 404 / ADMIN 读 200）

## 6. 回归与验证

- [x] 6.1 跑全量 `lead` 套件（core/ownership/stage/closure）确认共享 `lead` 表与新增 `updateLastTrackedAt` 写路径无回归，特别确认 lead-stage「阶段变更不碰 last_tracked_at」仍绿
- [x] 6.2 跑全量后端套件确认 V7 迁移与共享 `system_log` 不被进度路径写入
- [x] 6.3 `openspec validate progress-log` 通过；按 `qa/qa-test-report.md` 模板记录 Red→Green 证据与剩余风险
