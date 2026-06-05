# QA Test Report — add-customer-other-leads-hint

## Conclusion

- Overall result: PASS
- Requirement / change ID: `add-customer-other-leads-hint`（capability `customer-other-leads-hint`）
- QA owner: Claude Code（apply 阶段）
- Date: 2026-06-04
- Summary: 落地 PRD §7.6 / §11.6「客户其他业务线索提示」。后端新增只读端点 `GET /leads/customer-other-leads`（按角色裁剪：ADMIN 得「类型+归属+阶段」全量，SALES 仅得本人名下）；公海响应新增 `customerHasOtherLeads` 布尔（SALES 浏览仅得布尔，无详情）。范围限定为「同客户、不同业务类型、排除已流失、保留进行中+已赢单、跨年度」（用户确认降噪）。前端三处呈现：新建/分配弹窗详情列表、公海列表布尔标记、线索详情本人其他线索。spec 五条 Requirement 关键 Scenario 均有通过用例覆盖；§7.6.5 越权负例（SALES 不见他人/公海详情）显式断言通过。后端新增 10 用例、前端新增 5 用例全绿；后端 lead 包+权限 159 用例、前端全量 228 用例、`vue-tsc -b` 均无回归。

## Scope

| Area | In scope? | Notes |
| --- | --- | --- |
| Unit | Yes | `LeadOtherLeadsServiceTest`（Mockito，角色裁剪/映射纯逻辑） |
| API/integration | Yes | 端点 + 公海聚合 + 不阻断，真 MySQL 8.4（共享 `dealtrace` 实例） |
| E2E | No | 关键旅程由 API + 前端组件覆盖，未新增 Playwright |
| Regression | Yes | 后端 lead 包 + PermissionMatrix；前端全量 + 类型检查 |
| Runtime QA validation | No | 仅自动化测试 |

## Requirement Authority / Conflict Review

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Test action | Code action |
| --- | --- | --- | --- | --- | --- | --- |
| 同客户其他业务线索提示 | 无（仅同类查重 `LeadDuplicateService`） | PRD §7.6/§11.6 + 新 spec | adds（独立读侧） | PRD | Add | Implement（新 service/端点/字段） |
| 排除已流失、保留进行中+已赢单、跨年度 | PRD §7.6.1 未提年度/终态 | 用户确认（apply 前问答） | narrows（降噪） | 用户 | Add | Implement（`stage <> 已流失`） |
| Admin 提示字段=类型+归属+阶段（不含电话） | PRD §7.6.3 列举 | PRD §7.6.3 + 用户确认 | conforms（摘要范围；非限制 Admin 总访问权） | PRD/用户 | Add | DTO 结构不含电话；Admin 全访问经既有 `/leads/{id}` 保留 |
| SALES 不可借提示窥探他人私海 | `release` 等 NOT_FOUND 不泄漏约定 | PRD §7.6.5 | conforms | PRD | Add（越权负例） | service 按 owner 裁剪 |
| 提示不阻断写操作 | 创建/认领本不查提示 | PRD §7.6.2/§11.6.4 | conforms | PRD | Add（回归守卫） | 无（行为保持） |

## TDD Summary

| Test point | Source | Red evidence（实际运行输出） | Red 失败原因（断言级） | Green evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- | --- | --- |
| service ADMIN 全量映射 + SALES 仅本人 | §7.6.3/§7.6.5/§7.6.6 | `Tests run: 2, Failures: 2`，`AssertionError: containsExactly` 期望 2/1 项 but 桩返回空 | 桩 `return List.of()` | `Tests run: 2, Failures: 0` | `LeadOtherLeadsServiceTest` | GREEN |
| 端点 ADMIN 详情 / SALES 收窄不泄漏 / 匿名 401 + 排除已流失·保留已赢单·跨年度 | spec R2/R3 + §7.6.5 | `expected:<4> but was:<0>` / `expected:<2> but was:<0>`（端点桩返回空；匿名 401 已过） | 端点桩 `ApiResponse.ok(List.of())` | `Tests run: 3, Failures: 0` | `LeadOtherLeadsControllerTest` | GREEN |
| 公海行 `customerHasOtherLeads` true/false/不泄漏 | §7.6.4 | `Expected: iterable containing [<true>]`（字段桩恒 false；false/不泄漏用例对桩已过） | `PoolLeadView.of(...false)` 桩 | `Tests run: 3, Failures: 0` | `LeadPoolOtherLeadsHintTest` | GREEN |
| 新建弹窗渲染其他线索区块 | §7.6.3 | `AssertionError: expected false to be true`（`.other-leads-hint` 不存在；其余 5 用例过） | 组件未渲染区块 | 6/6 passed | `CreateLeadModal.spec.ts` | GREEN |
| 公海列表 true 行显示文案 | §7.6.4 | true 用例 `expected 0 to have length 1`（false 用例已过） | 视图未渲染 flag | 8/8 passed | `PublicPoolView.spec.ts` | GREEN |
| 详情显示本人其他线索 | §7.6.6 | `expected false`（`.detail-other-leads` 不存在，临时回退模板复现） | 面板未渲染区块 | 32/32 passed | `LeadDetailView.spec.ts` | GREEN |
| 有其他线索时创建/认领不阻断 | §7.6.2/§11.6.4 | 无 Red（写路径本不查提示）—回归守卫 | n/a | `Tests run: 2, Failures: 0` | `LeadOtherLeadsNonBlockingTest` | GREEN（守卫） |

