# QA Test Report — dashboard 指标看板

## Conclusion

- Overall result: PASS
- Requirement / change ID: `dashboard`（PRD §7.12 / §11.10；`openspec/changes/dashboard/specs/dashboard/spec.md`）
- QA owner: vibe-coding-qa（apply 阶段）
- Date: 2026-05-29
- Summary: 新增只读 `GET /api/dashboard`，按角色分流 Admin 全局 / Sales 个人口径，4 指标全部按 spec 落地。新增 24 个测试（8 单元 + 16 集成）全绿；全量回归 237/237 绿，零回归。无新增 Flyway 迁移，未改动任何既有测试期望。

## Evidence Guide

执行命令：`mvn test`（真 MySQL 8.4，禁 H2 禁 mock）。

## Scope

| Area | In scope? | Notes |
| --- | --- | --- |
| Unit | Yes | 时间窗 / 归一 / 角色分流（`DashboardServiceTest`，Mockito，无 Spring 上下文） |
| API/integration | Yes | 全口径真 MySQL（`DashboardControllerTest`） |
| E2E | No | 前端不在本 change 范围 |
| Regression | Yes | 全量 237 用例 |
| Runtime QA validation | No | — |

## Requirement Authority / Conflict Review

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Test action | Code action |
| --- | --- | --- | --- | --- | --- | --- |
| 4 指标聚合查询 | 无（全新只读入口） | PRD §7.12 / spec dashboard | extends | PRD | Add | Implement |
| 事件时归属取数 | lead-closure：`contract.deal_sales_id` 快照 + `won_at`/`lost_at` | spec D1 | extends（只读复用，不改写闭单） | PRD §7.12-12 | Add | Keep（不动闭单代码） |

无 conflicts；未修改/删除任何既有测试。

## TDD Summary

| Test point | Source / authority | Red evidence | Red failure reason | Green evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- | --- | --- |
| 未登录 401 | spec R1/S1 | 端点不存在时 404 | 行为缺口（路由未建立） | `mvn test -Dtest=DashboardControllerTest` PASS | `DashboardControllerTest#unauthenticated_returns401` | PASS |
| 金额空集=0 | spec R4/S5 / design D3 | 未归一时 SUM 返回 null | 行为缺口（归一逻辑缺失） | `mvn test -Dtest=DashboardServiceTest` PASS | `DashboardServiceTest#wonAmount_emptyNormalizedToZero` + `DashboardControllerTest#wonAmount_emptyIsZero` | PASS |
| 流失率分母0=null | spec R5/S3 / design D3 | 未实现 null 分支 | 行为缺口（除零/分支缺失） | 同上 PASS | `DashboardServiceTest#lossRate_zeroDenominatorIsNull` + `DashboardControllerTest#lossRate_zeroDenominatorIsNull` | PASS |

> 说明：取数 SQL 口径正确性以 API/集成层（真 MySQL）为主要证据，纯函数（时间窗 / 归一 / 分流）补单元层。

## Tests Run

| Layer | Test / suite | Command | Result | Evidence |
| --- | --- | --- | --- | --- |
| Unit | `DashboardServiceTest`（8） | `mvn test -Dtest=DashboardServiceTest` | PASS | Tests run: 8, Failures: 0, Errors: 0 |
| API/integration | `DashboardControllerTest`（16） | `mvn test -Dtest=DashboardControllerTest` | PASS | Tests run: 16, Failures: 0, Errors: 0（38.39s，真 MySQL） |
| Regression | 全量 | `mvn test` | PASS | Tests run: 237, Failures: 0, Errors: 0 |

## Coverage Summary

