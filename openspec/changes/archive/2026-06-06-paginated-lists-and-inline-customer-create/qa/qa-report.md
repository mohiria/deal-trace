# QA Test Report — paginated-lists-and-inline-customer-create

## Conclusion

- Overall result: PASS（后端全量 `mvn verify` 313 tests / 0 失败 / 0 错误，BUILD SUCCESS；前端全量 231/231 + `vue-tsc` 0 错误；Playwright E2E 10/10。E2E 暴露 1 代码缺陷已修复并加回归用例）
- Requirement / change ID: `paginated-lists-and-inline-customer-create`
- QA owner: mohiria
- Date: 2026-06-06
- Summary: 四个只读列表端点（customer / leads.mine / leads / leads.pool）由「裸数组 + 50 硬上限」改为「分页信封 `{items,total,page,size}` + keyword 全表下推」；`POST /leads` 增加 `newCustomer` 事务内 find-or-create；新增 `GET /leads/mine/stale`；前端三列表页 + 工作台改服务端分页，CustomerSelect/CreateLeadModal 支持内联建客户，工作台今日提醒区块改由后端驱动。

## Scope

| Area | In scope? | Notes |
| --- | --- | --- |
| Unit | Yes | 前端 vitest 组件/工具 + 后端 `PageQueryTest`、`LeadServiceOwnerNameTest`（Mockito） |
| API/integration | Yes | 真 MySQL 8.4（`@SpringBootTest @Transactional @Rollback`）：分页信封、keyword 下推、find-or-create、stale 端点 |
| E2E | Yes | Playwright 真后端（当前构建 :8080）+ 真 MySQL，10 passed / 0 failed；场景优先，不强制 Red-Green |
| Regression | Yes | 既有列表/搜索/创建/公海/工作台断言迁移到信封 + 服务端分页 |
| Runtime QA validation | No | — |

## Requirement Authority / Conflict Review

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Test action | Code action |
| --- | --- | --- | --- | --- | --- | --- |
| 列表 50 行硬上限 + 裸数组 | 既有 spec（customer L113-116、lead L216/L275、frontend-workbench） | 本 change `specs/*` MODIFIED | supersedes | OpenSpec change（用户确认「真服务端分页 + keyword 下推」） | modify（断言迁移到 `items/total`） | 移除上限，返回分页信封 |
| CustomerSelect「仅选既有，无边搜边建」 | `CustomerSelect.vue` 旧注释 + 旧负例 | 本 change `frontend-workbench` MODIFIED「候选为空提供录入新客户入口」 | supersedes | 同上（用户确认「后端 find-or-create 合并端点」） | modify（旧负例反转为正例） | 加 `v-model:newCustomer` + 录入态 |
| 工作台客户端 3-Tab 合并 + 业务类型/阶段筛选 | 既有 `DashboardView` 客户端语义 | 本 change `frontend-workbench`「工作台首屏内嵌线索工作区」（服务端分页、角色作用域、未述类型/阶段筛选） | supersedes | 同上（用户确认「Dashboard 完整服务端改造」） | modify/delete（删合并全部 Tab、月度过滤、类型/阶段筛选测试；新增服务端分页 + 提醒测试） | 双 Tab 服务端分页 + 提醒区块 |
| 客户端派生「长期未跟踪」阈值 | `utils/workbench.ts` `staleOwnedLeads` | 本 change `frontend-workbench`「前端 SHALL NOT 自行重算阈值」+ lead ADDED「我的长期未跟踪线索查询」 | supersedes | 同上 | delete（删 `workbench.ts` + test） | 改调后端 `GET /leads/mine/stale` |

## TDD Summary

