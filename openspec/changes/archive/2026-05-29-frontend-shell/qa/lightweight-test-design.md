# Lightweight Test Design — frontend-shell

## Context

- Requirement / Spec: `openspec/changes/frontend-shell/specs/frontend-workbench/spec.md`（6 条 ADDED 需求）
- Change summary: 前端地基——登录 / 登出 / 未登录拦截 / 会话失效清退 / 刷新恢复 / 按角色显隐入口（授权以后端为准）
- Target modules / pages: `src/api/client.ts`、`src/stores/auth.ts`、`src/router`、登录页组件、工作台外壳与导航组件
- Test environment / constraints: vitest + jsdom + @vue/test-utils，MSW 在 axios 边界拦截（`src/test/setup.ts` + `src/test/msw/*`）；E2E 用 Playwright 经 dev proxy 打真后端栈。tsconfig 严格模式。

## Input Sources Checked

- [x] Active Spec / PRD / acceptance criteria / issue（PRD §7.1、tech-arch §5.1/§10）
- [x] Existing behavior baseline: tests / code / old Spec / API contract（auth-account spec、`client.spec.ts`、`api/client.ts`）
- [x] Data model / field rules（login → `{token,email,name,role}`；me → `{id,email,name,role,status}`）
- [x] API contract / auth rules / error shape（统一信封 + `UNAUTHORIZED`/`FORBIDDEN`，401 防枚举）
- [x] UI states / user roles / user paths（ADMIN/SALES，登录/工作台/受保护区）
- [x] Code structure / changed code / dependency graph（D3 拦截器解耦、D5 声明式导航表）
- [x] Existing tests / historical defects（`unwrapEnvelope` 现有行为不回归）
- [x] Test data / credentials / mocks / CI constraints（MSW handler 工厂；E2E 用部署注入 Admin）

## Requirement Authority / Conflict Gate

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Result |
| --- | --- | --- | --- | --- | --- |
| 信封 unwrap + ApiError | `api/client.ts` / `client.spec.ts` | frontend-workbench spec | extends（新增 token 注入 + 401 分流，不改 unwrap 语义） | tech-arch §10 + auth-account spec | Proceed |
| 登录失败语义 | auth-account spec（后端 401 防枚举） | frontend-workbench spec | extends（前端如实展示后端 message，不自造枚举语义） | PRD §7.1 / auth-account spec | Proceed |

无 `conflicts`。

## Test Points

| Test point | Source / authority | Design method | Test layer | Input / precondition | Expected result | Assertion target | Priority | Coverage artifact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 受保护请求注入 Bearer token | spec R3 | 决策表 | Unit | 有/无 token | 有则带 Authorization，无则不带 | 请求头 | P0 | `src/api/client.spec.ts` |
| 错误归一为 ApiError（200-错误信封 & 非2xx-错误信封） | spec R3 / 现有契约 | 等价类 | Unit | 两类错误响应 | 均抛 ApiError 带 code/message | 异常类型与字段 | P0 | `src/api/client.spec.ts` |
| UNAUTHORIZED 触发会话失效处理器；其他错误码不触发 | spec R3 | 决策表 | Unit | 401-UNAUTHORIZED vs 其他 code | 仅前者触发 handler，后者透传 | handler 调用次数 + 抛出 | P0 | `src/api/client.spec.ts` |
| login 成功承载 `{token,email,name,role}` + 写 localStorage | spec R1 | 状态迁移 | Unit(store) | MSW 登录成功 | store 填充 + localStorage 有 token | store 状态 + storage | P0 | `src/stores/auth.spec.ts` |
| login 失败(401)不写态、抛 ApiError 带 message | spec R1 | 等价类 | Unit(store) | MSW 登录 401 | 态空、抛 ApiError(message) | 抛出 + 态 | P0 | `src/stores/auth.spec.ts` |
| restore：me 成功填充；me 401 清 token 且态空 | spec R4 | 状态迁移 | Unit(store) | 本地有 token + me 成功/401 | 成功填充；失败清退 | store 状态 + storage | P0 | `src/stores/auth.spec.ts` |
| logout 清 token+用户+localStorage | spec R5 | 状态迁移 | Unit(store) | 已登录 | 全清 | store + storage | P0 | `src/stores/auth.spec.ts` |
| isAdmin 随 role 派生 | spec R6 | 等价类 | Unit(store) | role=ADMIN/SALES | true/false | 派生值 | P1 | `src/stores/auth.spec.ts` |
| 未登录访问受保护路由→登录页；已登录访问登录页→工作台 | spec R2 | 决策表 | Unit(router) | 登录态×目标路由 | 守卫重定向 | 解析后的目标路由 | P0 | `src/router/guards.spec.ts` |
| SALES 访问 ADMIN 路由被回落 | spec R6 | 决策表 | Unit(router) | role=SALES + admin 路由 | 不渲染、回落 | 重定向目标 | P0 | `src/router/guards.spec.ts` |
| 空邮箱/空密码即时校验阻止提交且不发请求 | spec R1 | 边界值 | Unit(组件) | 空字段提交 | 阻止 + 无网络请求 | 校验提示 + 无请求 | P0 | `src/views/LoginView.spec.ts` |
| 正确凭据→承载令牌并跳转工作台 | spec R1 | 场景 | Unit(组件) | MSW 登录成功 | 跳转工作台 | 路由跳转 + store | P0 | `src/views/LoginView.spec.ts` |
| 401→展示后端 message、留登录页、不附加枚举语义；停用语义如实展示 | spec R1 | 等价类 | Unit(组件) | MSW 登录 401（不同 message） | 展示 message、留页 | 展示文案 | P0 | `src/views/LoginView.spec.ts` |
| 导航按 role 显隐 Admin 专属入口 | spec R6 | 决策表 | Unit(组件) | role=ADMIN/SALES | Admin 入口显/隐 | DOM 可见性 | P0 | `src/components/AppShell.spec.ts` |
| 登出入口触发 logout + 回落登录页 | spec R5 | 场景 | Unit(组件) | 已登录点击登出 | 清态 + 跳登录 | store + 路由 | P1 | `src/components/AppShell.spec.ts` |