| Test point | Layer | Behavioral evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- |
| 未登录 401 | API | 无 token → 401 | `DashboardControllerTest#unauthenticated_returns401` | COVERED |
| 两角色 200 + 不写日志 | API | system_log 计数不变 | `DashboardControllerTest#bothRoles_return200_withAllMetrics_noSystemLog` | COVERED |
| 今日新增 全局 vs 个人 | API | Admin=4 / SalesA=2 | `DashboardControllerTest#today_adminCountsGlobal_salesCountsOwnOnly` | COVERED |
| 今日边界（昨日末 / 零点） | API | 昨日末不计、今日零点计 | `DashboardControllerTest#today_yesterdayBoundaryExcluded_midnightIncluded` | COVERED |
| 公海数 进行中4态 / 排除有主与结束 / 双视角同值 | API | 4=4 | `DashboardControllerTest#openSea_countsNullOwnerActive_ownedAndEndedExcluded_salesEqualsAdmin` | COVERED |
| 赢单金额 全局/个人/无主只进全局 | API | Admin=150000.50 / A=80000.00 | `DashboardControllerTest#wonAmount_adminSumsGlobal_salesByDealSales_ownerlessGlobalOnly` | COVERED |
| 赢单按 won_at 而非签订日 | API | 签订上月 won_at 本月 → 计本月 | `DashboardControllerTest#wonAmount_anchorsOnWonAtNotSignedDate_crossMonth` | COVERED |
| 金额空集=0 | API+Unit | SUM null → 0 | `wonAmount_emptyIsZero` / `wonAmount_emptyNormalizedToZero` | COVERED |
| 上月赢单排除 | API | 上月末 → 0 | `DashboardControllerTest#wonAmount_lastMonthExcluded` | COVERED |
| 停用 Sales 赢单仍进全局 | API | 停用后金额仍计 | `DashboardControllerTest#wonAmount_disabledSalesStillCountedInGlobal` | COVERED |
| 流失率 Admin 2/5 | API | 0.4 | `DashboardControllerTest#lossRate_adminGlobal_twoLostThreeWon` | COVERED |
| 流失率 Sales 事件时归属 1/4 | API | 0.25 | `DashboardControllerTest#lossRate_salesByEventOwner` | COVERED |
| 流失率分母0=null | API+Unit | null | `lossRate_zeroDenominatorIsNull`（两层） | COVERED |
| 仅赢单=0 非 null | API+Unit | 0 | `lossRate_onlyWonIsZeroNotNull`（两层） | COVERED |
| 无主流失只进全局 | API | Admin 计1 / Sales 计0 | `DashboardControllerTest#lossRate_ownerlessLostGlobalOnly` | COVERED |
| 停用 Sales 结束事件仍进全局 | API | 停用后仍计 | `DashboardControllerTest#lossRate_disabledSalesEndedEventsStillInGlobal` | COVERED |
| 时间窗计算（今日 / 本月 / 跨年） | Unit | 起含止开端点 | `DashboardServiceTest#today_*` / `thisMonth_*` | COVERED |
| 角色分流 me 取值 | Unit | Admin null / Sales id；公海恒全局 | `DashboardServiceTest#scope_adminPassesNullMe_salesPassesOwnId` | COVERED |

## Regression Scope

- Changed behavior: 仅新增只读端点，无既有行为变更。
- Directly impacted old behavior: 无（不触碰 lead/contract/closure 写路径）。
- Requirement-driven test additions: +24（无修改 / 无删除既有测试）。
- Regression risk level: Low
- Selected regression tests: 全量 237（一次跑通），证明新增聚合查询未影响任何既有 capability。

## Remaining Risks

- Uncovered test points: 无（前端渲染 `--` 不在本 change 范围）。
- Unresolved prerequisite blockers: 无。
- Requirement authority conflicts: 无。
- 已知前提依赖：design D1「流失后 owner 冻结」依赖 PRD §7.7 闭单只读硬约束；若未来放开闭单只读，须重审本指标。
- 注意事项：集成测试不可与 dev backend smoke 并发共用 dealtrace 实例（项目 memory）。

## Final Statement

dashboard capability 按 spec 全部落地并验证：8 单元 + 16 集成新增用例全绿，全量回归 237/237 绿。所有 spec scenario（含今日/本月毫秒边界、金额空集=0、流失率分母0=null/分子0=0、无主线索不计 Sales、停用 Sales 历史事件计入全局、公海双视角同值、未登录 401、不写系统日志）均有对应覆盖证据。无新增 Flyway 迁移，无既有测试期望改动。结论 PASS。
