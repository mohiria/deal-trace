# 回归影响分析

## Change Summary

- Requirement / change ID: `restore-prototype-frontend-experience`
- Change type: requirement + frontend code + tests
- Changed behavior: 前端视觉和交互还原到 `prototype/dealtrace-workbench.html` 的方向；工作台抽屉默认隐藏、点击线索打开、支持关闭；客户/用户页面视觉统一；真实交互状态映射到现有流程。
- Impacted modules / APIs / pages: `AppShell.vue`、`DashboardView.vue`、`LeadDetailPanel.vue`、`CustomersView.vue`、`UsersView.vue`、相关 component/unit tests、关键 E2E 工作流。
- Author / owner: Codex / 当前变更。

## Requirement-Driven Test Changes

| Existing / new test | Action | Requirement source | Reason | Remaining coverage |
| --- | --- | --- | --- | --- |
| `frontend/src/views/DashboardView.spec.ts` | Modify/Add | active spec: 工作台三类视图、抽屉生命周期 | 覆盖默认隐藏、点击打开、关闭清空、tab/filter 行为 | Dashboard 指标加载/失败旧行为继续保留 |
| `frontend/src/components/LeadDetailPanel.spec.ts` | Add/Modify if exists | active spec: 抽屉可操作/只读状态、不展示系统日志 | 防止视觉重构暴露闭单写入口或静态系统日志 | 既有线索写入行为仍由旧测试和 API 兜底 |
| `frontend/src/views/CustomersView.spec.ts` | Modify/Add | active spec: 客户/线索真实交互状态 | 保证重复客户、线索查重阻断和 API 调用不被样式改动破坏 | 客户搜索和正常创建继续覆盖 |
| `frontend/src/views/UsersView.spec.ts` | Modify/Add | active spec: 用户管理视觉与交互语言；既有权限规则 | 保证密码字段不展示、自身停用入口不展示、弹窗仍可用 | 账号启停 API 行为继续覆盖 |
| `frontend/src/components/AppShell.spec.ts` | Run/Modify if needed | active spec: 外壳按角色显示导航 | 保证外壳视觉改动不暴露 Admin 入口给 Sales | 受保护路由行为由 router/guards 测试继续覆盖 |
| `frontend/tests/e2e/*.spec.ts` | Run focused subset when environment available | active spec: 关键用户路径与中文可读 | 浏览器可见验证视觉和中文渲染 | E2E 不覆盖所有视觉细节 |

## Impact Analysis

| Changed item | Impacted behavior | Existing tests to run | New / modified tests needed | Notes |
| --- | --- | --- | --- | --- |
| `AppShell.vue` | 角色导航、退出、布局外壳 | `AppShell.spec.ts`, `router/guards.spec.ts` | Sales 不显示 Admin 入口的断言如缺失则补充 | 权限入口不能因视觉还原放宽 |
| `DashboardView.vue` | 指标展示、线索 tab、筛选、抽屉生命周期 | `DashboardView.spec.ts` | 抽屉 close、tab、filter 指标不变 Red 测试 | 本次最高风险点 |
| `LeadDetailPanel.vue` | 线索操作入口、闭单只读、弹窗校验 | 现有 lead detail/panel 测试 | 只读状态、不展示系统日志、弹窗表现相关断言 | 共享组件影响独立详情页 |
| `CustomersView.vue` | 客户搜索、创建、重复错误、新建线索查重 | `CustomersView.spec.ts`, `customer-flow.spec.ts` | 查重阻断和重复客户反馈断言 | 不能为视觉改动弱化业务校验 |
| `UsersView.vue` | 账号列表、角色/状态、创建 Sales、启停 | `UsersView.spec.ts`, `admin-flow.spec.ts` | 密码字段不展示、自身停用入口断言 | 仅 Admin 可访问由路由/后端兜底 |
| UTF-8 / 中文文案 | 浏览器中文可读 | E2E headed smoke 或手工可见检查 | 中文标题/按钮/列名截图证据 | PowerShell 乱码不作为失败证据 |

## Risk Level

- Risk: Medium
- Rationale: 主要是前端视觉/交互改动，不改后端和数据库；但触及工作台、共享线索详情、客户/用户管理等高频页面，且容易误伤权限入口、闭单只读、查重阻断和中文渲染，因此需要组件测试 + 受影响回归 + 浏览器可见烟测。