| Test point | Source / authority | Red evidence | Red failure reason | Green evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- | --- | --- |
| 客户搜索返回分页信封 + keyword 全表 | customer spec MODIFIED | `mvn -Dtest=CustomerControllerSearchTest test` FAIL | `No value at JSON path "$.data.items"`（data 为裸数组） | 同命令 PASS（7/7） | `CustomerControllerSearchTest` | PASS |
| leads.mine/all 分页信封 + keyword join | lead spec MODIFIED | `mvn -Dtest=LeadListPaginationTest,LeadControllerDetailListTest test` FAIL | 断言级：data 仍裸数组 | 同命令 PASS（5/5 + 11/11） | `LeadListPaginationTest` | PASS |
| 公海分页信封 + keyword | lead spec MODIFIED | `LeadPoolListTest` FAIL（data 裸数组） | 断言级 | PASS（5/5） | `LeadPoolListTest` | PASS |
| find-or-create（USCI 仲裁 / 无孤儿 / 互斥） | lead ADDED「新建线索内联创建客户」 | `mvn -Dtest=LeadCreateInlineCustomerTest test` FAIL（5/8） | `status 200↔400`、`$.code DUPLICATE_CUSTOMER\|DUPLICATE_ACTIVE_LEAD` 实得 `VALIDATION_ERROR` | 同命令 PASS（8/8），`LeadControllerCreateTest` 15/15 无回归 | `LeadCreateInlineCustomerTest` | PASS |
| 我的长期未跟踪线索查询 | lead ADDED「我的长期未跟踪线索查询」 | `mvn -Dtest=LeadStaleListTest test` FAIL（2/2） | `Status expected:<200> but was:<500>`（端点未实现） | 同命令 PASS（2/2） | `LeadStaleListTest` | PASS |
| 前端三列表页服务端分页 + keyword 回第 1 页 | frontend-workbench MODIFIED | 翻页/keyword 断言更新（信封 mock） | 断言级：客户端 filter 不再命中靠后页 | `npx vitest run`（CustomersView/MyLeadsView/PublicPoolView）PASS | `src/views/*.spec.ts` | PASS |
| 内联建客户入口 + 提交 newCustomer + 不发预检 | frontend-workbench MODIFIED | 旧负例 `.cs-create-shortcut 不存在` 反转 | 断言级：候选为空应出现 `.cs-create-new` | `CustomerSelect.spec`/`CreateLeadModal.spec` PASS（6/6、8/8） | `src/components/*.spec.ts` | PASS |
| 工作台服务端分页 + tab 计数取 total + 提醒后端下推 | frontend-workbench MODIFIED/ADDED | `DashboardView.spec` 重写 | 断言级：tab 计数应取后端 total、提醒来自后端端点 | `DashboardView.spec` PASS（24/24） | `src/views/DashboardView.spec.ts` | PASS |
| 列表整页全公海（owner 全 null）不应 500（E2E 暴露的回归） | lead spec「线索详情与列表的权限隔离」+ 缺陷修复 | `mvn -Dtest=LeadListPaginationTest#admin_all_pageOfAllPoolLeads_doesNotError test` FAIL | `Status expected:<200> but was:<500>`（toViews 对不可变空映射 null 键 get → NPE） | 同命令 PASS（6/6） | `LeadListPaginationTest#admin_all_pageOfAllPoolLeads_doesNotError` | PASS |

## Non-TDD Exceptions

| Scope | Reason strict TDD does not apply | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| 端到端验收（>50 翻页/搜索、内联建客户、tab 计数/提醒跨全量） | 关键用户旅程，场景优先，不强制 Red-Green | Playwright 10/10（真后端 + 真 MySQL） | 低：各分层已覆盖核心契约 |
| 后端 find-or-create 并发同 USCI 竞态 | 多事务并发用例会污染共享远程库（见项目 memory：TRUNCATE 偷 bootstrap admin） | 唯一约束兜底 + catch-then-find 单测路径已覆盖（同名复用/异名拒）；并发分支由 DB 唯一约束保证 | 低：唯一约束是数据库级强约束 |

## Tests Run

| Source | Layer | Test / suite | Command | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| Both | Unit | 前端全量 | `cd frontend && npx vitest run` | PASS | 25 files / 231 tests |
| Both | Unit | 类型检查 | `cd frontend && npx vue-tsc -b` | PASS | 0 错误 |
| Both | API/integration | inline customer | `mvn -Dtest=LeadCreateInlineCustomerTest test` | PASS | 8/8 |
| Both | API/integration | stale endpoint | `mvn -Dtest=LeadStaleListTest test` | PASS | 2/2 |
| Regression | API/integration | create 无回归 | `mvn -Dtest=LeadControllerCreateTest test` | PASS | 15/15 |
| Both | API/integration | 全量后端 | `cd backend && mvn verify` | PASS | 313 tests / 0 失败 / 0 错误（含新增 all-pool 回归用例；toViews 修复后复跑） |
| Design | E2E | Playwright 全量 | `E2E_ADMIN_* E2E_SALES_* npx playwright test` | PASS | 10 passed / 0 failed / 0 skipped（真后端 :8080 当前构建 + 真 MySQL） |

## Regression Scope

