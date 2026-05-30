# Lightweight Test Design — frontend-customer

## Context

- Requirement / Spec: `openspec/changes/frontend-customer/specs/frontend-workbench/spec.md`（4 条 ADDED 需求：浏览/搜索客户、创建客户、可搜索下拉选择器、新建线索+查重预检）
- Change summary: 把 `customers` 占位路由落成真实客户管理页（列表/搜索/创建），抽出客户可搜索下拉选择器，补齐"新建线索"（含查重预检与历史流失提示）。后端零改动。
- Target modules / APIs / pages: `src/api/customers.ts`、`src/api/leads.ts`（增量）、`src/utils/lead.ts`（增量 `isValidContactPhone`）、`src/components/CustomerSelect.vue`、`src/views/CustomersView.vue`、`src/router/index.ts`；消费 `POST /customers`、`GET /customers?keyword=`、`POST /leads`、`GET /leads/duplicate-check`
- Test environment / constraints: Vitest + @vue/test-utils + MSW（axios 边界，`onUnhandledRequest:'error'`）；E2E 用 Playwright 真后端（缺凭据 `test.skip`）。禁 Tailwind，沿用 Arco + `--dt-*` token。

## Input Sources Checked

- [x] Active Spec / PRD / acceptance criteria / issue
- [x] Existing behavior baseline: tests / code / old Spec / API contract
- [x] Data model / field rules / CRUD matrix
- [x] API contract / auth rules / error shape
- [x] UI states / user roles / user paths
- [x] Code structure / changed code / dependency graph
- [x] Existing tests / historical defects / flaky areas
- [x] Test data / credentials / mocks / CI constraints

## Requirement Authority / Conflict Gate

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Result |
| --- | --- | --- | --- | --- | --- |
| USCI 归一化/校验位 | customer spec R3（后端权威）+ 后端 `CustomerService` | frontend-customer spec R2 | extends（前端只校验非空，不复算） | customer spec + tech-arch | Proceed |
| 新建线索归属（指定具体 Sales） | lead spec R(归属) | frontend-customer proposal Non-Goals | extends（本批次只做归己/公海） | proposal + frontend-lead-flow 先例 | Proceed |
| 查重三态 / 历史流失提示 | lead spec R(查重) + R(预检端点) | frontend-customer spec R4 | extends（前端预检+提示，后端兜底） | lead spec + CLAUDE.md | Proceed |

无 `conflicts` 项。

## Test Points