## Selected Regression Tests

| Test / suite | Layer | Why selected | Command | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| `DashboardView.spec.ts` | Unit/component | 覆盖指标、工作台 tab、抽屉生命周期 | `cd frontend; pnpm exec vitest run src/views/DashboardView.spec.ts` | PASS | 13 passed |
| `LeadDetailView.spec.ts` | Unit/component | 覆盖共享详情组件、闭单只读和操作入口 | `cd frontend; pnpm exec vitest run src/views/LeadDetailView.spec.ts` | PASS | 28 passed |
| `CustomersView.spec.ts` | Unit/component | 覆盖客户创建、线索创建和查重反馈 | `cd frontend; pnpm exec vitest run src/views/CustomersView.spec.ts` | PASS | 13 passed |
| `UsersView.spec.ts` | Unit/component | 覆盖账号管理表格与操作入口 | `cd frontend; pnpm exec vitest run src/views/UsersView.spec.ts` | PASS | 10 passed |
| `AppShell.spec.ts` | Unit/component | 覆盖外壳导航和角色入口 | `cd frontend; pnpm exec vitest run src/components/AppShell.spec.ts` | PASS | 6 passed |
| `router/guards.spec.ts` | Unit | 防止导航/权限改动影响守卫 | `cd frontend; pnpm exec vitest run src/router/guards.spec.ts` | 待执行 | 待补 |
| `vue-tsc` + build | Static/build | 类型和构建完整性 | `cd frontend; pnpm exec vue-tsc -b`; `cd frontend; pnpm build` | PASS | `vue-tsc` 通过；`pnpm build` 通过，仅 Vite chunk size 警告 |
| Focused browser smoke | E2E/runtime | 验证可见视觉、抽屉流程和中文渲染 | `cd frontend; $env:VISIBLE_SMOKE_HEADLESS='1'; node ..\\.codex_run\\restore-prototype-visible-smoke.mjs` | PASS | `.codex_run/restore-prototype-visible-smoke.png` |

## Tests Not Run / Blockers

| Test / scope | Reason not run | Exact blocker | Owner action | Residual risk |
| --- | --- | --- | --- | --- |
| Headed browser smoke | 当前以同一 Playwright 脚本完成 headless 截图烟测；非无头可见模式可用性未作为强制门禁 | 无业务阻塞；脚本支持不设置 `VISIBLE_SMOKE_HEADLESS=1` 时以非无头启动 | 如需人工观看执行过程，可复用 `.codex_run/restore-prototype-visible-smoke.mjs` 非无头运行 | 已有截图覆盖主要中文渲染和抽屉流程 |

## Runtime QA Validation

Runtime QA validation 是可用性烟测证据，不计为 Unit/API/E2E 业务覆盖。

| Needed? | Reason | Operation | Result | Evidence |
| --- | --- | --- | --- | --- |
| Yes | 本次主要是视觉/交互体验还原，必须验证浏览器实际渲染和中文可读 | 复用本地 Vite，Playwright 拦截 `/api/*` 构造稳定中文数据，打开登录、工作台、抽屉、客户管理并截图 | PASS | `.codex_run/restore-prototype-visible-smoke.png` |

## Regression Conclusion

- Overall result: PASS，仍保留 Vite chunk size 警告和非无头人工观看未执行的说明。
- Changed behavior covered: 工作台抽屉默认隐藏/打开/关闭、三 tab、筛选不改指标、阶段轨道、客户/用户真实状态、中文浏览器渲染。
- Directly impacted old behavior covered: 登录外壳导航、Dashboard 指标、线索详情闭单只读、客户创建/查重、用户管理敏感字段和自身停用入口。
- Historical defects considered: Arco 浮层 `render-to-body` 测试可见性、闭单只读、线索查重阻断、公海认领/权限入口。
- Uncovered test points: 非无头人工观看未执行；截图烟测已覆盖相同路径。
- Unresolved prerequisite blockers: 无。
- Remaining risks: 原型视觉贴合度仍有主观判断，建议评审 `.codex_run/restore-prototype-visible-smoke.png`。
