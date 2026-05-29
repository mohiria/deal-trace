# Lightweight Test Design — dashboard 指标看板

## Context

- Requirement / Spec: PRD §7.12 / §11.10；`openspec/changes/dashboard/specs/dashboard/spec.md`（5 Requirement / 21 Scenario）
- Change summary: 新增只读 `GET /api/dashboard`，按登录角色分流 Admin 全局 / Sales 个人口径，返回 4 指标（今日新增、公海待认领、本月赢单金额、本月流失率）。
- Target modules / APIs: `GET /api/dashboard`；`DashboardController` / `DashboardService` / `DashboardMapper`（只读 COUNT/SUM）；`DashboardView` DTO。
- Test environment / constraints: 集成测试用真 MySQL 8.4（Testcontainers，禁 H2 禁 mock，tech-arch §12）；与 dev backend smoke 不可并发共用 dealtrace 实例；回滚测试内禁用 raw TRUNCATE（项目 memory）。事务时间戳服务端生成。

## Input Sources Checked

- [x] Active Spec / PRD / acceptance criteria / issue
- [x] Existing behavior baseline: tests / code / old Spec / API contract（复用 lead-closure 的 won_at/lost_at、contract.deal_sales_id）
- [x] Data model / field rules / CRUD matrix（lead / contract 表，无新增迁移）
- [x] API contract / auth rules / error shape（ApiResponse 信封；security 链 anyRequest().authenticated()）
- [ ] UI states / user roles / user paths（前端不在本 change 范围）
- [x] Code structure / changed code / dependency graph
- [x] Existing tests / historical defects / flaky areas（LeadWinTest/LeadLoseTest 的 seed 模式）
- [x] Test data / credentials / mocks / CI constraints（真 MySQL，JwtService 生成 token）

## Requirement Authority / Conflict Gate

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Result |
| --- | --- | --- | --- | --- | --- |
| 4 指标聚合查询 | 无（全新只读入口） | PRD §7.12 / spec dashboard | extends | PRD | Proceed |
| 事件时归属取数 | lead-closure: contract.deal_sales_id 快照 + won_at/lost_at | spec dashboard D1 | extends（只读复用，不改写闭单行为） | PRD §7.12-12 | Proceed |

无 conflicts。

## Test Points