| Test point | Source / authority | Design method | Test layer | Input / precondition | Expected result | Assertion target | Priority | Coverage artifact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| searchCustomers 无关键词命中 GET /customers 不带 query | spec R1 / 后端契约 | 契约测试 | Unit | 不传 keyword | 请求无 `keyword` query，返回 CustomerView[] | 请求 URL + 返回类型 | P0 | `src/api/customers.spec.ts#searchNoKeyword` |
| searchCustomers 带关键词命中 GET /customers?keyword= | spec R1 | 契约测试 | Unit | keyword="建筑" | 请求带 `keyword=建筑` | 请求 query | P0 | `src/api/customers.spec.ts#searchKeyword` |
| createCustomer 原样提交 name/usci 命中 POST /customers | spec R2 / D8 | 契约测试 | Unit | name/usci 原始输入 | body 与输入一致（不前端归一化） | 请求 body | P0 | `src/api/customers.spec.ts#createBody` |
| 客户创建错误归一为 ApiError(code) | spec R2 / 拦截器 | 异常路径 | Unit | 后端 DUPLICATE_CUSTOMER | reject ApiError code=DUPLICATE_CUSTOMER | 错误类型+code | P0 | `src/api/customers.spec.ts#duplicateError` |
| createLead 命中 POST /leads | spec R4 | 契约测试 | Unit | 合法 payload | body 透传 | 请求 body | P0 | `src/api/leads.spec.ts#createLead` |
| duplicateCheck 命中 GET /leads/duplicate-check?customerId=&businessType= | spec R4 / lead spec R6 | 契约测试 | Unit | customerId+businessType | query 正确，返回 {canCreate,blockingReason,historicalLost} | 请求 query + 返回形状 | P0 | `src/api/leads.spec.ts#duplicateCheck` |
| isValidContactPhone 手机号/座机/非法 | lead spec R3 / D7 | 等价类+边界 | Unit | 各类号码 | 合法 true / 非法 false | 布尔判定 | P0 | `src/utils/lead.spec.ts#isValidContactPhone` |
| 客户列表无关键词渲染后端返回 | spec R1 | 状态渲染 | 组件 | poolless customerList | 表格渲染 name/usci/createdAt | DOM 文本 | P0 | `src/views/CustomersView.spec.ts#listRender` |
| 关键词搜索去抖后调 searchCustomers | spec R1 / D2 | 交互 | 组件 | 输入关键词 | 去抖后发请求渲染匹配 | 请求触发+DOM | P1 | `src/views/CustomersView.spec.ts#searchDebounce` |
| 关键词无命中空态 | spec R1 | 状态渲染 | 组件 | 空结果 | 空态文案，不伪造行 | DOM 空态 | P1 | `src/views/CustomersView.spec.ts#emptyState` |
| 创建客户 name/usci 空即时拦截不发请求 | spec R2 | 即时校验 | 组件 | 空白输入提交 | 阻止+提示，不发请求 | 请求未发出 | P0 | `src/views/CustomersView.spec.ts#createBlockEmpty` |
| 创建成功后入列表 | spec R2 | 状态渲染 | 组件 | 合法提交 success | 新客户出现在列表 | DOM 列表 | P0 | `src/views/CustomersView.spec.ts#createSuccess` |
| DUPLICATE_CUSTOMER 提示已存在 | spec R2 | 异常路径 | 组件 | 后端 DUPLICATE_CUSTOMER | "客户已存在"语义，不伪造成功 | Message.error/warning | P0 | `src/views/CustomersView.spec.ts#createDuplicate` |
| VALIDATION_ERROR 透传 message 不暴露字符位置 | spec R2 / D8 | 异常路径 | 组件 | 后端 VALIDATION_ERROR | 展示后端 message | Message 内容 | P1 | `src/views/CustomersView.spec.ts#createValidation` |
| 选择器关键词搜索+选中既有客户 | spec R3 / D4 | 交互 | 组件 | 输入关键词选中 | v-model 暴露 customerId + emit 客户对象 | emit 值 | P0 | `src/components/CustomerSelect.spec.ts#selectExisting` |
| 选择器无匹配不提供"新建客户"捷径 | spec R3 | 负例 | 组件 | 无匹配关键词 | 无新建入口 | DOM 无该入口 | P1 | `src/components/CustomerSelect.spec.ts#noCreateShortcut` |
| 新建线索必填/电话非法即时拦截 | spec R4 / D7 | 即时校验 | 组件 | 缺字段/非法电话提交 | 阻止+提示，不发请求 | 请求未发出 | P0 | `src/views/CustomersView.spec.ts#leadBlockInvalid` |
| 表单不渲染业务年度/初始阶段 | spec R4 | 负例 | 组件 | 表单渲染 | 无该两字段 | DOM 无字段 | P1 | `src/views/CustomersView.spec.ts#noYearStage` |
| 预检阻塞(ACTIVE/WON)禁用提交 | spec R4 / lead spec R6 | 决策表 | 组件 | duplicateCheck canCreate=false | 提示阻塞，不发创建请求 | 提交禁用+无请求 | P0 | `src/views/CustomersView.spec.ts#leadDuplicateBlock` |
| 仅历史流失允许新建并展示原因+时间 | spec R4 / CLAUDE.md | 状态渲染 | 组件 | historicalLost 非空 canCreate=true | 展示每条 loseReason+lostAt，允许提交 | DOM 历史区块 | P0 | `src/views/CustomersView.spec.ts#leadHistoricalLost` |
| Sales 归己/放入公海 | spec R4 | 交互 | 组件 | SALES 选公海提交 | createLead body 带 assignToPool | 请求 body | P1 | `src/views/CustomersView.spec.ts#leadSalesPool` |
| 创建被后端业务错误拒绝不伪造成功 | spec R4 / D6 | 异常路径 | 组件 | 后端 VALIDATION_ERROR/DUPLICATE_* | 展示语义不伪造 | Message + 无成功态 | P1 | `src/views/CustomersView.spec.ts#leadCreateError` |

