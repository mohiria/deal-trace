## Context

lead capability 已分四片落地（lead-core / ownership / stage / closure）。lead 表 V5 预留 `last_tracked_at DATETIME(3) NULL`，注释明写"由后续 capability 写入"——即本片。lead-stage spec 已有反向契约「阶段变更**不**更新最后跟踪时间」（lead/spec.md §阶段变更不更新最后跟踪时间），意味着只有"新增进度"这一条路径能动 `last_tracked_at`。

约束来源：PRD §5.4（进度跟踪定义）、§7.8（字段 / 跟踪方式枚举 / 8 条规则）、§9.4（业务对象）、§8.5.6 + §11.7.4（结束线索禁新增）、§11.7.3（仅 Sales 对自己名下新增）、§7.6.5（Sales 不可见他人私海进度内容）、tech-arch §8.5（进度与系统日志是两个独立对象）、§13.2（capability 拆分）、§12（真 MySQL 测试）、§9.1（时间戳服务端生成）。CLAUDE.md：追加流不可改不可删含 Admin、时间戳禁客户端时钟、schema 名不带环境标签。

探索阶段已与需求方逐条确认 7 个 PRD 未钉死的决策点（见下 D1-D7）。

现成可复用件：`LeadMapper.selectByIdForUpdate`（行锁读）；lead-stage / closure 的 indistinguishable-404 模式与错误优先级阶梯；`ErrorCode.{VALIDATION_ERROR, NOT_FOUND, LEAD_ENDED_READONLY}` + `GlobalExceptionHandler` 映射；`BusinessType` / `LeadStage` 的 `@EnumValue` 中文枚举范式；`LeadStage.isActive()`（WON/LOST 为 false）。

## Goals / Non-Goals

**Goals:**
- 提供"新增进度跟踪"原子写动作 + "进度列表"读动作，完成线索推进时间线。
- 新增进度联动更新 `lead.last_tracked_at`（同值同源、跨表原子）。
- 追加流不可变（不提供改 / 删端点，含 Admin）；跟踪时间服务端生成。
- 权限 / 可见性与 PRD 角色矩阵一致且可测。

**Non-Goals:**
- Admin 新增进度（PRD §3 角色表 + §11.7.3 仅授权 Sales；D1）。
- 进度编辑 / 删除 / 撤销（PRD §7.8.3-5：仅可追加纠错）。
- 新增进度写系统日志（§11.7.9 事件清单不含"新增进度"；D6）。
- "结束线索对所有 Sales 公开进度"（无 PRD 依据，探索中已砍；D3）。
- 把进度内联进 lead 详情端点（lead 单条详情端点尚未存在；本片自带独立读端点，详情聚合留后续切片）。
- 进度分页（单线索进度量级小，硬返回全部倒序）。

## Decisions

**D1 — 新增进度仅 SALES，且仅限自己名下线索；ADMIN 不可新增。**
PRD §3 角色表：Sales 职责列含"新增进度跟踪"，Admin 职责列**不**含；§11.7.3 只授权"Sales 可为自己名下…新增进度跟踪"。这与赢单 / 流失（Admin 可对任意线索操作）的模式**相反**——故本片**不**沿用 closure 的"Admin 万能写"。写权限判定：调用者非 SALES，或 `owner_sales_id != caller`，均拒绝。
_备选_：Admin 也可新增——放弃，无 PRD 依据且与角色表冲突，按需求方决定取最简。

**D2 — 写端点 `POST /api/leads/{id}/progress`，body 仅 `{ method, content }`。**
`track_time`（服务端时钟）、`tracker_id`（认证用户派生）均**不**接受客户端入参（类比 system-log 的 `created_at` / `operator_id`、closure 的 `won_at`）。与 ownership 的 claim / release、closure 的 win / lose 同族动作端点。

**D3 — 读端点 `GET /api/leads/{id}/progress`，可见性 = 线索可见性。**
ADMIN 可读任意线索进度；SALES 仅可读 `owner_sales_id == 自己` 的线索进度，其余统一 404 不泄漏存在性（PRD §7.6.5：Sales 不可见他人私海进度内容）。返回该线索全部进度，按 `track_time` 倒序（PRD §7.8.6），不分页。**结束与否不改变可见性**——探索中确认"结束线索对所有人公开"无 PRD 依据，砍掉。读端点无持久化副作用、不写系统日志。

