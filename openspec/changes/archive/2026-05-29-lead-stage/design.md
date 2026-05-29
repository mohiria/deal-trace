## Context

`lead` capability 已分两片落地：`lead-core`（创建/查重/可见性）与 `lead-ownership`（公海/认领/退回/分配/回收/转移）。两者均归档进 `openspec/specs/lead`。现有 spec 在可见性需求里留有占位："含未结束阶段判断推迟到 lead-stage / lead-closure"。本切片实现其中的**阶段变更**部分；进入结束阶段（赢单/流失）留给后续 `lead-closure` / `contract`。

约束来源：PRD §7.7（阶段定义与规则）、§7.3.5（阶段变更须后端事务兜底）、§7.8.6/§7.8.7（阶段变更日志须含原+新阶段）、§8.4（结束状态只读）、tech-arch §12（真 MySQL 8.4 测试）、§13.2（业务域 spec 拆分）。

现成可复用件：
- `LeadStage`（`backend/.../lead/entity/LeadStage.java`）：6 枚举，前 4 项 `isActive()==true`。
- `LeadOwnershipService` 的私有原语 `lockOrThrow`（`selectByIdForUpdate` → 不存在则 404）、`ensureActive`（结束则 `LEAD_ENDED_READONLY`）、`record`（写 `SystemLogPort`）、以及 `release()` 的 indistinguishable-404 归属判定模式。
- `ErrorCode.{NOT_FOUND, VALIDATION_ERROR, LEAD_ENDED_READONLY}` 与 `GlobalExceptionHandler` 映射均已存在。
- `SystemLogPort` javadoc 已预声明 `LEAD_STAGE_CHANGE`。

## Goals / Non-Goals

**Goals:**
- 提供 `PATCH /api/leads/{id}/stage` 实现非结束阶段间的任意跳转。
- ADMIN 改任意线索、SALES 改自己名下，权限与可见性边界确定且可测。
- 事务兜底 + `LEAD_STAGE_CHANGE` 审计日志（含原/新阶段）。
- 与 `lead-ownership` 完全一致的错误优先级阶梯，保证测试确定性。

**Non-Goals:**
- 进入 `已赢单` / `已流失`（含合同金额、签订日、流失原因）——属 `lead-closure` / `contract`。
- 撤销赢单/流失（PRD §7.7.10：MVP 不支持）。
- 进度跟踪新增与 `lastTrackedAt` 维护——属 `progress-log`。
- 阶段顺序强约束（PRD §7.7.6 明确"任意跳转"，不做线性校验）。
- 新增 DB schema / Flyway 迁移（`lead.stage`、`system_log` 均已存在）。

## Decisions

**D1 — HTTP 动词用 `PATCH /api/leads/{id}/stage`。**
线索控制器现有动作（claim/release/assign/recall/transfer）均为 `POST /{id}/<verb>`，但那些是**归属流转动作**；阶段变更是**对单一状态字段的局部更新**，语义上与已落地的 `PATCH /api/accounts/{id}/status` 同构。选 PATCH 以贴合"字段更新"语义并复用既有先例。
_备选_：`POST /{id}/stage`（控制器内风格统一）——放弃，理由是 PATCH 的字段更新语义更准确，且账号状态已开 PATCH 先河。

**D2 — 角色矩阵与 indistinguishable-404。**
ADMIN：无归属过滤，可改任意线索（含公海与他人名下）。
SALES：仅当 `lead.owner_sales_id == 调用者 id` 才可改；其余（公海 `owner_sales_id IS NULL`、他人名下、`id` 不存在）统一返回 `404 NOT_FOUND`，message 与"线索不存在"不可区分——直接复用 `release()` 的判定写法（`!Objects.equals(ownerSalesId, principal.id())` → `NOT_FOUND`）。
_理由_：与 `lead-core` 详情可见性（SALES 看非自己名下 → 404 不泄漏存在性）保持一致；ADMIN 全可见也与既有可见性需求一致。

**D3 — Admin 阶段权是对 PRD §7.7.6 的 gap-fill 扩展（已确认，非冲突）。**
PRD §7.7.6 仅显式授予 "Sales 在自己名下…任意跳转"，未提及 Admin；但 §7.7 未禁止 Admin 改阶段，§7.8.2/§7.8.6 把"阶段变更"列为受审计操作却未限定操作人。故"Admin 可改任意线索阶段"是填补 PRD 未表态的空白，**不触发 Requirement Conflict Gate**（CLAUDE.md 冲突门仅针对与 PRD/tech-arch 抵触）。在此明确记录为有意广义化，供后续审阅区分于 spec drift。

