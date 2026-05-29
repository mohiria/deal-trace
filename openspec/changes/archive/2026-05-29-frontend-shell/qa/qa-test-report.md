# QA Test Report — frontend-shell

## Conclusion

- Overall result: PASS（E2E 待真后端环境执行，已就绪并自动 skip 缺凭据场景）
- Requirement / change ID: `frontend-shell`（capability `frontend-workbench` 地基批次）
- QA owner: vibe-coding-qa（TDD 落地）
- Date: 2026-05-29
- Summary: 登录 / 未登录拦截 / 会话失效清退 / 刷新恢复 / 登出 / 按角色显隐入口（授权以后端为准）以严格 Red-Green 落地，29 个单元/组件测试全绿，`vue-tsc` 类型检查 + vite 构建通过。E2E 关键旅程脚本就绪，依赖真后端 + 部署注入 Admin 凭据方可执行。

## Evidence Guide

| Evidence type | What to record |
| --- | --- |
| Execution | `pnpm test:unit` → 6 files / 29 tests PASS；`pnpm build` → vue-tsc + vite build PASS |
| Behavioral | 见下方 TDD Summary 各行"行为证据" |
| Coverage | `src/**/...spec.ts#...`（见 Coverage Summary） |

## Scope

| Area | In scope? | Notes |
| --- | --- | --- |
| Unit | Yes | 拦截器 / store / 守卫 / 纯函数 |
| API/integration | Yes（前端侧）| 组件测试经 MSW 在 axios 边界拦截后端 |
| E2E | Yes（就绪待执行）| Playwright 真后端栈，缺凭据自动 skip |
| Regression | Yes | `unwrapEnvelope` 既有行为不回归 |
| Runtime QA validation | No | — |

## Requirement Authority / Conflict Review

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Test action | Code action |
| --- | --- | --- | --- | --- | --- | --- |
| 信封 unwrap + ApiError | `api/client.ts` / `client.spec.ts` | frontend-workbench spec R3 | extends | tech-arch §10 + auth-account spec | 保留既有 2 用例 + 新增 6 用例 | 扩展拦截器，不改 unwrap 语义 |
| 登录失败语义 | auth-account spec（后端 401 防枚举）| frontend-workbench spec R1 | extends | PRD §7.1 | 新增组件用例 | 前端如实展示后端 message |

无 `conflicts`。

## TDD Summary

| Test point | Source | Red evidence | Red failure reason | Green evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- | --- | --- |
| Bearer 注入 / 错误归一 / UNAUTHORIZED 分流 | spec R3 | `vitest run src/api/client.interceptors.spec.ts` 6 FAIL | token 未注入、错误未归一、handler 未触发（行为缺失）| 同命令 PASS | `src/api/client.interceptors.spec.ts` | PASS |
| store login/restore/logout/isAdmin | spec R1/R4/R5/R6 | `vitest run src/stores` 7 FAIL | `store.login is not a function` 等动作缺失 | 同命令 PASS | `src/stores/auth.spec.ts` | PASS |
| 路由两道守卫 | spec R2/R6 | `vitest run src/router` 失败（`authGuard` 模块缺失）| 守卫未实现 | 5 PASS | `src/router/guards.spec.ts` | PASS |
| 登录页校验/跳转/失败展示 | spec R1 | `vitest run src/views`（LoginView.vue 模块缺失 → 实现后 matchMedia/插件修复）| 组件缺失 | 4 PASS | `src/views/LoginView.spec.ts` | PASS |
| 角色导航显隐 + 登出 | spec R6/R5 | `vitest run src/components`（AppShell.vue 模块缺失）| 组件缺失 | 5 PASS | `src/components/AppShell.spec.ts` | PASS |

> 说明：net-new 模块的首个 Red 多表现为"目标模块/符号尚不存在"或"行为断言失败"。拦截器与 store 两组为纯行为缺失型 Red（符号已存在仍失败），证据最干净。

