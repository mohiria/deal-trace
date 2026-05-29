## Why

lead capability 已有 core / ownership / stage 三片，但线索生命周期还差最后一环——**进入终态（赢单/流失）**。lead-stage spec 明确把"进入 已赢单/已流失"推迟到本切片（`PATCH /stage` 拒绝终态目标）。本片补齐 PRD §7.11 闭环：销售结果可结构化沉淀（合同金额 / 流失原因），并让既有 lead-core 查重规则（`DUPLICATE_WON_LEAD`）真正生效。这是销售跟踪闭环（创建 → 流转 → 推进 → **赢单/流失**）的收尾。

## What Changes

- 新增赢单端点 `POST /api/leads/{id}/win`，请求体 `{ contractAmount, signedDate }`。
- 新增流失端点 `POST /api/leads/{id}/lose`，请求体 `{ loseReason, loseNote? }`。
- 操作人/可见性沿用 lead-stage：**ADMIN 可操作任意线索**（含公海、他人名下）；**SALES 仅自己名下**，其余一律 `404 NOT_FOUND` 且不泄漏存在性。
- **赢单**为原子事务：校验金额（必填、>0、≤2 位小数）与签订日期（合法日期）→ `stage=已赢单` + `won_at`（服务端时钟）+ **生成 1 条合同记录** + `LEAD_WIN` 日志（含金额与签订日摘要）。
- 合同**成交销售** `deal_sales_id` = 赢单时刻 `lead.owner_sales_id`（公海单由 Admin 赢单时为 `NULL`）；操作人记于系统日志 `operator_id`，与成交销售解耦。
- 每条线索**最多 1 条合同**（contract 表 `lead_id` UNIQUE 强约束）。
- **流失**为原子事务：校验流失原因枚举（其他 → 流失说明必填）→ `stage=已流失` + `lost_at`（服务端时钟）+ `lose_reason`/`lose_note` + `LEAD_LOSE` 日志（含原因与说明摘要）。
- **不可重复标记 / 不可对已结束线索标记**：由现有"已结束只读"覆盖（首次闭单后 stage 终态，二次命中 `LEAD_ENDED_READONLY`），无需新错误码。
- 无前置阶段要求：任意非终态阶段均可赢单/流失。
- 合同本片**只写不读**（读取留给 dashboard 切片；不内联进 `GET /leads/{id}`）。

## Capabilities

### New Capabilities
- `contract`: 合同记录的字段（关联线索 / 合同金额 / 签订日期 / 成交销售 / 创建时间）、金额精度规则、每线索≤1 约束、由赢单事务原子生成。

### Modified Capabilities
- `lead`: 新增"标记赢单""标记流失"两个行为契约（端点、原子事务、终态写入 won_at/lost_at/lose_reason/lose_note、角色矩阵、错误优先级阶梯、LEAD_WIN/LEAD_LOSE 日志），兑现 lead-stage spec 中"进入终态推迟到 lead-closure"的占位。

## Impact

- **API**：新增 `POST /api/leads/{id}/win`、`POST /api/leads/{id}/lose`。
- **代码**：`LeadController` +2 端点；新增 `WinLeadRequest` / `LoseLeadRequest` DTO、`LoseReason` 枚举、`Contract` 实体 + `ContractMapper`；闭单服务方法（新 `LeadClosureService` 或复用，apply 阶段按最小重复/最小风险定夺）。
- **DB**：新增 **V6 Flyway 迁移**建 `contract` 表（FK→`lead`、FK→`account`(deal_sales_id, NULL 允许)、`UNIQUE(lead_id)`、`contract_amount DECIMAL(15,2)`、`signed_date DATE`、`created_at`）。lead 表 `lose_reason/lose_note/won_at/lost_at` 列已在 V5 预留，无需 ALTER。database 名 `dealtrace`，schema 命名不带环境标签。
- **复用既有**：`ErrorCode.{VALIDATION_ERROR, NOT_FOUND, LEAD_ENDED_READONLY}` 均已存在；金额用 `BigDecimal`（精确类型，禁 float/double）。
- **衔接**：闭环使既有 lead-core 查重生效（WON 后同三元组 `DUPLICATE_WON_LEAD`、LOST 后允许新建），dedup 代码不改。
- **测试**：API/集成层用真 MySQL 8.4（Testcontainers，禁 H2/mock）。
