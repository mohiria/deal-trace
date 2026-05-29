## Why

`progress-log` 是 tech-arch §13.2 MVP 变更包里最后一个未落地的 capability。lead 表 V5 已预留 `last_tracked_at` 列（注释明写"由后续 capability 写入"），就是为它准备的。PRD §5.4 / §7.8 / §9.4 / §11.7 要求销售围绕线索手动追加推进记录，形成可追溯的推进时间线（PRD 目标 §4.2）。

`system-log` 已先一步拆分归档；本片实现其镜像对：销售手动写、强绑定 lead、新增后不可改不可删，但写失败必须回滚（与系统日志的 best-effort 相反，因为这是用户主动发起的主操作本身）。

## What Changes

- 新增"新增进度跟踪"写端点：`POST /api/leads/{id}/progress`，body `{ method, content }`，仅 SALES 可对自己名下未结束线索调用。
- 新增"进度列表"读端点：`GET /api/leads/{id}/progress`，按跟踪时间倒序返回该线索全部进度；ADMIN 可读任意线索，SALES 仅可读自己名下线索。
- 新增进度在单事务内原子地：插入 progress_log 行（`track_time` = 服务端时钟）+ 把 `lead.last_tracked_at` 更新为同一时刻。
- 新增 `TrackMethod` 枚举（电话/微信/拜访/其他），仿 `BusinessType` 的 `@EnumValue` 中文枚举范式。
- 新增 V7 Flyway 迁移建 `progress_log` 表。

明确不做：进度不可编辑 / 不可删除（含 Admin）；新增进度不写系统日志；不提供"结束线索对所有 Sales 公开"的可见性扩张（无 PRD 依据）。

## Capabilities

### New Capabilities
- `progress-log`: 销售手动新增的进度跟踪——字段（关联线索 / 跟踪方式 / 跟踪内容 / 跟踪人 / 跟踪时间）、新增与读取的权限与可见性、追加流不可变性、跟踪时间服务端生成、新增同步线索最后跟踪时间。

### Modified Capabilities
<!-- 无：last_tracked_at 的正向写入契约归本片新 capability；lead spec 既有的"阶段变更不碰 last_tracked_at"反向契约与之一致，无需改 lead spec。 -->

## Impact

- **数据库**：新增 `progress_log` 表（V7 Flyway 迁移，纯增量）；`lead` 表无 ALTER（`last_tracked_at` 列 V5 已预留）。
- **API**：新增 `POST /api/leads/{id}/progress`、`GET /api/leads/{id}/progress`。
- **代码**：新增 `ProgressLog` 实体 / Mapper、`TrackMethod` 枚举、进度服务；复用 `LeadMapper.selectByIdForUpdate`（行锁）、lead-stage / closure 的 indistinguishable-404 模式与错误优先级阶梯、`ErrorCode.{VALIDATION_ERROR, NOT_FOUND, LEAD_ENDED_READONLY}`、`GlobalExceptionHandler` 映射。
- **不触及**：system-log（不产生系统日志）、合同、dashboard、lead-core 查重逻辑。