**D4 — 新增进度的原子事务与 last_tracked_at 同值同源。**
单一 `@Transactional` 内：`selectByIdForUpdate` 锁行 → 角色 / 归属校验 → 结束只读校验 → 入参校验 → `now = LocalDateTime.now()`（取一次）→ `progressLogMapper.insert(track_time=now, tracker_id=caller, ...)` → `leadMapper.updateLastTrackedAt(leadId, now)`。进度 `track_time` 与 `lead.last_tracked_at` 是**同一个 now**，不各取各的时钟（PRD §7.8.7）。两步同事务，任一失败整体回滚。

**D5 — 写失败报错回滚（与 system-log 的 best-effort 相反）。**
system-log 是业务副作用，写失败不阻塞主流程；进度跟踪**本身就是用户主动发起的主操作**，持久化失败 SHALL 返回错误并回滚事务，**不**吞异常、**不**返回伪成功。

**D6 — 新增进度不写系统日志。**
PRD §11.7.9 列出产生系统日志的事件（创建 / 认领 / 退回 / 分配 / 回收 / 转移 / 阶段变更 / 赢单 / 流失 / 停用账号）——**不含**"新增进度跟踪"。tech-arch §8.5：进度与系统日志是两个独立对象。本片写路径**不**调用 `SystemLogPort`；spec 含一条负向断言锁定。

**D7 — 错误优先级阶梯（与 lead-stage / closure 同构）。**
```
1. 线索不存在                       → 404 NOT_FOUND        (selectByIdForUpdate 返回 null)
2. 调用者非 SALES，或 owner != caller → 404 NOT_FOUND        (写端点；ADMIN 也走此分支被拒)
3. 当前 stage 已结束 (WON/LOST)      → 400 LEAD_ENDED_READONLY  (§8.5.6 / §11.7.4)
4. 入参校验失败 (method/content)     → 400 VALIDATION_ERROR
   ✅ 通过 → 插进度 + 更新 last_tracked_at
```
关 3 先于关 4：线索终态是支配性事实。注意关 2 对 ADMIN 写端点也拒（D1），但 ADMIN 读端点放行（D3）——读写权限不对称。

**D8 — 字段校验。**
- `content`：trim 后必填非空（PRD §7.8.1）。上限 VARCHAR(1024)（推进说明，留余量）。
- `method`：必填 + 合法枚举（电话 / 微信 / 拜访 / 其他）；缺失 / 非法 → `VALIDATION_ERROR`（探索确认 method 必填）。

**D9 — TrackMethod 枚举（中文 dbValue），仿 BusinessType。**
新增 `TrackMethod` 枚举：电话 / 微信 / 拜访 / 其他，`@EnumValue` 持久化中文 dbValue，与 `BusinessType` / `LeadStage` / `LoseReason` 一致。

**D10 — 服务归属留给 apply。**
新建 `ProgressLogService` + `ProgressLogMapper`，自包含（仿 closure D10），不放宽既有 lead 服务私有成员可见性。最终类名 / 复用由 apply 阶段按最小重复定夺，spec 不约束。`updateLastTrackedAt` 作为 `LeadMapper` 新方法或独立 SQL，由 apply 定。

## Risks / Trade-offs

- **[读写权限不对称易写错：ADMIN 能读不能写]** → 测试显式覆盖"ADMIN 新增进度被拒（404/权限）"与"ADMIN 读任意线索进度成功"两条。
- **[关 3 与关 4 顺序若反，已结束线索 + 非法入参误报 VALIDATION_ERROR]** → 测试锁定"已结束线索新增 → LEAD_ENDED_READONLY"先于入参校验。
- **[last_tracked_at 与 track_time 若各取时钟会产生毫秒级偏差]** → D4 强制单次 `now` 复用；测试断言进度 `track_time` 与线索 `last_tracked_at` 相等。
- **[跨 lead + progress_log 两表写，任一失败须整体回滚]** → 单一 @Transactional 包裹（D5）；测试覆盖"写失败时 last_tracked_at 不被更新、无残留进度行"。
- **[SALES 读他人线索进度若误放行会泄漏私海内容（违 §7.6.5）]** → 读端点对 SALES 非自己名下线索返回 404；测试覆盖。

## Migration Plan

新增 V7 Flyway 迁移建 `progress_log` 表（向后兼容、纯增量）。lead 表无 ALTER（`last_tracked_at` 列 V5 已预留）。`lead` 为 MySQL 8 保留字，FK 引用必须反引号（见记忆 mysql-reserved-word-lead）。collation 沿用库默认 utf8mb4_unicode_ci。回滚：删两端点 + 服务 + V7（开发期未上生产；progress_log 表可 DROP，lead.last_tracked_at 回到全 NULL）。敏感迁移按 tech-arch §12 在真 MySQL 8.4 跑。