**D4 — 错误优先级阶梯（与归属操作同构，决定测试断言顺序）。**
```
1. 线索不存在                                  → 404 NOT_FOUND        (lockOrThrow)
2. SALES 且 owner_sales_id != 调用者           → 404 NOT_FOUND        (ADMIN 跳过此关)
3. 当前 stage 为结束态 (已赢单/已流失)          → 400 LEAD_ENDED_READONLY (ensureActive)
4. 目标 stage 非法枚举 / 是结束阶段             → 400 VALIDATION_ERROR
5. 目标 stage == 当前 stage (no-op)            → 400 VALIDATION_ERROR
   ✅ 通过 → updateStage + record(LEAD_STAGE_CHANGE)
```
关 3 先于关 4/5：线索结束态是支配性事实，即便目标非法也先报只读（与 `release()` 中 `ensureActive` 在归属校验后立即执行一致）。

**D5 — 校验在锁内执行，单一 `@Transactional`。**
方法体：`lockOrThrow(id)` → SALES 归属校验 → `ensureActive` → 解析并校验目标 stage → `updateStage(id, newStage)` → `record(...)`。先锁后判（PRD §7.3.5），避免并发下 TOCTOU。
_理由_：阶段变更无跨行竞争（不像认领抢同一行归属），但仍以行锁统一事务模型，且与既有 `LeadOwnershipService` 写法一致便于复用与审阅。

**D6 — `LEAD_STAGE_CHANGE` 日志摘要格式。**
沿用归属日志的 `key=value | …` 风格：`summary = "阶段变更 | 原阶段=" + old.dbValue + " | 新阶段=" + new.dbValue`。`record(action="LEAD_STAGE_CHANGE", targetType="LEAD", targetId=leadId, operatorId=调用者id)`，底层由 `system-log` 的 `JdbcSystemLogPort` 持久化，`lead_id` 落 `leadId`。

**D7 — 目标 stage 解析。**
请求体 `{ "stage": "<中文阶段名>" }`，服务端用 `LeadStage.fromDbValue(label)`：返回 `null`（非法枚举）或结束阶段（`!isActive()`）→ `VALIDATION_ERROR`；等于当前阶段 → `VALIDATION_ERROR`；否则为合法目标。
_理由_：DTO 直接收中文 dbValue 与现有 `CreateLeadRequest.businessType` 接收中文枚举的约定一致。

**D8 — 服务归属：可放进 `LeadOwnershipService` 或新建 `LeadStageService`。**
两者均可；倾向**新建 `LeadStageService`** 以保持 `LeadOwnershipService` 聚焦归属语义，但需复用其原语——可将 `lockOrThrow` / `ensureActive` / `record` / `ownerLabel` 提取为共享 helper 或包级可见。实现期由 apply 阶段按最小重复原则定夺，spec 不约束类名（CLAUDE.md：spec 只写行为契约）。

## Risks / Trade-offs

- **[关 3 与关 4 顺序若实现反了，已结束线索 + 非法目标会误报 `VALIDATION_ERROR`]** → 测试显式覆盖"已结束线索 + 任意目标 → `LEAD_ENDED_READONLY`"锁定顺序。
- **[SALES 对公海线索改阶段期望 404 而非 `FORBIDDEN`/`LEAD_ENDED_READONLY`]** → 用例显式断言公海线索（owner=null）下 SALES 得 404，与他人名下同码，防止泄漏存在性。
- **[复用 `LeadOwnershipService` 私有原语需提升可见性，可能牵动既有归属测试]** → 仅提取/放宽可见性不改行为；apply 后跑全量 `lead` 套件回归（claim/release/assign/recall/transfer）确认无回归。
- **[no-op 后端归为 `VALIDATION_ERROR`，仅为防御性兜底]** → 前端阶段是选择控件（tech-arch §10.4：Tag/Steps），候选项 SHALL 排除当前阶段（或选中当前阶段时禁用提交），故 no-op 不会经正常 UI 提交；后端的 `VALIDATION_ERROR` 只防直接打 API 或"陈旧选项+并发改阶段"。前端不依赖错误码区分 no-op，不构成 UX 歧义；message 文案表达"目标阶段与当前阶段相同"，不新增错误码。

## Migration Plan

无数据迁移。新增端点向后兼容；无 schema 变更；回滚即移除端点与服务方法，无残留状态。