## Non-TDD Exceptions

| Scope | Reason | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| 设计 token（`style.css` / `arco-theme.css` 色板/圆角/阴影）| 纯样式低风险 | build 通过 + 组件渲染冒烟 | 视觉偏差 |
| 工作台外壳静态布局 chrome | 纯展示结构 | 组件渲染断言 + E2E 可见性 | 布局细节偏差 |

## Tests Run

| Layer | Suite | Command | Result | Evidence |
| --- | --- | --- | --- | --- |
| Unit/组件 | 全部 6 文件 | `pnpm test:unit` | PASS | 29/29 passed |
| 类型/构建 | — | `pnpm build` | PASS | vue-tsc + vite build 成功（731 模块）|
| E2E | auth-shell | `pnpm test:e2e` | 待执行 | 缺凭据自动 skip |

## Tests Not Run / Blockers

| Test / scope | Reason | Exact blocker | Required owner action | Residual risk |
| --- | --- | --- | --- | --- |
| E2E auth-shell | BLOCKED（环境）| 需真后端运行 + `E2E_ADMIN_EMAIL`/`E2E_ADMIN_PASSWORD` 与后端初始 Admin 一致 | 起后端 + dev server + 设凭据 | 低：核心行为已由组件层 MSW 覆盖 |

## Coverage Summary

| Test point | Layer | Behavioral evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- |
| 受保护请求注入 Bearer | Unit | 有 token 带 `Bearer`，无 token 不带 | `src/api/client.interceptors.spec.ts` | COVERED |
| 错误归一为 ApiError | Unit | 200-错误信封与非2xx-错误信封均抛 ApiError | 同上 | COVERED |
| UNAUTHORIZED 分流 | Unit | 仅 UNAUTHORIZED 触发 handler，FORBIDDEN 透传 | 同上 | COVERED |
| 登录成功/失败/恢复/登出/角色 | Unit | store 状态迁移与 localStorage | `src/stores/auth.spec.ts` | COVERED |
| 未登录拦截 / 已登录回落 / 角色守卫 | Unit | 守卫重定向目标 | `src/router/guards.spec.ts` | COVERED |
| 登录页校验/跳转/失败展示 | 组件 | 空提交不发请求；成功跳转；401 展示 message | `src/views/LoginView.spec.ts` | COVERED |
| 角色导航显隐 / 登出 | 组件 | Admin 入口对 SALES 不可见；登出清退 | `src/components/AppShell.spec.ts` | COVERED |
| 登录→工作台→登出→拦截 | E2E | 关键旅程 | `tests/e2e/auth-shell.spec.ts` | BLOCKED（环境）|

## Regression Scope

- Changed behavior: `api/client.ts` 新增请求拦截器与错误拦截器扩展；`auth` store 从占位升级为真实登录态；`router` 从空表升级为含守卫的路由表。
- Directly impacted old behavior: `unwrapEnvelope`（保持纯函数语义，原 2 用例仍 PASS）。
- Historical defects considered: 无（前端首个业务 change）。
- Regression risk level: Low（后端零改动；前端原仅脚手架）。
- Selected regression tests: `src/api/client.spec.ts`（既有 unwrap 用例）随全量套件执行，PASS。

## Remaining Risks

- Uncovered: 多标签页登录态同步（Non-Goal）。
- Unresolved blockers: E2E 需真后端环境（已文档化）。
- Conflicts: 无。
- Manual follow-up: 真环境跑一次 E2E 关键旅程。

## Final Statement

frontend-shell 的全部 spec 行为以严格 TDD 落地，29 个单元/组件测试全绿，类型检查与构建通过，既有 `unwrapEnvelope` 行为无回归。E2E 关键旅程脚本就绪，唯一未执行项为依赖真后端 + 部署注入 Admin 凭据的 E2E，已记录为环境型阻塞，核心行为已被组件层 MSW 覆盖，剩余风险低。
