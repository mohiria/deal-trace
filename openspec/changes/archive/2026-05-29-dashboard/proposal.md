## Why

PRD §7.12 / §11.10 要求一个核心数据看板，是 MVP 后端业务模块中唯一尚未落地的能力。线索全生命周期（创建、归属、阶段、闭环、合同）数据已齐备，但缺少把它们聚合成「今日新增 / 公海待认领 / 本月赢单金额 / 本月流失率」四项指标的入口；Admin 和 Sales 登录后无任何概览视图。

## What Changes

- 新增只读查询接口 `GET /api/dashboard`，一次返回 4 个指标卡片数据。
- 后端按登录主体角色自动分流口径：Admin 看全局，Sales 看个人——前端不传视角参数，返回同构 JSON。
- 实现 PRD §7.12-12 的「归属语义双轨」：存量指标（今日新增、公海待认领）按**当前归属**统计；事件指标（本月赢单金额、本月流失率）按**事件发生时归属**统计。
- 公海待认领数对两种角色均返回全局值（PRD §7.12-9），是 Sales 视角下唯一不按本人过滤的指标。
- 本月流失率分母为 0 时返回 `null`（前端渲染 `--`，非 `0%`）；本月赢单金额空集返回数值 `0`（语义区分：金额「没有」=0，流失率「无法计算」=null）。
- 不含图表、不写任何状态、不写系统日志（纯只读查询不入审计流）。
- **无需新增 Flyway 迁移**：`lead` 表与 `contract` 表已含全部所需列与索引。

## Capabilities

### New Capabilities

- `dashboard`: 指标看板查询能力——按登录角色聚合并返回今日新增业务线索数、当前公海待认领数、本月赢单总金额、本月流失率四项指标，落实存量/事件双轨归属语义与分母为 0 的 null 语义。

### Modified Capabilities

<!-- 无既有 capability 的行为契约发生变化；本次仅新增聚合查询，不改 lead/contract/closure 的既有断言 -->

## Impact

- **新增 API**：`GET /api/dashboard`（鉴权：登录即可访问；未登录 401；Admin 与 Sales 均可，无角色 403）。
- **读取数据源**：`lead` 表（`created_at` / `won_at` / `lost_at` / `owner_sales_id` / `stage`）、`contract` 表（`contract_amount` / `deal_sales_id`）。仅读不写。
- **复用既有索引**：`idx_lead_owner_created`、`idx_lead_stage_created`。
- **依赖既有 capability 的稳定行为**：lead-core（创建时间）、lead-ownership（归属/公海/owner 可为 NULL）、lead-closure + contract（赢单/流失事件时间戳与赢单时刻归属快照 `deal_sales_id`）。
- **不影响**：无 schema 变更、无既有接口签名变更、无既有测试期望变更。