| Test point | Source / authority | Design method | Test layer | Input / precondition | Expected result | Assertion target | Priority | Coverage artifact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 未登录访问被拒 | spec R1/S1 | 边界 | API/integration | 无 token GET /dashboard | 401 | HTTP 401 | P0 | `DashboardControllerTest#unauthenticated_returns401` |
| Admin 与 Sales 均可访问且不写日志 | spec R1/S2 | 决策表 | API/integration | Admin/Sales token | 200 + 四指标字段 + system_log 无新增 | 字段齐全、日志计数不变 | P0 | `DashboardControllerTest#bothRoles_return200_noSystemLog` |
| Admin 今日新增计全局 | spec R2/S1 | 等价类 | API/integration | 今日 3 条不同归属线索 | todayNewLeadCount=3 | 计数 | P0 | `DashboardTodayTest#admin_countsGlobal` |
| Sales 今日新增仅当前归属本人 | spec R2/S2 | 等价类 | API/integration | 今日本人2/他人1/无主1 | =2 | 计数 | P0 | `DashboardTodayTest#sales_countsOwnOnly` |
| 昨日线索不计今日 | spec R2/S3 | 边界 | API/integration | created_at=昨日23:59:59.999 | 不计入 | 计数 | P0 | `DashboardTodayTest#yesterdayExcluded` |
| 今日零点计入 | spec R2/S4 | 边界 | API/integration | created_at=今日00:00:00.000 | 计入 | 计数 | P0 | `DashboardTodayTest#todayMidnightIncluded` |
| 公海数=归属空且进行中 | spec R3/S1 | 等价类 | API/integration | 5 空主 4进行中1流失 | openSeaUnclaimedCount=4 | 计数 | P0 | `DashboardOpenSeaTest#countsNullOwnerActive` |
| 有主进行中不计公海 | spec R3/S2 | 等价类 | API/integration | 有主进行中线索 | 不计入 | 计数 | P0 | `DashboardOpenSeaTest#ownedExcluded` |
| Sales 公海数=全局值 | spec R3/S3 | 一致性 | API/integration | 同数据 Admin vs Sales | 两者相等 | 计数相等 | P0 | `DashboardOpenSeaTest#salesEqualsAdmin` |
| Admin 本月赢单金额全局 | spec R4/S1 | 等价类 | API/integration | 本月 2 赢单 100000.00+50000.50 | monthlyWonAmount=150000.50 | BigDecimal | P0 | `DashboardWonAmountTest#admin_sumsGlobal` |
| Sales 赢单金额按 deal_sales_id | spec R4/S2 | 等价类 | API/integration | 本人80000他人60000 | =80000.00 | BigDecimal | P0 | `DashboardWonAmountTest#sales_byDealSales` |
| 赢单按 won_at 而非签订日归月 | spec R4/S3 | 边界 | API/integration | signed_date 上月，won_at 本月 | 计入本月 | BigDecimal | P0 | `DashboardWonAmountTest#anchorsOnWonAt` |
| 无主赢单只进全局 | spec R4/S4 | 决策表 | API/integration | 公海单 Admin 赢单 deal_sales_id=NULL | 进 Admin，不进 Sales | BigDecimal | P0 | `DashboardWonAmountTest#ownerlessGlobalOnly` |
| 本月无赢单金额=0 | spec R4/S5 | 边界（空集） | API/integration | 本月无赢单 | monthlyWonAmount=0（非 null） | BigDecimal 0.00 | P0 | `DashboardWonAmountTest#emptyIsZero` |
| 停用 Sales 历史赢单仍进全局 | spec R4/S6 | 边界 | API/integration | 赢单后 owner 停用 | 仍计入 Admin 全局 | BigDecimal | P1 | `DashboardWonAmountTest#disabledSalesStillCounted` |
| Admin 流失率全局 2/5 | spec R5/S1 | 等价类 | API/integration | 本月2流失3赢单 | lossRate=0.40 | 比率 | P0 | `DashboardLossRateTest#admin_global` |
| Sales 流失率按事件时归属 | spec R5/S2 | 等价类 | API/integration | 本人1流失3赢单 | lossRate=0.25 | 比率 | P0 | `DashboardLossRateTest#sales_byEventOwner` |
| 分母 0 流失率=null | spec R5/S3 | 边界 | API/integration | 本月无结束事件 | lossRate=null | null 字段存在 | P0 | `DashboardLossRateTest#zeroDenominatorNull` |
| 仅赢单无流失=0 | spec R5/S4 | 边界 | API/integration | 本月2赢单0流失 | lossRate=0（非 null） | 比率 0 | P0 | `DashboardLossRateTest#onlyWonIsZero` |
| 无主流失只进全局 | spec R5/S5 | 决策表 | API/integration | 无主线索流失 | 进 Admin，不进 Sales | 分子/分母 | P0 | `DashboardLossRateTest#ownerlessGlobalOnly` |
| 停用 Sales 结束事件仍进全局 | spec R5/S6 | 边界 | API/integration | 停用后历史事件 | 仍计入 Admin 全局 | 分子/分母 | P1 | `DashboardLossRateTest#disabledSalesStillCounted` |
| 今日/本月时间窗计算 | design D4 | 边界 | Unit | 给定 now | 起含止开的 LocalDateTime 区间 | 区间端点 | P0 | `DashboardTimeWindowTest` |
| 流失率归一逻辑 | design D3 | 决策表 | Unit | 分母0/分子0/正常 | null / 0 / 比率 | 返回值 | P0 | `DashboardMetricsCalcTest`（若 service 逻辑可纯函数化） |

## TDD Candidates