说明：静态语言真 Red 技法（先建最小可编译桩拿断言级失败）见 `references/qa-constitution.md` §Mandatory TDD Rule。所有 Red 均为「断言级行为缺口」（expected≠actual），非编译/缺端点/环境失败。

## Tests Run

| Source | Layer | Test / suite | Command | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| Spec/Design | 单元 | LeadOtherLeadsServiceTest（2） | `mvn test -Dtest=LeadOtherLeadsServiceTest` | PASS | Tests run: 2, Failures: 0 |
| Spec/Design | API/集成 | LeadOtherLeadsControllerTest（3） | `mvn test -Dtest=LeadOtherLeadsControllerTest` | PASS | Tests run: 3, Failures: 0 |
| Spec/Design | API/集成 | LeadPoolOtherLeadsHintTest（3） | `mvn test -Dtest=LeadPoolOtherLeadsHintTest` | PASS | Tests run: 3, Failures: 0 |
| Spec/Design | API/集成 | LeadOtherLeadsNonBlockingTest（2） | `mvn test -Dtest=LeadOtherLeadsNonBlockingTest` | PASS | Tests run: 2, Failures: 0 |
| Spec/Design | 前端单测 | CreateLeadModal/PublicPoolView/LeadDetailView 新增 5 用例 | `vitest run`（对应文件） | PASS | 6/8/32 各文件全绿 |
| Regression | API/集成 | 后端 lead 包 + PermissionMatrix | `mvn test -Dtest='com.dealtrace.lead.**,...PermissionMatrixTest'` | PASS | **159 run, 0 failures, 0 errors, BUILD SUCCESS** |
| Regression | 前端单测 | 全量 | `vitest run` | PASS | 98 suites / 228 tests passed |
| Regression | 前端类型 | `vue-tsc -b` | `vue-tsc -b` | PASS | 0 error（修 `workbench.test.ts` 缺 `customerHasOtherLeads` 字面量） |

## Spec Scenario 覆盖核对

| Spec Requirement / Scenario | 覆盖用例 |
| --- | --- |
| 范围限定：同客户不同类型触发 / 同类不计 / 已流失不计 / 已赢单计入 | `LeadOtherLeadsControllerTest#admin_...excludesLost_keepsWonAndCrossYear`（含跨年度 WON）；`LeadPoolOtherLeadsHintTest#poolRow_falseWhenOnlySameTypeOrLost` |
| Admin 见其他业务线索详情（类型+归属+阶段） | `LeadOtherLeadsServiceTest#admin_seesOtherTypesWithOwnerAndStage`；`LeadOtherLeadsControllerTest#admin_...`；`CreateLeadModal.spec`「展示其他业务线索」 |
| Sales 公海仅得布尔、不泄漏详情 | `LeadPoolOtherLeadsHintTest#poolRow_trueWhenCustomerHasOtherActiveType` + `#poolRow_noOtherLeadDetailLeak`；`PublicPoolView.spec`「仅 true 行展示」 |
| Sales 自有仅见本人、不暴露他人 | `LeadOtherLeadsServiceTest#sales_seesOnlyOwnOtherLeads`；`LeadOtherLeadsControllerTest#sales_narrowedToOwn_noLeakOfOthers`；`LeadDetailView.spec`「§7.6.6」 |
| 协同提示不阻断写操作 | `LeadOtherLeadsNonBlockingTest#create_succeedsWithOtherLeads` / `#claim_succeedsWithOtherLeads` |

## §7.6.5 越权负例结论

`LeadOtherLeadsControllerTest#sales_narrowedToOwn_noLeakOfOthers` 断言：SALES 调用端点的响应**不含**他人销售姓名、不含「公海」项、不含他人阶段（方案报价），仅本人名下两条。`LeadPoolOtherLeadsHintTest#poolRow_noOtherLeadDetailLeak` 断言公海响应不含「异业务持有者」姓名。DTO `LeadOtherLeadView` 结构上不含 `contactPhone`/进度字段，从类型层杜绝泄漏。结论：SALES 无法借本提示获取他人私海归属/联系方式/进度/具体阶段。

## 测试基础设施改动（非弱化断言）

- `frontend handlers.ts`：新增 `leadCustomerOtherLeads` 工厂并入默认 handler 集；`SAMPLE_POOL_LEAD` 补 `customerHasOtherLeads` 字段。
- `LeadDetailView.spec.ts` mountView：在 `leadDetail` 之前注册 `leadCustomerOtherLeads`，因 MSW `*/api/leads/:id` 会按注册序误捕获 `/leads/customer-other-leads`（真实后端 Spring 精确路由无此问题）。仅测试基础设施调整，未改任何既有断言。
- `workbench.test.ts`：`PoolLeadView` 字面量补 `customerHasOtherLeads: false` 以过类型检查。

## Residual Risk / Notes

- 「其他业务线索」跨年度统计：已按用户决策排除已流失降噪；若进行中+已赢单仍偏多，后续可增量加阶段/年度过滤（不破坏现 spec）。
- SALES 角色裁剪在 service Java 层过滤（先查后裁），公海布尔聚合为单次 `IN (customerIds)`；公海每页 ≤50，开销可控。
- 未新增 E2E；该提示的用户旅程由 API + 前端组件用例覆盖，符合本仓 E2E 仅关键旅程约定。
- 共享库 contract/account 外键污染隐患（[[account-wipe-tests-vs-contract-fk]]）：本 change 集成测试**按唯一客户隔离、不做全局清表**，规避了该隐患，未触发相关 ERROR。