- Changed behavior: 4 个只读列表端点响应形状（破坏式：裸数组 → 分页信封）；`POST /leads` 入参（`customerId` 可选 + `newCustomer`）；新增 stale 端点；前端 store/视图重构。
- Directly impacted old behavior: 所有既有列表/搜索断言、CustomerSelect「仅选既有」负例、Dashboard 客户端 3-Tab/筛选/分页语义。
- Regression impact source: Lightweight design（`qa/test-design.md`）。
- Requirement-driven test additions/modifications/deletions: 新增 `LeadListPaginationTest`/`LeadCreateInlineCustomerTest`/`LeadStaleListTest`；重写 CustomersView/MyLeadsView/PublicPoolView/DashboardView/CustomerSelect/CreateLeadModal spec；删除 `utils/workbench.ts(+test)`（spec 禁前端重算阈值）。
- Regression risk level: Medium（破坏式响应形状，回归面广）；已由分层测试 + 全量回归覆盖。

## 缺陷与失败分析（E2E 暴露）

| 问题 | 类型 | 根因 | 处置 | 回归覆盖 |
| --- | --- | --- | --- | --- |
| `GET /leads`（及所有走 `toViews` 的列表端点）当整页线索全部为公海（owner 全 null）时返回 500 | 代码缺陷（单测盲区，E2E 捕获） | `LeadService.loadOwnerNames` 无归属时返回不可变 `Map.of()`；`toViews` 对每行 `ownerNames.get(l.getOwnerSalesId())` 传入 null 键，不可变 Map 对 null 键 `get` 抛 NPE | `toViews` 改为 null 键短路（`id == null ? null : map.get(id)`）；新增回归 `LeadListPaginationTest#admin_all_pageOfAllPoolLeads_doesNotError`（Red 证据：status 200 vs 500 / NPE） | `LeadListPaginationTest` 6/6 + 全量 verify |
| E2E `.cs-search`/`.lead-*` 定位到 2 元素 | 测试脆弱（既有，本轮才执行到该步） | AppShell 全局 `CreateLeadModal` 与视图级模态并存，旧 spec 未加 `:visible`（与 `system-log-flow.spec` 约定不一致） | `admin-flow`/`customer-flow` 线索模态交互统一加 `:visible` | E2E 10/10 |
| E2E 断言归属为 `销售 #`（id 拼串） | 陈旧断言（与 design D1 冲突） | 现行/既定行为展示销售姓名（见 `DashboardView.spec`「显示销售姓名而非 销售 #id」） | `admin-flow` 改断言已分配 Sales 姓名可见 | E2E 10/10 |
| E2E 期望 `data-test="workbench-reminders"` 常驻不命中 | 代码（test-id 不符既定契约 + 区块条件渲染） | 实现用 `data-test="reminders"` 且仅有条目时渲染；既有 E2E 契约要求 `workbench-reminders` 区块常驻 | 区块改名 `workbench-reminders` 并常驻（无条目显示「今日暂无待办提醒」）；同步单测 | `DashboardView.spec` 24/24 + E2E 10/10 |

> E2E 运行前置（一次性，已授权）：重置共享库 ADMIN 口令为 `Adm1n!Test`；经 admin API 建固定 Sales `e2e.sales@dealtrace.test`/`Pw123456!`。E2E 会向共享库写入真实数据且不回滚（留痕）。

## Remaining Risks

- 并发同 USCI 仅靠 DB 唯一约束 + catch-then-find，未做多线程集成测试（避免污染共享库）。
- 工作台搜索仅下推 keyword；业务类型/阶段筛选已按 spec 移除（如后续需要服务端筛选需另立 change）。
- E2E 在共享远程库留下真实数据（未回滚）；ADMIN 口令已重置为 `Adm1n!Test`、新增固定 Sales `e2e.sales@dealtrace.test`。如需恢复原 ADMIN 口令需另行处理。

## Final Statement

阶段 1-5 全部完成并以 TDD 落地：每个会改生产代码的能力均先取得断言级 Red（贴出运行输出），再实现至 Green，并跑回归确认无破坏。后端全量 `mvn verify` 313 tests / 0 失败 / 0 错误（BUILD SUCCESS）；前端全量 231/231 绿、`vue-tsc` 0 错误；Playwright E2E 10 passed / 0 failed / 0 skipped（真后端当前构建 + 真 MySQL，admin+sales）；`openspec validate --strict` 通过。E2E 额外暴露并修复 1 个代码缺陷（整页全公海列表 NPE→500，已加回归用例 `LeadListPaginationTest#admin_all_pageOfAllPoolLeads_doesNotError`），并修正 4 处陈旧/脆弱 E2E 断言。全部任务闭环，无未决阻塞。
