# 轻量测试设计

## Context

- Requirement / Spec: `openspec/changes/restore-prototype-frontend-experience/specs/frontend-workbench/spec.md`
- Change summary: 按 `prototype/dealtrace-workbench.html` 还原前端外壳、销售工作台、线索抽屉、客户管理页、用户管理页的视觉与交互效果；不修改后端接口、schema、权限模型和业务语义。
- Target modules / APIs / pages: `AppShell.vue`、`DashboardView.vue`、`LeadDetailPanel.vue`、`CustomersView.vue`、`UsersView.vue`、现有 `leads` / `dashboard` / `customers` / `accounts` API client。
- Test environment / constraints: 前端使用 Vitest + Vue Test Utils + MSW；E2E 使用 Playwright。严格 TDD 适用于抽屉状态、tab/filter、权限/只读状态、表单反馈等组件行为；纯 CSS 视觉还原以非 TDD 例外记录，并通过浏览器可见检查验证。

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
| 工作台抽屉默认隐藏、点击线索后打开、可关闭 | 归档 `2026-05-30-workbench-lead-hub` 要求工作台抽屉承载详情；当前草稿已默认隐藏但缺少关闭入口 | active spec: `工作台线索详情抽屉默认隐藏且可关闭`；用户明确反馈“抽屉默认是不显示的呀，点击线索才会显示”，并要求支持关闭 | amends | 当前 active spec + 用户确认 | Proceed |
| 工作台三类线索视图 | 当前草稿已有 `mine/pool/ended`，旧页面仍有独立线索页 | active spec: `销售工作台按原型呈现指标与三类线索视图` | extends | active spec | Proceed |
| 抽屉不展示系统日志 | 归档 `workbench-lead-hub` 曾声明无后端读能力时不展示系统日志；原型里有静态系统日志块 | active spec: `抽屉不展示未交付的系统日志` | extends | active spec + design D5 | Proceed |
| 客户/用户页面视觉一致性 | 现有页面已有业务行为和基础样式 | active spec: `客户与用户管理页面保持原型一致的视觉与交互语言` | extends | active spec | Proceed |
| 中文可读与 UTF-8 | 项目要求中文文档/界面，PowerShell 输出可能乱码 | active spec: `中文界面文本保持 UTF-8 可读`；`AGENT.md` 中文约束 | extends | active spec + `AGENT.md` | Proceed |

## Test Points