| Test point | Initial failing test | Why it should fail before implementation | Expected Red failure reason | Minimal behavior to pass | Related regression |
| --- | --- | --- | --- | --- | --- |
| 401 未登录 | `DashboardControllerTest#unauthenticated_returns401` | 端点尚不存在 | 404→修正为期望 401（端点建立后由 security 链返回 401） | 建 controller + 端点路由 | security 链既有行为 |
| Admin 四指标 | `DashboardControllerTest#bothRoles_return200_noSystemLog` | 端点/DTO 不存在 | 404 / 反序列化失败 | DTO + service + controller 全链 | 无 |
| 金额空集=0 | `DashboardWonAmountTest#emptyIsZero` | service 未归一，SUM 返回 null | NPE 或返回 null | service 归一 BigDecimal.ZERO | 无 |
| 分母0=null | `DashboardLossRateTest#zeroDenominatorNull` | 未实现 null 分支 | 除零异常或返回 0 | endedEventCount==0→null | 无 |

> 说明：本 change 取数逻辑高度依赖真 MySQL 聚合 SQL，Red 证据主要落在 API/集成层（端点不存在→404，建立后逐项断言）。纯函数部分（时间窗、归一）补单元层 Red。

## Non-TDD Exceptions

| Scope | Reason strict TDD does not apply | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| 无 | — | — | — |

## Prerequisite Blockers

| Blocker | Affected test point | Required owner action | Status |
| --- | --- | --- | --- |
| 无（lead/contract 表与索引已就绪，无需迁移） | — | — | RESOLVED |

## 测试数据构造策略（task 1.2）

统一沿用 `LeadWinTest` 的 seed 模式（`@BeforeEach` 清表 → 插 account / customer / lead / contract），用 `JdbcTemplate` 精确控制时间戳列：

1. **公海单赢单 deal_sales_id=NULL**：插 owner_sales_id=NULL 的 lead，stage='已赢单'，won_at=本月内；contract.deal_sales_id 显式插 NULL。验证只进 Admin 全局、不进任何 Sales 个人。
2. **流失后 owner 冻结**：插 owner_sales_id=salesA 的 lead，stage='已流失'，lost_at=本月内；不再改 owner。验证 Sales 流失分子按当前（=冻结）owner_sales_id 取到 A。
3. **停用 Sales 历史事件**：插 owner_sales_id=salesA 的赢单/流失事件，再把 salesA.status 置 DISABLED。验证 Admin 全局仍计入（不因停用排除）。
4. **跨月签订日期 vs 赢单事件时间**：contract.signed_date=上月某日，但 lead.won_at=本月某日。验证按 won_at 归本月。
5. **今日/本月边界毫秒级**：
   - created_at=今日 00:00:00.000（计入）、昨日 23:59:59.999（不计入）。
   - won_at/lost_at=本月 1 号 00:00:00.000（计入）、上月末 23:59:59.999（不计入）、下月 1 号 00:00:00.000（不计入）。
6. **角色 token**：用 `JwtService.generateToken(account)` 分别生成 admin / salesA / salesB token，验证同一数据集下 Admin 全局 vs Sales 个人口径差异，以及公海数两视角相等。
7. **不写日志验证**：操作前后 `SELECT COUNT(*) FROM system_log`（或按 action 过滤）计数不变。

## Coverage Closure

- [ ] 每个 in-scope 可执行 test point 有 coverage artifact（实现并跑 Green 后回填）。
- [ ] 新增测试已执行并记录结果。
- [ ] Red 测试因预期行为原因失败（端点不存在→404；归一/除零分支缺失）。
- [ ] 未把语法/导入/fixture/环境失败当作有效 Red。
- [ ] 未覆盖点与阻塞显式列出（当前无）。

## Notes

- Uncovered test points: 暂无（前端渲染 `--` 不在本 change 范围）。
- Remaining risks: 事件时归属混用 deal_sales_id（赢单）与 owner_sales_id（流失）两套字段，靠 R4/S4、R5/S5 无主用例与 R4/S2、R5/S2 个人口径用例锁死。
- Execution evidence: 实现后回填测试通过数与命令。