## TDD Candidates

| Test point | Initial failing test | Why it should fail before implementation | Expected Red failure reason | Minimal behavior to pass | Related regression |
| --- | --- | --- | --- | --- | --- |
| customers API 契约 | `customers.spec.ts` | `src/api/customers.ts` 不存在 | import 解析后函数未定义 → 行为断言失败 | 实现 searchCustomers/createCustomer | leads.ts 不受影响 |
| leads 增量 createLead/duplicateCheck | `leads.spec.ts` 增量 | 函数未导出 | 调用未定义函数 | 追加两函数 | 既有 leads 函数回归 |
| isValidContactPhone | `lead.spec.ts` 增量 | 函数未导出 | 调用未定义 | 实现正则纯函数 | 既有 utils 回归 |
| CustomersView 列表/创建/线索 | `CustomersView.spec.ts` | 组件不存在 | 挂载失败/断言失败 | 实现视图 | 路由占位替换 |
| CustomerSelect 选择器 | `CustomerSelect.spec.ts` | 组件不存在 | 挂载失败 | 实现组件 | 无 |

策略：API/utils 走严格 Red（纯函数/契约，Red 原因为行为而非语法）。组件测试先红（组件未实现→挂载失败属环境性，故先建最小组件骨架再断行为，确保 Red 因断言失败而非 import）。

## E2E Scenarios

| Scenario | Persona / role | Preconditions | User path | Critical assertions | Cleanup | Evidence on failure |
| --- | --- | --- | --- | --- | --- | --- |
| 客户→线索录入闭环 | SALES | 真后端可用+凭据 | 创建客户→搜索到→选择器选中→新建线索→我的线索可见 | 客户入列表；线索出现在我的线索 | 后端数据由测试库隔离 | screenshot / trace / network log |

缺 E2E 凭据时 `test.skip`（与既有 `lead-flow.spec.ts` 一致）。

## Non-TDD Exceptions

| Scope | Reason strict TDD does not apply | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| 纯样式（`--dt-*` token / scoped CSS） | 低风险展示 | 视觉沿用既有视图样式 | 低 |
| 路由占位替换（D10） | 配置改动 | 既有 guards 测试覆盖 + build 通过 | 低 |

## Prerequisite Blockers

| Blocker | Affected test point | Required owner action | Status |
| --- | --- | --- | --- |
| 无 | — | — | RESOLVED |

## Coverage Closure

- [x] Each in-scope executable test point has a coverage artifact after prerequisites are available.
- [ ] New or modified tests were executed and results were recorded.（实现阶段填充 qa-test-report.md）
- [ ] Red tests failed for the expected behavior reason when strict TDD applies.（实现阶段记录 Red 证据）
- [x] Syntax/import/fixture/setup/env failures not counted as valid Red evidence.
- [ ] Commands/reports/logs recorded as execution evidence.（qa-test-report.md）
- [x] Behavioral evidence describes what assertion proved.
- [x] Coverage evidence maps each test point to a project-relative test path.
- [x] Uncovered test points and blockers listed explicitly.
- [x] Requirement conflicts resolved（无冲突）.
- [x] Runtime QA validation treated only as smoke evidence.

## Notes

- Uncovered test points: 并发查重窗口（预检通过但提交冲突）仅能在真后端复现，组件层只断"收到 DUPLICATE_* code → 提示不伪造"；真并发交后端既有测试。
- Remaining risks: 前端电话正则与后端规则可能漂移 → 后端 VALIDATION_ERROR 兜底；客户搜索上限 50 由后端裁决，前端达上限引导细化关键词。
- Execution evidence: 见 qa-test-report.md（实现阶段）。
