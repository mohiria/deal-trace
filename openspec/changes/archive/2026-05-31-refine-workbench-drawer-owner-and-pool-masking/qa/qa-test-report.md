# QA Test Report

## Conclusion

- Overall result: PASS (前端全绿 + 后端单元绿 + 编译通过)；后端集成层环境阻塞，已书面记录
- Requirement / change ID: refine-workbench-drawer-owner-and-pool-masking
- QA owner: apply 阶段实现 + TDD
- Date: 2026-05-31
- Summary: 修三个相互关联缺陷——①归属显示销售姓名（后端 LeadView 内联 ownerSalesName）；②SALES 公海抽屉脱敏只读 + 认领（前端用脱敏公海数据填充，不调明文详情端点）；③抽屉切换即时刷新、加载失败清空。前端新增 6 条测试先红后绿，全量 23 files/210 tests PASS，vue-tsc 通过；后端 ownerSalesName 解析由单元测试 5/5 覆盖，全模块编译通过。后端集成测试因共享远程库 contract 污染数据 FK 阻断未执行（整套含既有测试均受阻），记为环境阻塞。

## Scope

| Area | In scope? | Notes |
| --- | --- | --- |
| Unit | Yes | 后端 LeadServiceOwnerNameTest（解析）；前端 leads store |
| API/integration | Blocked | LeadControllerDetailListTest 端到端 ownerSalesName 断言已写，受远程库污染阻断 |
| E2E | No | 以前端组件测试 + 用户浏览器验收替代 |
| Regression | Yes | 前端全量 210 tests；后端编译 + 单元 |
| Runtime QA validation | Pending(user) | 重启本地前后端，SALES 浏览器验收交用户 |

## Requirement Authority / Conflict Review

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Test action | Code action |
| --- | --- | --- | --- | --- | --- | --- |
| 线索视图内联归属姓名 | LeadView 仅 ownerSalesId；spec 只内联 customerName | 本 change lead spec MODIFIED | amends | 用户反馈 + lead spec | 加集成+单元断言 | LeadView 内联 ownerSalesName |
| SALES 公海详情可见性 | detailFor 对 SALES 看公海 NOT_FOUND | PRD §7.5 认领前脱敏 | extends（前端不取明文，改用脱敏公海数据） | PRD §7.5 | 加组件测试 | 前端分流，后端 detailFor 不变 |
| 抽屉切换刷新 | :key 重挂但失败残留 | 本 change fe-workbench spec | amends | 本 change spec | 加切换/清空测试 | loadLead 清空 + 分流 |

> 不改 detailFor 对其他 SALES 私海的 NOT_FOUND 裁决，不改脱敏端点划分（保留旧行为）。

## TDD Summary

| Test point | Source / authority | Red evidence | Red failure reason | Green evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- | --- | --- |
| 归属列显示姓名 | fe spec | vitest FAIL：显示 `销售 #7` | 仍用 id 拼串 | 同测试 PASS | `DashboardView.spec.ts#归属列显示销售姓名` | PASS |
| SALES 公海抽屉脱敏不调详情 | PRD §7.5 / fe spec | vitest FAIL：明文 `13912344321` + detailCalls=1 | 抽屉走明文详情端点 | PASS（脱敏 + detailCalls=0） | `DashboardView.spec.ts#公海抽屉脱敏只读` | PASS |
| SALES 公海抽屉认领入口 | fe spec | vitest FAIL：无 drawer-claim | 抽屉无认领入口 | PASS | `DashboardView.spec.ts#公海抽屉提供认领入口` | PASS |
| 抽屉切换不残留 | fe spec | vitest FAIL：残留 `名下客户甲` | loadLead 失败不清空 | PASS（显示公海脱敏） | `DashboardView.spec.ts#抽屉切换即时刷新` | PASS |
| loadLead 失败清空 | fe spec | vitest FAIL：currentLead 未清空 | 失败保留旧值 | PASS | `leads.spec.ts#loadLead 失败清空 currentLead` | PASS |
| 归属姓名解析（有/null/缺失/批量） | lead spec / design D1 | 见下「非干净 Red」 | 集成 Red 被环境阻断 | `LeadServiceOwnerNameTest` 5/5 PASS | `LeadServiceOwnerNameTest` | PASS |

## Non-TDD Exceptions

| Scope | Reason strict TDD does not apply | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| 后端 ownerSalesName 端到端（detail/mine/list） | 集成测试 @BeforeEach 全量清账号撞远程库 contract FK，无法在共享库跑出干净 Red/Green（环境阻塞，非代码） | LeadServiceOwnerNameTest 单元覆盖解析；集成断言已写入 LeadControllerDetailListTest 待清库执行 | 端到端 JSON 字段未在真库回归；解析逻辑已单元覆盖，风险中低 |
| 抽屉只读摘要视觉排版 | 纯样式 | 结构断言 + 用户浏览器验收 | 视觉细节主观 |

## Tests Run

| Layer | Test / suite | Command | Result | Evidence |
| --- | --- | --- | --- | --- |
| 前端单元（受影响） | DashboardView + leads store | `pnpm exec vitest run ...` | PASS | 33/33 |
| 前端全量回归 | 全部 | `pnpm test:unit` | PASS | 23 files / 210 tests |
| 前端类型 | vue-tsc | `pnpm exec vue-tsc -b` | PASS | exit 0 |
| 后端单元 | LeadServiceOwnerNameTest | `mvn -Dtest=LeadServiceOwnerNameTest test` | PASS | 5/5, BUILD SUCCESS |
| 后端集成 | LeadControllerDetailListTest | `mvn -Dtest=LeadControllerDetailListTest test` | BLOCKED | 11/11 ERROR：fk_contract_sales（远程库污染） |

## Tests Not Run / Blockers

| Test / scope | Reason not run | Exact blocker | Required owner action | Residual risk |
| --- | --- | --- | --- | --- |
| 后端集成 ownerSalesName 断言 | BLOCKED | 远程 dealtrace 库 contract 行引用 account，@BeforeEach 全量清账号触发 fk_contract_sales | 清理远程库 contract/lead 联调数据（或用隔离测试库）后重跑 | 中低：端到端字段未真库回归 |

## Regression Scope

- Changed behavior: 归属姓名内联、公海抽屉脱敏只读、抽屉切换刷新/清空。
- Directly impacted old behavior: LeadView 各端点（mine/list/detail/写动作返回）；前端工作台抽屉与归属列。保留：detailFor 私海 NOT_FOUND、脱敏端点划分、认领事务、闭单只读。
- Requirement-driven additions: 前端 6 测试 + 后端单元 5 + 集成 3（待执行）。未删负例、未弱化断言。
- Regression risk level: Low（前端全绿回归；后端仅新增内联字段，编译 + 单元通过）。

## Remaining Risks

- Uncovered: 后端端到端 ownerSalesName（集成层，环境阻塞）；抽屉视觉。
- Blocker: 远程库 contract 污染数据阻断后端集成套件——影响的是整套集成测试，建议清库或引入隔离测试库。
- Manual follow-up: 用户以 SALES 浏览器验收三项；后续清库跑 LeadControllerDetailListTest。

## Final Statement

前端三问题修复以 TDD 落地（5 条先红后绿），全量 210 tests + 类型检查通过；后端 ownerSalesName 内联以单元测试 5/5 验证、全模块编译通过。后端集成层端到端断言已写但受共享远程库 contract 污染数据 FK 阻断未执行，按用户决定记为环境阻塞 + 非 TDD 例外（备选单元验证 + 剩余风险已记录），未私自清库。Overall: PASS（含书面阻塞项）。