| Test point | Source / authority | Design method | Test layer | Input / precondition | Expected result | Assertion target | Priority | Coverage artifact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 工作台初始不显示详情抽屉 | active spec: 初次进入工作台不显示详情抽屉 | 状态转换 | Unit/component | Dashboard API、线索列表、公海列表加载成功；未点击任何线索 | `lead-drawer` 不存在，无选中行 | DOM 中不存在 `[data-test="lead-drawer"]`，无 `tr.selected` | P0 | 待补：`frontend/src/views/DashboardView.spec.ts#does not show lead drawer before selecting a lead` |
| 点击线索打开抽屉并选中行 | active spec: 点击线索打开详情抽屉 | 状态转换 | Unit/component | 表格存在一条线索 | 点击线索名称或详情按钮后抽屉出现，对应行选中 | `[data-test="lead-drawer"]` 存在；对应行 class 包含 `selected` | P0 | 待补：`frontend/src/views/DashboardView.spec.ts#opens drawer and selects row when lead is clicked` |
| 关闭抽屉清空选中态且不触发写请求 | active spec: 关闭抽屉后清空选中状态 | 状态转换 + 副作用检查 | Unit/component | 抽屉已打开 | 点击关闭后抽屉隐藏，选中态清空；没有新增进度/阶段/赢单等写请求 | DOM 状态；MSW 写接口调用计数为 0 或 store action 未调用 | P0 | 待补：`frontend/src/views/DashboardView.spec.ts#closes drawer and clears selected lead without writes` |
| tab 切换展示不同数据源 | active spec: 三类线索视图 | 决策表 | Unit/component | `myLeads/allLeads/pool` 均有数据，本月结束有匹配/不匹配记录 | 切换 tab 后表格展示对应数据源或派生结果 | 表格客户名、tab count、空态 | P0 | 待补：`frontend/src/views/DashboardView.spec.ts#switches workbench tabs using existing store data` |
| 筛选不重算四个指标 | active spec: 筛选不会修改看板指标 | 副作用检查 | Unit/component | Dashboard 指标已加载，表格有多条数据 | 搜索/筛选后表格变化，指标值保持 dashboard 返回值 | 指标 data-test 文本保持不变 | P1 | 待补：`frontend/src/views/DashboardView.spec.ts#filters rows without changing metrics` |
| 结束线索详情只读 | active spec: 已结束线索呈现只读详情；PRD 闭单只读 | 决策表 | Unit/component | 当前线索 stage 为已赢单或已流失 | 不展示新增进度、阶段、赢单、流失、退回、分配、回收、转移入口 | 操作按钮不存在；进度历史可见 | P0 | 待补：`frontend/src/components/LeadDetailPanel.spec.ts#renders ended lead as readonly` |
| 抽屉不展示系统日志 | active spec: 抽屉不展示未交付系统日志 | 负向检查 | Unit/component | 任意线索详情加载成功 | 不出现系统日志区或入口 | 文本/测试标识不存在 | P1 | 待补：`frontend/src/components/LeadDetailPanel.spec.ts#does not render system logs` |
| 客户重复反馈不伪造成成功 | active spec: 客户重复反馈 | 错误路径 | Unit/component | `createCustomer` 返回 `DUPLICATE_CUSTOMER` | 弹窗保留，展示重复反馈，不把客户插入列表 | message/error 行为，列表未新增 | P0 | 待补：`frontend/src/views/CustomersView.spec.ts#keeps modal open on duplicate customer` |
| 线索查重阻断状态 | active spec: 线索查重反馈 | 等价类 + 错误路径 | Unit/component | `duplicateCheck` 返回 `canCreate=false` | 展示阻断提示，点击创建不调用 `createLead` | 阻断提示 DOM；POST 调用计数为 0 | P0 | 待补：`frontend/src/views/CustomersView.spec.ts#blocks lead creation when duplicate check rejects` |
| 用户管理不展示密码字段且自身不能停用 | active spec + 既有 frontend-workbench 用户管理要求 | 权限矩阵 | Unit/component | Admin 当前用户与账号列表同时存在 | 表格不展示密码字段；当前用户行不显示停用按钮 | 表格列/文本；自身行操作为占位或无停用按钮 | P0 | 待补：`frontend/src/views/UsersView.spec.ts#hides password fields and self disable action` |
| Sales 外壳不展示 Admin 入口 | active spec: Sales 不因外壳还原看到 Admin 入口 | 权限矩阵 | Unit/component | 当前角色为 `SALES` | 外壳导航不包含 Admin 专属入口 | nav 文本不存在 | P0 | 已有/待确认：`frontend/src/components/AppShell.spec.ts` |
| 中文文本浏览器可读 | active spec: 中文界面文本保持 UTF-8 可读 | 场景测试 | E2E/Runtime QA | 前端服务可启动；非无头浏览器可见 | 工作台、客户、用户页面中文标题/按钮/列名正常可读 | Playwright screenshot 或人工可见检查记录 | P1 | 待执行：headed browser smoke |

## TDD Candidates

