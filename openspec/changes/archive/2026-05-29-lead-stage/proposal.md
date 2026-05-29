## Why

`lead-core` 与 `lead-ownership` 已落地，但线索的**阶段推进**仍缺位：销售无法在非结束阶段之间推进线索，管理员也无法纠正阶段。现有 `lead` spec 明确把"未结束阶段判断"标记为推迟到本切片（`含未结束阶段判断推迟到 lead-stage / lead-closure`）。本切片补齐 PRD §7.7 的阶段流转契约，是销售跟踪闭环（创建→流转→**推进**→赢单/流失）中承上启下的一环。

## What Changes

- 新增阶段变更端点 `PATCH /api/leads/{id}/stage`，请求体 `{ "stage": "<中文阶段名>" }`。
- 角色权限：**ADMIN 可改任意线索**（含公海 `owner_sales_id IS NULL`、含他人名下）；**SALES 仅能改自己名下**线索，其余（公海 / 他人名下 / 不存在）一律 `404 NOT_FOUND` 且不泄漏存在性。
- 目标阶段只能是 4 个非结束阶段之一（`未触达` / `初步沟通` / `方案报价` / `商务谈判`），可任意方向跳转，无强制线性顺序（PRD §7.7.6）。
- 已结束线索（`已赢单` / `已流失`）阶段只读：以其为源 → `LEAD_ENDED_READONLY`；以其为目标 → `VALIDATION_ERROR`（进入结束阶段须走后续 `lead-closure` / `contract` 切片，**不在本范围**）。
- 目标阶段 == 当前阶段（no-op）→ `VALIDATION_ERROR`（不支持空变更）。
- 阶段变更在单一事务内先行锁目标行后校验再更新（PRD §7.3.5），成功触发 `LEAD_STAGE_CHANGE` 系统日志，摘要含原阶段与新阶段（PRD §7.8.6 / §7.8.7）。
- 阶段变更**不**更新 `lastTrackedAt`（该字段由 `progress-log` 维护）。

## Capabilities

### New Capabilities
<!-- 无新增 capability -->

### Modified Capabilities
- `lead`: 新增"阶段变更"行为契约（端点、角色矩阵、错误优先级阶梯、`LEAD_STAGE_CHANGE` 日志），兑现现有 spec 中"未结束阶段判断推迟到 lead-stage"的占位。

## Impact

- **API**：新增 `PATCH /api/leads/{id}/stage`。
- **代码**：`LeadController` 新增端点；新增 `UpdateStageRequest` DTO；阶段变更服务方法（复用 `LeadOwnershipService` 的 `lockOrThrow` / `ensureActive` / `record` 原语，或新建 `LeadStageService` 同构实现）。
- **复用既有**：`ErrorCode.{LEAD_ENDED_READONLY, VALIDATION_ERROR, NOT_FOUND}` 均已存在；`LeadStage.isActive()` 已区分非结束/结束；`SystemLogPort` javadoc 已预声明 `LEAD_STAGE_CHANGE`。
- **无 DB 变更**：`lead.stage` 列与 `system_log` 表均已存在，无新增 Flyway 迁移。
- **测试**：API/集成层用真 MySQL 8.4（Testcontainers，禁 H2/mock，tech-arch §12）。
