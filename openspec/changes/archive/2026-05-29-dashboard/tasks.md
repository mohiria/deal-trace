## 1. QA 测试设计（先于生产代码，TDD Red）

- [x] 1.1 在 `openspec/changes/dashboard/qa/` 用 vibe-coding-qa 模板写测试设计：按 spec 的 6 个 Requirement × 全部 scenario 列测试矩阵，标注分层（聚合 SQL/口径 → API/集成；时间边界与归一逻辑 → 单元）
- [x] 1.2 记录测试数据构造策略：覆盖「公海单赢单 deal_sales_id=NULL」「流失后 owner 冻结」「停用 Sales 历史事件」「跨月签订日期 vs 赢单事件时间」「今日/本月边界毫秒级用例」

## 2. 返回契约与 DTO（不写状态、不写日志）

- [x] 2.1 定义 `DashboardView` 响应 DTO：`todayNewLeadCount`(long)、`openSeaUnclaimedCount`(long)、`monthlyWonAmount`(BigDecimal，空集归一 0)、`monthlyLossRate`(BigDecimal/Double，可为 null)；并补充流失率分子分母原始计数字段以便测试与前端调试
- [x] 2.2 确认序列化：`monthlyLossRate` 为 null 时 JSON 输出 `null`（不省略键），`monthlyWonAmount` 为 `0` 输出 `"0.00"` 数值语义

## 3. 取数层（只读聚合 SQL，复用既有索引）

- [x] 3.1 新增聚合查询方法（`DashboardMapper` 或在 `LeadMapper`/`ContractMapper` 追加），全部为只读 COUNT/SUM；按 design D5 用 `idx_lead_owner_created` / `idx_lead_stage_created` 覆盖过滤列
- [x] 3.2 ① 今日新增：`COUNT(lead WHERE created_at ∈ [今日起,次日起))`，Sales 加 `owner_sales_id = :me`
- [x] 3.3 ② 公海待认领：`COUNT(lead WHERE owner_sales_id IS NULL AND stage NOT IN (WON,LOST))`，两角色同一全局 SQL
- [x] 3.4 ③ 本月赢单金额：`SUM(contract.contract_amount)` join `lead WHERE won_at ∈ 本月`，Sales 加 `contract.deal_sales_id = :me`；SQL 空集 NULL 在 service 归一为 0
- [x] 3.5 ④ 本月流失分子：`COUNT(lead WHERE lost_at ∈ 本月)`，Sales 加 `owner_sales_id = :me`
- [x] 3.6 ④ 本月结束分母：`赢单事件 COUNT(won_at∈本月[+deal_sales_id=:me]) + 流失事件 COUNT(lost_at∈本月[+owner_sales_id=:me])`

## 4. Service 编排（角色分流 + 双轨语义 + 归一）

- [x] 4.1 `DashboardService`：读 `AccountPrincipal.role()`，Admin 走全局口径、Sales 走个人口径（`me = principal.id()`）；② 公海数两角色都调全局
- [x] 4.2 计算今日/本月时间窗（服务端 `LocalDateTime`，闭起开止，design D4）作为 SQL 入参，禁用客户端时钟
- [x] 4.3 金额空集归一 `BigDecimal.ZERO`；流失率：`endedEventCount==0` → null，否则 `流失分子 / 分母`（保留合适精度，0 流失时为 0 而非 null）
- [x] 4.4 Sales 个人口径下无主线索（owner/deal_sales_id 为 NULL）一律不计入（D1）

## 5. Controller 与鉴权

- [x] 5.1 `GET /api/dashboard` 返回 `DashboardView`；从 `AccountPrincipal` 取角色与 id，不接受客户端 owner/视角参数
- [x] 5.2 鉴权沿用既有 security 链：未登录 401；Admin 与 Sales 均放行，无角色 403（D6）

## 6. 测试落地（TDD Green，真 MySQL 8.4）

- [x] 6.1 单元测试：时间窗计算（今日/本月起止毫秒边界）、金额空集归一为 0、流失率分母 0 → null / 分子 0 → 0
- [x] 6.2 API/集成测试（Testcontainers + 真 MySQL，禁 H2 禁 mock）：未登录 401；Admin 全局四指标；Sales 个人四指标；② 公海数 Sales==Admin
- [x] 6.3 集成测试覆盖事件归属边界：公海单 Admin 赢单（deal_sales_id=NULL）只进全局；流失后 owner 冻结取值；跨月（签订日期上月、won_at 本月）归本月；停用 Sales 历史赢单/流失仍计入全局
- [x] 6.4 集成测试遵守隔离策略，不与 dev backend smoke 并发共用 dealtrace 实例；回滚测试内禁用 raw TRUNCATE（见项目 memory）

## 7. 收尾

- [x] 7.1 全量测试 Green，记录通过数；产出 QA 报告入 `openspec/changes/dashboard/qa/`
- [x] 7.2 `openspec validate dashboard --strict` 通过；确认无新增 Flyway 迁移、无既有测试期望被改动