| Test point | Initial failing test | Why it should fail before implementation | Expected Red failure reason | Minimal behavior to pass | Related regression |
| --- | --- | --- | --- | --- | --- |
| 关闭抽屉清空选中态且不触发写请求 | `DashboardView.spec.ts#closes drawer and clears selected lead without writes` | 当前草稿抽屉缺少显式关闭入口 | 找不到关闭按钮或点击后 `lead-drawer` 仍存在 | 增加关闭入口并在关闭时置空 `activeLeadId` | 抽屉打开详情、tab 切换清空选中 |
| tab 切换展示不同数据源 | `DashboardView.spec.ts#switches workbench tabs using existing store data` | 若实现未完整读取 pool/ended 派生结果，断言会失败 | 切换后表格仍显示旧视图数据或 ended 过滤不正确 | 使用现有 store 数据计算 `mine/pool/ended` rows | Dashboard 指标不变 |
| 筛选不重算四个指标 | `DashboardView.spec.ts#filters rows without changing metrics` | 如果实现把指标绑定到过滤后行数，断言会失败 | 筛选后指标文本发生变化 | 指标只使用 dashboard API 返回值 | Dashboard 指标格式 |
| 结束线索详情只读 | `LeadDetailPanel.spec.ts#renders ended lead as readonly` | 若表现调整误暴露操作入口，断言会失败 | 已结束线索出现写入按钮 | 保持 `closed` 派生控制写入口 | 现有赢单/流失/阶段行为 |
| 线索查重阻断状态 | `CustomersView.spec.ts#blocks lead creation when duplicate check rejects` | 若视觉重构破坏阻断逻辑，断言会失败 | `createLead` 被调用或阻断提示缺失 | 保留 `dupResult.canCreate=false` 的提交前阻断 | 客户创建和线索创建 |

## E2E Scenarios

| Scenario | Persona / role | Preconditions | User path | Critical assertions | Cleanup | Evidence on failure |
| --- | --- | --- | --- | --- | --- | --- |
| 工作台抽屉可见流程 | Sales | 已有可登录 Sales、名下至少一条线索 | 登录 -> 工作台 -> 点击线索 -> 查看抽屉 -> 关闭抽屉 | 抽屉初始不显示；点击后显示中文详情；关闭后隐藏 | 使用现有测试数据或 API 构造；不写业务状态 | screenshot / trace / network log |
| 客户页查重阻断流程 | Sales 或 Admin | 已有客户和同年同类型进行中线索 | 登录 -> 客户管理 -> 新建线索 -> 选择重复客户/类型 -> 尝试提交 | 显示阻断提示；不创建新线索 | 清理测试数据或使用隔离前缀 | screenshot / trace / network log |
| 用户管理视觉与权限烟测 | Admin | 已有 Admin 登录账号和至少一个 Sales | 登录 -> 用户管理 -> 打开新建 Sales 弹窗 | 表格不显示密码字段；角色/状态标签可读；新建弹窗中文可读 | 不提交新账号或使用隔离账号后清理 | screenshot / trace / network log |

## Non-TDD Exceptions

| Scope | Reason strict TDD does not apply | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| 纯 CSS 视觉还原：间距、阴影、hover 色、spark 图形 | 视觉样式不是稳定业务断言，组件测试不适合精确比较像素 | 浏览器非无头检查 + 必要截图；保留结构/状态组件测试 | 仍需人工判断“接近原型”的程度 |
| 中文终端输出乱码判断 | PowerShell 输出编码可能与文件编码不一致，不能作为 Red 证据 | 检查文件编码 + 浏览器渲染中文可读性 | 若浏览器未跑起来，只能保留风险 |
| E2E headed 可见过程 | E2E 用场景先行，不强制 Red-Green；本地环境和账号/数据可能影响运行 | 先定义场景，环境可用时执行 headed Playwright 或手工可见烟测 | 若环境不可用，需记录阻塞 |

## Prerequisite Blockers

| Blocker | Affected test point | Required owner action | Status |
| --- | --- | --- | --- |
| 暂未确认本机 headed browser/E2E 所需服务是否可用 | E2E 工作台抽屉、中文渲染、客户/用户页面烟测 | 实现后检查前端服务、后端服务和测试账号；不可用时记录具体缺失项 | 待确认 |

## Coverage Closure

- [ ] Each in-scope executable test point has a coverage artifact after prerequisites are available.
- [ ] New or modified tests were executed and results were recorded.
- [ ] Red tests failed for the expected behavior reason when strict TDD applies.
- [ ] Syntax, import, fixture, setup, or environment failures were not counted as valid Red evidence.
- [ ] Commands, reports, CI links, logs, screenshots, traces, or responses are recorded as execution evidence when relevant.
- [ ] Behavioral evidence describes what assertion proved.
- [ ] Coverage evidence maps each covered test point to a project-relative test path and optional `#testName`.
- [ ] Uncovered test points and unresolved prerequisite blockers are listed explicitly.
- [ ] Requirement conflicts are resolved or explicitly listed as BLOCKED.
- [ ] Runtime QA validation, if performed, is treated only as availability smoke evidence and not counted as Unit/API/E2E business coverage.

