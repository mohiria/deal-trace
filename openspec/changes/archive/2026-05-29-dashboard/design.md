## Context

PRD §7.12 / §11.10 要求一个核心数据看板，是 MVP 后端唯一未落地的业务能力。所有底层数据已由既有 capability 落地：lead-core 提供创建时间，lead-ownership 提供当前归属（可为 NULL）与公海语义，lead-closure + contract 提供赢单/流失事件时间戳（`won_at` / `lost_at`）以及赢单时刻归属快照（`contract.deal_sales_id`）。本次只新增一个聚合查询入口，不改任何既有行为契约。

关键约束（CLAUDE.md / tech-arch）：金额禁浮点用 `DECIMAL(15,2)` / `BigDecimal`；集成测试用真 MySQL 8.4（Testcontainers，禁 H2 禁 mock）；schema 不带环境标签；归属语义双轨（PRD §7.12-12）。

## Goals / Non-Goals

**Goals:**
- 单一只读接口 `GET /api/dashboard` 返回 4 指标，后端按 `principal.role()` 自动分流全局/个人口径。
- 正确实现存量/事件双轨归属语义。
- 复用既有表与索引，零 Flyway 迁移。

**Non-Goals:**
- 图表/趋势/时间序列（PRD 明确「不包含复杂图表」）。
- 任何写操作或状态变更、任何系统日志记录。
- 自定义时间范围筛选（仅「今日」「本月」固定窗口）。
- 缓存层（MVP 直接实时查询）。

## Decisions

**D1. 流失事件时归属 = `lead.owner_sales_id`，允许 NULL。**
赢单有显式快照 `contract.deal_sales_id`，流失没有专门的归属快照列。论证：流失是终态，PRD §7.7 闭单只读规定流失后不能再分配/回收/转移，故 `owner_sales_id` 在流失时刻被自然冻结，「当前 owner」恒等于「流失时 owner」。NULL（公海单流失、Admin 操作的无主线索）只进 Admin 全局口径，不计入任何 Sales 个人口径——与赢单侧 `deal_sales_id` 为 NULL 的处理对称。
*备选*：新增 `lead.lost_owner_sales_id` 冻结列。否决——需 Flyway 迁移且与终态冻结事实重复，无收益。

**D2. 单接口按角色自动分流，不拆 admin/sales 两个端点。**
两角色指标项完全相同，仅过滤条件不同（Sales 对①③④按本人过滤，②看全局）。后端读 `AccountPrincipal.role()` 决定 SQL 过滤，返回同构 JSON。
*备选*：`/api/dashboard/admin` + `/api/dashboard/sales`。否决——前端需感知自身角色，徒增分支。

**D3. 分母为 0 → 后端 `lossRate=null`；金额空集 → `wonAmount=0`。**
语义区分：流失率「无法计算」(null) vs 金额「没有」(0)。SQL `SUM` 空集返回 NULL，在服务层归一为 `BigDecimal.ZERO`（"0.00"）。流失率仅当 `endedEventCount==0` 时为 null；分子为 0 但分母>0 时为正常的 0（0%）。前端把 null 渲染成 `--`。

**D4. 时间窗口用服务端本地时间（`LocalDateTime`），闭起开止。**
今日 `[今天 00:00:00.000, 次日 00:00:00.000)`；本月 `[本月1号 00:00:00.000, 下月1号 00:00:00.000)`。所有事件时间戳（`created_at` / `won_at` / `lost_at`）均为服务端 `LocalDateTime`，不引入 UTC 偏移，与既有 capability 时间戳生成方式一致。
*备选*：UTC。否决——既有数据全是服务端本地时间，混用会错位。

**D5. 取数走 MyBatis-Plus Mapper 的聚合 SQL，service 层组装 DTO。**
`lead` 表 `idx_lead_owner_created`（覆盖①today + owner 过滤）、`idx_lead_stage_created`（覆盖②公海 stage 过滤）已就绪。③④需 join `contract`（赢单金额/归属）。建议在 `LeadMapper` / `ContractMapper` 追加只读聚合方法（COUNT / SUM），或新建 `DashboardMapper` 集中聚合查询——具体归属在 tasks 阶段定，但 SQL 必须用既有索引覆盖的过滤列。

**D6. 鉴权沿用既有 security 链。**
未登录 → 401（既有过滤器）；Admin 与 Sales 均放行，无角色 403。Sales 的「本人」从 `AccountPrincipal.id()` 取，不接受客户端传入的 owner 参数。

## Risks / Trade-offs

- [D1 等式被破：若未来出现「流失后仍可改 owner」的路径（如 Admin 数据订正）] → 当前 PRD 闭单只读硬约束保证不会发生；spec 已将此前提显式写入，若后续放开闭单只读须同步重审本指标。
- [事件时归属与当前归属在同一接口内混用两套字段（②按 `owner_sales_id` 当前值、③按 `deal_sales_id` 快照、④流失按 `owner_sales_id`、赢单按 `deal_sales_id`）易实现错] → spec 已逐指标固化取数字段；测试须分别构造「赢单后归属本应变化但因终态冻结」「公海单赢单 deal_sales_id=NULL」等用例锁死。
- [`SUM` 空集 NULL 未归一导致前端拿到 null 金额] → D3 在 service 层强制归一为 0，并由 spec scenario「本月无赢单时金额为 0」覆盖。
- [集成测试与 dev backend smoke 并发争用同一 dealtrace 实例、多事务 TRUNCATE 偷走 bootstrap admin] → 沿用既有测试基类隔离策略，禁止并发跑 smoke 与 verify（见项目 memory）。

## Migration Plan

无 schema 迁移。新增代码为纯读路径，可独立部署；回滚即移除接口与相关类，不影响任何既有数据或行为。

## Open Questions

无。7 项决策（D1–D6 + 双轨语义）已与需求方在 explore 阶段全部敲定。