## TDD Candidates

| Test point | Initial failing test | Why fail before impl | Expected Red reason | Minimal behavior to pass | Related regression |
| --- | --- | --- | --- | --- | --- |
| token 注入 + 401 分流 + 错误归一 | `client.spec.ts` 新增用例 | 拦截器尚未实现注入/分流 | 断言"带 Authorization"/"handler 被调用"失败 | 实现请求/错误拦截器 + setUnauthorizedHandler | 不回归 `unwrapEnvelope` |
| store login/restore/logout/isAdmin | `auth.spec.ts` | store 仅 token 占位 | 方法不存在 / 态未填充 | 实现 store 动作 | — |
| 路由两道守卫 | `guards.spec.ts` | 守卫未实现 | 未发生重定向 | 实现登录态/角色守卫 | — |
| 登录页校验与跳转 | `LoginView.spec.ts` | 组件不存在 | 渲染/校验/跳转断言失败 | 实现登录页 | — |
| 角色导航显隐 + 登出 | `AppShell.spec.ts` | 组件不存在 | 显隐断言失败 | 实现外壳 + 声明式导航表 | — |

## E2E Scenarios

| Scenario | Persona | Preconditions | User path | Critical assertions | Cleanup | Evidence on failure |
| --- | --- | --- | --- | --- | --- | --- |
| 登录→工作台→登出→受保护区被拦截 | 部署注入 Admin | 后端 + dev server 运行 | 输入凭据登录 → 见工作台 → 登出 → 直访受保护区 | 登录后到工作台；登出后回登录且受保护区不可见 | 无（只读 + 登出） | screenshot / trace |

## Non-TDD Exceptions

| Scope | Reason | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| 设计 token 抽取（色板/圆角/阴影 CSS 变量） | 纯样式、低风险展示 | 人工目测 + build 通过 | 视觉偏差，不影响行为 |
| 工作台外壳静态布局骨架 | 纯展示结构 | 组件渲染冒烟 + E2E 可见性 | 布局细节偏差 |

## Prerequisite Blockers

| Blocker | Affected test point | Required owner action | Status |
| --- | --- | --- | --- |
| 真后端 + 初始 Admin 凭据 | E2E 场景 | 部署配置注入 admin-email/password 并起后端 | 仅 E2E 执行时需要（组件测试用 MSW 不阻塞） |

## Coverage Closure

- [x] 每个 in-scope 可执行 test point 规划了 coverage artifact 路径
- [ ] 新增/修改测试已执行并记录（随各 TDD 组推进更新）
- [ ] 严格 TDD 项的 Red 因预期行为原因失败（随推进记录）
- [x] 语法/导入/fixture/环境失败不计为 Red 证据
- [ ] 命令/报告/日志作为执行证据记录（QA 报告阶段补）

## Notes

- Uncovered test points: 多标签页登录态同步（Non-Goal，不覆盖）
- Remaining risks: localStorage 存令牌的 XSS 面（design Open Questions 记录）
- Execution evidence: 见 `qa/qa-test-report.md`（实现完成后补）
