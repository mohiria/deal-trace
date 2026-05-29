## Why

lead-core 已交付线索的创建 / 查询 / 查重预检，但归属流转全部缺位：公海无法被 Sales 看见、无法认领，Admin 无法分配 / 回收 / 转移，Sales 无法主动退回。PRD §7.5 / §7.9 / §7.10 把这套归属机制列为 MVP 主链路（§5.2 第 4 条），lead-core 的 R9 也显式声明 `/claim` 等端点"暂未暴露"。本 change 把 ownership 一族从"未暴露"升级为可契约的行为，是 lead capability 的第二站（在 lead-stage / lead-closure 之前）。

## What Changes

- 新增公海列表查询 `GET /api/leads/pool`：Admin + Sales 均可访问；返回同一字段集，但 `contactPhone` 按角色脱敏——Admin 明文、Sales 按 tech-arch §9.4 分级脱敏（`138****5678`）。仅含 `owner_sales_id IS NULL` 且未结束的线索。
- 新增认领 `POST /api/leads/{id}/claim`（SALES）：后端事务原子兜底（`SELECT … FOR UPDATE` + 校验 + `UPDATE`）。成功条件为线索存在、当前在公海、未结束、调用者为 ENABLED 的 SALES。并发下仅一人成功，其余返回 `LEAD_ALREADY_CLAIMED`；"线索存在但不在公海"亦归 `LEAD_ALREADY_CLAIMED`。
- 新增主动退回 `POST /api/leads/{id}/release`（SALES，owner=self）：必填 `releaseNote`（trim 后非空）；归属清空、阶段保留（不重置为"未触达"）；releaseNote 仅进 `system_log.summary`，lead 表不加列。
- 新增分配 `POST /api/leads/{id}/assign`（ADMIN，body `salesId`）：仅对公海未结束线索，目标须为 ENABLED 的 SALES。
- 新增回收 `POST /api/leads/{id}/recall`（ADMIN）：把私海未结束线索归属清空、移回公海。
- 新增转移 `POST /api/leads/{id}/transfer`（ADMIN，body `salesId`）：私海未结束线索从原 Sales 改派给另一 ENABLED SALES（目标须不同于现归属）。
- 5 个写动作各触发一条系统日志：`LEAD_CLAIM` / `LEAD_RELEASE` / `LEAD_ASSIGN` / `LEAD_RECALL` / `LEAD_TRANSFER`，`summary` 以可读文本记录原归属与新归属（退回另含 releaseNote）。
- 引入手机号脱敏工具（tech-arch §9.4：11 位 → 前 3 后 4 中间 `****`；≥8 位其他号 → 前 3 后 4 + `****`；<8 位 → 仅末 2 位），供公海列表 Sales 视角使用。
- 任一已结束（已赢单 / 已流失）线索对全部 5 个写动作返回 `LEAD_ENDED_READONLY`（PRD §8.5 / tech-arch §8.4）。
- **零 schema 改动**：V5 已预留 `owner_sales_id NULL` 与 `idx_lead_owner_created`，全部动作仅 `UPDATE owner_sales_id`。

## Capabilities

### New Capabilities

无新增 capability。

### Modified Capabilities

- `lead`: 向现有 `lead` capability 增量加入归属流转 requirement（公海列表 / 认领 / 退回 / 分配 / 回收 / 转移 + 各自系统日志），并 MODIFY lead-core 的"未暴露能力"requirement——删去其中 `/claim` 未暴露的 scenario（stage / win / lose 两条保留，留给后续 change）。

## Impact

- **新增代码**：
  - `backend/src/main/java/com/dealtrace/lead/service/PhoneMasker.java`（脱敏工具，含单元测试）
  - `backend/src/main/java/com/dealtrace/lead/dto/PoolLeadView.java`（公海列表项，电话字段已脱敏 / 明文二态）
  - `backend/src/main/java/com/dealtrace/lead/dto/AssignLeadRequest.java` / `TransferLeadRequest.java`（body `salesId`）/ `ReleaseLeadRequest.java`（body `releaseNote`）
  - `backend/src/main/java/com/dealtrace/lead/service/LeadOwnershipService.java`（5 动作事务编排 + system_log）
- **修改代码**：
  - `backend/src/main/java/com/dealtrace/lead/LeadController.java`：追加 6 个端点（pool / claim / release / assign / recall / transfer）
  - `backend/src/main/java/com/dealtrace/lead/repository/LeadMapper.java`：追加 `selectForUpdate`、公海列表查询、归属 `UPDATE`
  - `backend/src/main/java/com/dealtrace/common/ErrorCode.java`：追加 `LEAD_ALREADY_CLAIMED` / `LEAD_ENDED_READONLY`（tech-arch §6.3.8/9 已占位）
  - `backend/src/main/java/com/dealtrace/common/GlobalExceptionHandler.java`：switch 追加两码 → `LEAD_ALREADY_CLAIMED` 映射 409（或按既有约定 400，design 定）、`LEAD_ENDED_READONLY` → 400
- **API**：新增 6 个端点（1 查询 + 5 写）。
- **数据库**：无 schema 改动（复用 V5 的列与索引）。
- **依赖 / 库**：无新增。
- **复用且行为不变**：`auth-account`（角色 / ENABLED 状态判断）、`system-log`（SystemLogPort，action 字符串开放枚举可直接新增 5 个）、`customer`（公海列表内联 customerName / USCI）。
- **回滚**：删新代码 + 还原 ErrorCode / GlobalExceptionHandler / LeadController / LeadMapper；无 migration 可回滚。下游 lead-stage / lead-closure 尚未开始，无依赖。