## Notes

- Baseline audit:
  - 当前未提交前端草稿已经部分实现原型风格工作台：四指标、三 tab、表格、默认隐藏的右侧详情区，以及客户/用户页的轻量视觉调整。
  - 当前主要缺口是：工作台抽屉缺少显式关闭入口；QA 先行产物和 Red 证据缺失；外壳 `AppShell.vue` 尚未按原型提醒卡片/品牌导航完整收口；客户/用户页还需围绕真实状态补测试和视觉收口。
  - 当前草稿未新增后端接口，基本符合“不改业务代码/接口语义”的设计约束。
- Encoding check:
  - PowerShell 输出中存在中文 mojibake，但 diff 中新增源码片段可见正常中文；后续仍需用浏览器渲染验证中文可读。
  - 修改文件时继续按 UTF-8 保存；终端显示乱码不单独作为源码损坏证据。
- Uncovered test points: 待 Red/实现/回归后更新。
- Remaining risks: 原型视觉贴合度需要浏览器可见验证；终端中文乱码不等于浏览器乱码。
- Execution evidence:
  - Red: `cd frontend; pnpm exec vitest run src/views/DashboardView.spec.ts`，结果 12 passed / 1 failed。失败用例 `lead drawer close behavior > closes the lead drawer and clears the selected row`，失败原因为 `Cannot call trigger on an empty DOMWrapper`，对应缺少 `[data-test="lead-drawer-close"]` 关闭入口。
  - Green: `cd frontend; pnpm exec vitest run src/views/DashboardView.spec.ts`，结果 13 passed。
  - Regression: `cd frontend; pnpm exec vitest run src/views/LeadDetailView.spec.ts`，结果 28 passed。
  - Regression: `cd frontend; pnpm exec vitest run src/views/CustomersView.spec.ts`，结果 13 passed。
  - Regression: `cd frontend; pnpm exec vitest run src/views/UsersView.spec.ts`，结果 10 passed。
  - Regression: `cd frontend; pnpm exec vitest run src/components/AppShell.spec.ts`，结果 6 passed。
  - Regression bundle: `cd frontend; pnpm exec vitest run src/views/DashboardView.spec.ts src/views/LeadDetailView.spec.ts src/views/CustomersView.spec.ts src/views/UsersView.spec.ts src/components/AppShell.spec.ts src/router/guards.spec.ts`，结果 6 files / 75 tests passed。
  - Static: `cd frontend; pnpm exec vue-tsc -b`，通过。
  - Build: `cd frontend; pnpm build`，通过，仅 Vite chunk size 警告。
  - Browser smoke: `cd frontend; $env:VISIBLE_SMOKE_HEADLESS='1'; node ..\\.codex_run\\restore-prototype-visible-smoke.mjs`，通过，截图 `.codex_run/restore-prototype-visible-smoke.png`。
- Behavioral evidence:
  - Red 证明当前工作台抽屉打开后没有可测试的关闭入口，无法完成“关闭后隐藏抽屉并清空选中态”的 active spec 行为。
  - Green 证明新增关闭入口后，抽屉可隐藏且表格选中态被清空。
  - 详情、客户、用户、外壳回归证明闭单只读、系统日志不展示、客户/线索校验、账号敏感字段和角色导航入口仍受保护。
  - 浏览器烟测证明中文登录页、工作台中文指标、抽屉打开/关闭、阶段轨道、客户管理页中文数据可正常渲染。
- Coverage evidence:
  - `frontend/src/views/DashboardView.spec.ts#closes the lead drawer and clears the selected row`
  - `frontend/src/views/LeadDetailView.spec.ts`
  - `frontend/src/views/CustomersView.spec.ts`
  - `frontend/src/views/UsersView.spec.ts`
  - `frontend/src/components/AppShell.spec.ts`
  - `.codex_run/restore-prototype-visible-smoke.png`
